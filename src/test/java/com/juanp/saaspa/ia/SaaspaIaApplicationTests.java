package com.juanp.saaspa.ia;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {"spring.ai.deepseek.api-key=test-key"})
class SaaspaIaApplicationTests {

	@Test
	void contextLoads() {
	}

}
