-- ============================================================================
-- saaspa-IA - estado del turno en `ia.turn_log` (ADR 0014)
-- Hasta ahora solo se registraban los turnos atendidos: un turno cortado por el
-- deadline del modelo no dejaba ninguna fila (hallazgo A-08). Los turnos ya
-- registrados son correctos, por eso el valor por defecto es 'OK'.
-- ============================================================================

ALTER TABLE ia.turn_log
    ADD COLUMN IF NOT EXISTS status VARCHAR(16) NOT NULL DEFAULT 'OK';

COMMENT ON COLUMN ia.turn_log.status IS 'OK | DEADLINE (ver ADR 0014)';
