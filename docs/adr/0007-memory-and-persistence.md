# ADR 0007: Memoria y persistencia del agente

- **Estado:** Aceptado
- **Fecha:** 2026-09-23
- **Relacionado:** decisión D-MEM

## Contexto

Se necesita memoria de conversación durable y auditable, sin depender de Redis Stack
(`RedisChatMemoryRepository` lo exige) y sin tocar el esquema de negocio (regla R6).

## Decisión

- **Memoria:** `spring-ai-starter-model-chat-memory-repository-jdbc` sobre PostgreSQL 15, en el
  esquema propio `ia` (`JdbcChatMemoryRepository`).
- **`spring.ai.chat.memory.repository.jdbc.initialize-schema: never`**: la tabla la crea
  **Flyway V1**, nunca Spring AI.
- Tabla `ia.spring_ai_chat_memory` con el esquema que espera el starter:
  `conversation_id`, `content`, `type` (`USER|ASSISTANT|SYSTEM|TOOL`), `"timestamp"`,
  `sequence_id`. Se amplía `conversation_id` a `VARCHAR(255)` porque va namespaced.
- **`conversationId`** = `{tenantId}:{channel}:{conversationId}`.
- Se guardan **solo turnos finales** usuario/asistente. El detalle de tool calls va a
  `ia.tool_call_log`.
- **Ventana inicial:** 10 mensajes (`MessageWindowChatMemory`), configurable.
- **Redis estándar** queda solo para idempotencia, rate limiting y caché.

## Consecuencias

- **Positivas:** durable, auditable, con `tenant_id` y sin Redis Stack; esquema explícito y
  versionado por Flyway.
- **Negativas:** el `JdbcChatMemoryRepository` descarta silenciosamente los tool calls al guardar
  (asumido, cubierto por `tool_call_log`); `conversationId` debe caber en la columna ampliada.
