package com.ledgermind.ledger.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;

import com.ledgermind.ledger.JournalCheckpoint;
import com.ledgermind.ledger.JournalCheckpointService;
import com.ledgermind.ledger.JournalSigner;
import com.ledgermind.ledger.JournalSignerRegistry;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The demo reset vs the {@code @Scheduled} checkpoint job, both signing the SAME chain head (seen on the live demo
 * 2026-10-10: the first POST /api/demo/reset after a fresh database returned HTTP 500, "duplicate key value violates
 * unique constraint journal_checkpoint_chain_seq_key" then {@code UnexpectedRollbackException}).
 *
 * <p>The interleaving is forced, not left to timing. Two threads, a "scheduler" calling
 * {@code checkpointIfHeadAdvanced()} as soon as the reset has chained its postings, and the real
 * {@code DemoSupportController.reset()}. A gate inside the signer holds BOTH until each has read the head and found no
 * checkpoint (so both want to insert chain_seq = N), then lets the scheduler insert and commit FIRST and only then lets
 * the reset insert, so the reset is always the loser of the UNIQUE(chain_seq) race, which is the user-visible 500.
 * (The gate waits are bounded and non-failing, so a fix that serializes the two writers with a lock still passes.)
 *
 * <p>Scheduled jobs are effectively disabled (huge delay) so that only the two threads above touch the journal.
 */
@SpringBootTest(properties = {
        "ledgermind.journal.chain-delay-ms=3600000",
        "ledgermind.journal.checkpoint-delay-ms=3600000"
})
@ActiveProfiles("demo")
@Testcontainers
class DemoResetCheckpointRaceTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Autowired
    private DemoSupportController demo;
    @Autowired
    private JournalCheckpointService checkpoints;
    @Autowired
    private JdbcTemplate jdbc;
    @MockitoSpyBean
    private JournalSignerRegistry registry;

    private final ExecutorService pool = Executors.newFixedThreadPool(2);
    private final CountDownLatch bothAreSigning = new CountDownLatch(2);
    private final CountDownLatch schedulerFinished = new CountDownLatch(1);
    private final ThreadLocal<Boolean> inReset = new ThreadLocal<>();

    @BeforeEach
    void gateTheSigner() {
        jdbc.execute("TRUNCATE journal_checkpoint, posting_hash, posting, account RESTART IDENTITY CASCADE");
        doAnswer(inv -> gated((JournalSigner) inv.callRealMethod())).when(registry).activeSigner(anyString());
    }

    @AfterEach
    void stopPool() {
        pool.shutdownNow();
    }

    /** Wraps the real signer: wait for the other writer, and make the reset insert only after the scheduler committed. */
    private JournalSigner gated(JournalSigner real) {
        return new JournalSigner() {
            @Override
            public String algorithm() {
                return real.algorithm();
            }

            @Override
            public String publicKeyBase64() {
                return real.publicKeyBase64();
            }

            @Override
            public String sign(byte[] data) {
                bothAreSigning.countDown();
                await(() -> bothAreSigning.await(5, TimeUnit.SECONDS));
                if (Boolean.TRUE.equals(inReset.get())) {
                    await(() -> schedulerFinished.await(10, TimeUnit.SECONDS));
                }
                return real.sign(data);
            }

            @Override
            public boolean verify(byte[] data, String signatureBase64, String publicKeyBase64) {
                return real.verify(data, signatureBase64, publicKeyBase64);
            }
        };
    }

    private interface Wait {
        Object run() throws Exception;
    }

    private static void await(Wait w) {
        try {
            w.run();
        } catch (java.util.concurrent.TimeoutException expiredGate) {
            // bounded wait: carry on (a lock-based fix keeps one writer out of the signer until the other is done)
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @RepeatedTest(3)
    void reset_does_not_fail_when_the_scheduler_signs_the_same_head_first() throws Exception {
        Future<?> reset = pool.submit(() -> {
            inReset.set(true);
            return demo.reset();
        });
        Future<Optional<JournalCheckpoint>> scheduler = pool.submit(() -> {
            try {
                // the scheduler wakes up once the reset has committed its chain links (the head exists)
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
                while (jdbc.queryForObject("SELECT count(*) FROM posting_hash", Long.class) < 5) {
                    if (System.nanoTime() > deadline) {
                        throw new IllegalStateException("the reset never chained its 5 postings");
                    }
                    Thread.sleep(20);
                }
                return checkpoints.checkpointIfHeadAdvanced();
            } finally {
                schedulerFinished.countDown();
            }
        });

        assertThatCode(() -> reset.get(90, TimeUnit.SECONDS))
                .as("POST /api/demo/reset must not fail when the scheduled job signed the same head first")
                .doesNotThrowAnyException();
        assertThatCode(() -> scheduler.get(90, TimeUnit.SECONDS))
                .as("the scheduled checkpoint job")
                .doesNotThrowAnyException();

        assertThat(jdbc.queryForObject("SELECT count(*) FROM journal_checkpoint", Long.class))
                .as("exactly one checkpoint for the head: two writers, one row").isEqualTo(1L);
        var audit = checkpoints.audit();
        assertThat(audit.checkpointPresent()).isTrue();
        assertThat(audit.tamperDetected()).isFalse();
    }
}
