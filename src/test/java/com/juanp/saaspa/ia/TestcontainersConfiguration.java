package com.juanp.saaspa.ia;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@TestConfiguration(proxyBeanMethods = false)
class TestcontainersConfiguration {

	// pgvector/pgvector:pg15 alineado con el backend (PostgreSQL 15).
	private static final DockerImageName POSTGRES_IMAGE =
			DockerImageName.parse("pgvector/pgvector:pg15").asCompatibleSubstituteFor("postgres");

	// Redis estandar (la memoria de chat es JDBC; no se requiere Redis Stack).
	private static final DockerImageName REDIS_IMAGE = DockerImageName.parse("redis:7-alpine");

	@Bean
	@ServiceConnection
	PostgreSQLContainer postgresContainer() {
		return new PostgreSQLContainer(POSTGRES_IMAGE);
	}

	@Bean
	@ServiceConnection(name = "redis")
	GenericContainer<?> redisContainer() {
		return new GenericContainer<>(REDIS_IMAGE).withExposedPorts(6379);
	}

}
