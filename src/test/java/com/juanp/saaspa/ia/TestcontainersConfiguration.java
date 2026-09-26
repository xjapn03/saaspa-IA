package com.juanp.saaspa.ia;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

	// pgvector/pgvector:pg15 alineado con el backend (PostgreSQL 15).
	private static final DockerImageName POSTGRES_IMAGE =
			DockerImageName.parse("pgvector/pgvector:pg15").asCompatibleSubstituteFor("postgres");

	// Contenedor unico compartido por todos los contextos de test (patron singleton): evita levantar una
	// Postgres por clase y el agotamiento de recursos del entorno (varios @SpringBootTest a la vez).
	private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(POSTGRES_IMAGE);

	static {
		POSTGRES.start();
	}

	@Bean
	@ServiceConnection
	PostgreSQLContainer postgresContainer() {
		return POSTGRES;
	}

}
