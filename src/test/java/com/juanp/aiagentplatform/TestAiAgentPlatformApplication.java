package com.juanp.aiagentplatform;

import org.springframework.boot.SpringApplication;

public class TestAiAgentPlatformApplication {

	public static void main(String[] args) {
		SpringApplication.from(AiAgentPlatformApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
