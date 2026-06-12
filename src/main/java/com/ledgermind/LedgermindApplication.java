package com.ledgermind;

import java.util.TimeZone;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class LedgermindApplication {

	public static void main(String[] args) {
		// Un ledger de pagos vive en UTC. Lo fijamos ANTES de cualquier conexion a la DB
		// para que el driver de Postgres no envie el timezone del host (es_AR) y lo rechace.
		TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
		SpringApplication.run(LedgermindApplication.class, args);
	}

}
