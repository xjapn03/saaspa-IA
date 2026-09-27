-- ============================================================================
-- saaspa-IA - hash del origen del turno para el tope de coste por origen (ADR 0020)
-- El tope del tenant lo podia agotar una sola IP en ~12 minutos (H-04). El guard
-- cuenta ahora los turnos por origen: el usuario si el turno esta identificado, la
-- IP resuelta por el backend si es anonimo (claim `clientIp` del turn token).
-- Se guarda un HMAC-SHA256 con sal, NUNCA la IP en claro (R8).
-- Sin backfill: las filas anteriores quedan con origen nulo, que no entra en
-- ninguna cubeta de origen y sigue contando para tenant y conversacion.
-- ============================================================================

ALTER TABLE ia.turn_log
    ADD COLUMN IF NOT EXISTS origin_hash VARCHAR(64);

COMMENT ON COLUMN ia.turn_log.origin_hash IS
    'HMAC-SHA256 (sal de entorno) de user:<id> o ip:<addr>; NULL si el turno no traia origen (ADR 0020)';

-- ----------------------------------------------------------------------------
-- El agregado del guard filtra por tenant, origen y rango de fecha en cada turno.
-- ----------------------------------------------------------------------------
CREATE INDEX IF NOT EXISTS turn_log_tenant_origin_created_idx
    ON ia.turn_log (tenant_id, origin_hash, created_at);
