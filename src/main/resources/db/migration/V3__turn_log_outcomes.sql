-- ============================================================================
-- saaspa-IA - desenlaces del turno y base del computo de coste (ADR 0015)
-- `status` pasa a tener cuatro valores (OK | HANDOFF | DEADLINE | ERROR) y se
-- anaden el motivo del handoff y el codigo de error. No se tocan V1 ni V2.
-- Sin backfill: los estados HANDOFF y ERROR no existen en ninguna base todavia
-- y las filas antiguas (OK/DEADLINE) conservan su clasificacion.
-- ============================================================================

ALTER TABLE ia.turn_log
    ADD COLUMN IF NOT EXISTS handoff_reason VARCHAR(32),
    ADD COLUMN IF NOT EXISTS error_code VARCHAR(32);

COMMENT ON COLUMN ia.turn_log.status IS 'OK | HANDOFF | DEADLINE | ERROR (ver ADR 0015)';
COMMENT ON COLUMN ia.turn_log.handoff_reason IS
    'Motivo del handoff cuando status = HANDOFF: HEALTH_TOPIC | COMPLAINT | EXPLICIT_REQUEST';
COMMENT ON COLUMN ia.turn_log.error_code IS
    'Fallo del turno cuando status = ERROR: BACKEND_UNAVAILABLE | BACKEND_ERROR | MODEL_ERROR';

-- ----------------------------------------------------------------------------
-- El guard de coste (ADR 0010) filtra por tenant y rango de fecha en cada
-- turno; el indice existente (tenant_id, conversation_id, created_at) no cubre
-- ese rango porque `conversation_id` queda sin restringir en medio.
-- ----------------------------------------------------------------------------
CREATE INDEX IF NOT EXISTS turn_log_tenant_created_idx
    ON ia.turn_log (tenant_id, created_at);
