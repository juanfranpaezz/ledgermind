package com.ledgermind;

import java.util.TimeZone;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class LedgermindApplication {

	public static void main(String[] args) {
		// A payments ledger lives in UTC. We pin it BEFORE any DB connection
		// so that the Postgres driver does not send the host timezone (e.g. es_AR) and reject it.
		TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
		SpringApplication.run(LedgermindApplication.class, args);
	}

}
