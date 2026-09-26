package com.ledgermind.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.ledgermind.TestApiKeys;

/**
 * OVF-4: a transfer that would push a posted counter past Long.MAX is rejected with 422 and writes nothing. Before
 * the fix {@code postedCredits += amount} wrapped silently to a negative counter (later read as tamper).
 */
@SpringBootTest(properties = {
        "ledgermind.journal.chain-delay-ms=3600000",
        "ledgermind.journal.checkpoint-delay-ms=3600000",
        "ledgermind.overdraft.sweep-delay-ms=3600000"
})
@AutoConfigureMockMvc
@Testcontainers
class AccountCounterOverflowTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Autowired
    private LedgerService ledger;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private MockMvc mvc;

    @BeforeEach
    void clean() {
        jdbc.execute("TRUNCATE journal_checkpoint, posting_hash, posting, account RESTART IDENTITY CASCADE");
        ledger.createAccount("external:funding", "ARS", true);
        ledger.createAccount("wallet:a", "ARS", false);
    }

    private ResultActions transfer(long amount, String key) throws Exception {
        return mvc.perform(post("/api/transfers").header("X-API-Key", TestApiKeys.key())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"debitAddress\":\"external:funding\",\"creditAddress\":\"wallet:a\",\"amount\":" + amount
                        + ",\"idempotencyKey\":\"" + key + "\"}"));
    }

    private long counter(String column, String address) {
        return jdbc.queryForObject("SELECT " + column + " FROM account WHERE address = ?", Long.class, address);
    }

    private long postings() {
        return jdbc.queryForObject("SELECT count(*) FROM posting", Long.class);
    }

    @Test
    void creditCounterOverflowIs422AndNothingIsWritten() throws Exception {
        jdbc.update("UPDATE account SET posted_credits = ? WHERE address = 'wallet:a'", Long.MAX_VALUE - 10);
        long before = postings();
        long fundingDebits = counter("posted_debits", "external:funding");

        transfer(11, "ovf-credit").andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.status").value(422));

        assertThat(postings()).as("posting rows added").isEqualTo(before);
        assertThat(counter("posted_credits", "wallet:a")).isEqualTo(Long.MAX_VALUE - 10);
        assertThat(counter("posted_debits", "external:funding")).isEqualTo(fundingDebits);
    }

    @Test
    void debitCounterOverflowIs422AndNothingIsWritten() throws Exception {
        jdbc.update("UPDATE account SET posted_debits = ? WHERE address = 'external:funding'", Long.MAX_VALUE - 10);
        long before = postings();

        transfer(11, "ovf-debit").andExpect(status().isUnprocessableEntity());

        assertThat(postings()).isEqualTo(before);
        assertThat(counter("posted_debits", "external:funding")).isEqualTo(Long.MAX_VALUE - 10);
        assertThat(counter("posted_credits", "wallet:a")).isZero();
    }

    @Test
    void transferThatLandsExactlyOnLongMaxIsAccepted() throws Exception {
        jdbc.update("UPDATE account SET posted_credits = ? WHERE address = 'wallet:a'", Long.MAX_VALUE - 11);

        transfer(11, "edge-ok").andExpect(status().is2xxSuccessful());

        assertThat(counter("posted_credits", "wallet:a")).isEqualTo(Long.MAX_VALUE);
    }
}
