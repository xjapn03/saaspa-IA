-- ============================================================================
-- saaspa-IA - esquema propio `ia` (V1)
-- Este servicio NUNCA toca el esquema de negocio (Prisma/NestJS). Ver regla R6.
-- ============================================================================

CREATE SCHEMA IF NOT EXISTS ia;

-- ----------------------------------------------------------------------------
-- Memoria de conversacion (Spring AI JdbcChatMemoryRepository).
-- conversation_id va namespaced: "{tenantId}:{channel}:{conversationId}",
-- por eso se amplia a VARCHAR(255) (el esquema por defecto de Spring AI usa 36).
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS ia.spring_ai_chat_memory (
    conversation_id VARCHAR(255) NOT NULL,
    content         TEXT         NOT NULL,
    type            VARCHAR(10)  NOT NULL CHECK (type IN ('USER', 'ASSISTANT', 'SYSTEM', 'TOOL')),
    "timestamp"     TIMESTAMP    NOT NULL,
    sequence_id     BIGINT       NOT NULL
);

CREATE INDEX IF NOT EXISTS spring_ai_chat_memory_conv_ts_idx
    ON ia.spring_ai_chat_memory (conversation_id, "timestamp");

CREATE INDEX IF NOT EXISTS spring_ai_chat_memory_conv_seq_idx
    ON ia.spring_ai_chat_memory (conversation_id, sequence_id);

-- ----------------------------------------------------------------------------
-- Registro durable de turnos y uso de tokens (R5: todo dato propio lleva tenant_id).
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS ia.turn_log (
    id              BIGSERIAL    PRIMARY KEY,
    turn_id         UUID         NOT NULL,
    tenant_id       VARCHAR(64)  NOT NULL,
    conversation_id VARCHAR(255) NOT NULL,
    channel         VARCHAR(32)  NOT NULL,
    agent           VARCHAR(16)  NOT NULL,
    user_id         VARCHAR(64),
    role            VARCHAR(16),
    prompt_version  VARCHAR(32),
    model           VARCHAR(64),
    tokens_in       INTEGER      NOT NULL DEFAULT 0,
    tokens_out      INTEGER      NOT NULL DEFAULT 0,
    latency_ms      INTEGER      NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS turn_log_tenant_conv_idx
    ON ia.turn_log (tenant_id, conversation_id, created_at);

-- ----------------------------------------------------------------------------
-- Auditoria de tool calls.
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS ia.tool_call_log (
    id          BIGSERIAL   PRIMARY KEY,
    turn_id     UUID        NOT NULL,
    tenant_id   VARCHAR(64) NOT NULL,
    tool_name   VARCHAR(64) NOT NULL,
    status      VARCHAR(16) NOT NULL,
    latency_ms  INTEGER     NOT NULL DEFAULT 0,
    args_json   JSONB,
    result_json JSONB,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS tool_call_log_tenant_turn_idx
    ON ia.tool_call_log (tenant_id, turn_id);
