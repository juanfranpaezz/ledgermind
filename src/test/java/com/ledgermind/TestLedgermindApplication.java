package com.ledgermind;

import org.springframework.boot.SpringApplication;

public class TestLedgermindApplication {

	public static void main(String[] args) {
		SpringApplication.from(LedgermindApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
