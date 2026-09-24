package com.juanp.saaspa.ia;

import org.springframework.boot.SpringApplication;

public class TestSaaspaIaApplication {

	public static void main(String[] args) {
		SpringApplication.from(SaaspaIaApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
