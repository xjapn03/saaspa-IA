# ============================================================================
# saaspa-IA - imagen de produccion (contenedor `ia-bot` de kamerinos-infra).
# Patron tomado del Dockerfile de saaspa-backend (multi-etapa, usuario no root,
# .dockerignore), adaptado a Java 21 + Spring Boot en lugar de Node.
# ============================================================================

# ----------------------------------------------------------------------------
# Etapa 1: build con Maven y JDK 21.
# Se usa el `mvn` de la imagen oficial y no `./mvnw` por dos motivos verificados:
# el wrapper del repo es `distributionType=only-script` (descargaria Maven dentro
# del build) y `mvnw` depende del bit de ejecucion, que en este equipo no
# sobrevive a git (`core.fileMode=false`).
# ----------------------------------------------------------------------------
FROM docker.io/library/maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace

# Capa de dependencias: solo se reconstruye si cambia el pom.
COPY pom.xml ./
RUN mvn -B -q dependency:go-offline

# Codigo y empaquetado. Los tests NO se ejecutan aqui (necesitan Docker para
# Testcontainers y los corre CI con `./mvnw -B verify`), pero SI se compilan.
COPY src ./src
RUN mvn -B -DskipTests package

# ----------------------------------------------------------------------------
# Etapa 2: runtime, solo JRE 21.
# ----------------------------------------------------------------------------
# Imagenes referidas con el registro completo (`docker.io/library/...`): en el equipo de desarrollo `docker`
# es Podman 5.8.7 y la resolucion de nombres cortos exige un TTY; el nombre completo funciona igual en Docker
# y en Podman, sin tocar la configuracion del sistema.
FROM docker.io/library/eclipse-temurin:21-jre

# El compose de kamerinos-infra limita el contenedor (`mem_limit: 768m`): sin
# MaxRAMPercentage la JVM se dimensiona contra la RAM del host, no del cgroup.
# HOME en /tmp porque el usuario no tiene directorio propio escribible.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0" \
    HOME=/tmp

WORKDIR /app

# No ejecutar como root (seguridad), igual que el contenedor del backend.
RUN groupadd --system --gid 10001 spring \
 && useradd --system --uid 10001 --gid spring --no-create-home --shell /usr/sbin/nologin spring \
 && chown spring:spring /app

COPY --from=build /workspace/target/*.jar /app/app.jar

USER spring

# Puerto del servicio (configurable con PORT, por defecto 8000). El `ia-bot` NO
# publica puertos: solo lo llama el backend por la red interna.
EXPOSE 8000

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
