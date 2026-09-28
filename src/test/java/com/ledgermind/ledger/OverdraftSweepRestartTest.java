package com.ledgermind.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import com.ledgermind.LedgermindApplication;
import com.ledgermind.ledger.OverdraftSweeper.SweepResult;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The sweep's watermark survives a REAL restart of the Spring context (one is closed and another is started on the
 * same database): the first pass of the new context re-derives no account. Control in the same test: if the
 * watermark is lost (set to 0), the same pass DOES touch the 3 accounts again, so the assertion can fail.
 */
@Testcontainers
class OverdraftSweepRestartTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    private static ConfigurableApplicationContext start() {
        // as command-line arguments: they win over application.yml (the builder's .properties() do not)
        return new SpringApplicationBuilder(LedgermindApplication.class).run(
                "--spring.datasource.url=" + postgres.getJdbcUrl(),
                "--spring.datasource.username=" + postgres.getUsername(),
                "--spring.datasource.password=" + postgres.getPassword(),
                "--server.port=0",
                "--ledgermind.journal.chain-delay-ms=3600000",
                "--ledgermind.journal.checkpoint-delay-ms=3600000",
                "--ledgermind.overdraft.sweep-delay-ms=3600000",
                "--ledgermind.overdraft.sweep-initial-delay-ms=3600000",
                "--ledgermind.overdraft.watermark-lag-ms=0");
    }

    @Test
    void la_marca_de_agua_persiste_a_traves_de_un_reinicio_del_contexto() {
        long watermarkBeforeRestart;
        try (ConfigurableApplicationContext first = start()) {
            LedgerService ledger = first.getBean(LedgerService.class);
            ledger.createAccount("external:funding", "ARS", true);
            ledger.createAccount("wallet:a", "ARS", false);
            ledger.createAccount("wallet:b", "ARS", false);
            ledger.transfer("external:funding", "wallet:a", 100_000, "seed-a");
            ledger.transfer("external:funding", "wallet:b", 100_000, "seed-b");
            SweepResult r = first.getBean(OverdraftSweeper.class).sweep();
            System.out.println("[RESTART][before] " + r);
            assertThat(r.touchedAccounts()).isEqualTo(3);
            watermarkBeforeRestart = r.newWatermark();
        }
        assertThat(watermarkBeforeRestart).isEqualTo(2L);

        try (ConfigurableApplicationContext second = start()) {
            JdbcTemplate jdbc = second.getBean(JdbcTemplate.class);
            assertThat(jdbc.queryForObject("SELECT watermark_posting_id FROM overdraft_sweep_state WHERE id = 1",
                    Long.class)).isEqualTo(2L);
            SweepResult after = second.getBean(OverdraftSweeper.class).sweep();
            System.out.println("[RESTART][after] " + after);
            assertThat(after.previousWatermark()).isEqualTo(2L);
            assertThat(after.touchedAccounts()).isZero();

            // control: with the watermark lost, the same pass re-derives the 3 accounts
            jdbc.update("UPDATE overdraft_sweep_state SET watermark_posting_id = 0 WHERE id = 1");
            SweepResult lost = second.getBean(OverdraftSweeper.class).sweep();
            System.out.println("[RESTART][control-lost-watermark] " + lost);
            assertThat(lost.touchedAccounts()).isEqualTo(3);
        }
    }
}
