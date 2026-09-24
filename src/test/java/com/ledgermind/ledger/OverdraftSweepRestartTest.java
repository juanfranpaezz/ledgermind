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
 * La marca de agua del barrido sobrevive a un reinicio REAL del contexto de Spring (se cierra y se levanta otro sobre
 * la misma base): la primera pasada del contexto nuevo no re-deriva ninguna cuenta. Control en el mismo test: si la
 * marca se pierde (se pone en 0), la misma pasada SI vuelve a tocar las 3 cuentas, o sea que la asercion puede fallar.
 */
@Testcontainers
class OverdraftSweepRestartTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    private static ConfigurableApplicationContext start() {
        // como argumentos de linea de comando: le ganan a application.yml (las .properties() del builder no)
        return new SpringApplicationBuilder(LedgermindApplication.class).run(
                "--spring.datasource.url=" + postgres.getJdbcUrl(),
                "--spring.datasource.username=" + postgres.getUsername(),
                "--spring.datasource.password=" + postgres.getPassword(),
                "--server.port=0",
                "--ledgermind.journal.chain-delay-ms=3600000",
                "--ledgermind.journal.checkpoint-delay-ms=3600000",
                "--ledgermind.overdraft.sweep-delay-ms=3600000",
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

            // control: con la marca perdida, la misma pasada re-deriva las 3 cuentas
            jdbc.update("UPDATE overdraft_sweep_state SET watermark_posting_id = 0 WHERE id = 1");
            SweepResult lost = second.getBean(OverdraftSweeper.class).sweep();
            System.out.println("[RESTART][control-lost-watermark] " + lost);
            assertThat(lost.touchedAccounts()).isEqualTo(3);
        }
    }
}
