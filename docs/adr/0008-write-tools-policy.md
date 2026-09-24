# ADR 0008: Política de herramientas de escritura

- **Estado:** Aceptado
- **Fecha:** 2026-09-23

## Contexto

Las herramientas de escritura (crear/reprogramar/cancelar cita, pedidos) modifican datos y tienen
consecuencias económicas (abono Wompi). El modelo no debe poder ejecutarlas por sí solo (regla R9).

## Decisión

- **Solo lectura primero:** la Fase 1 expone únicamente herramientas de lectura.
- **Feature flag** `ia.tools.write.enabled`, **apagado por defecto**.
- **Confirmación explícita** de la clienta antes de cualquier escritura: el agente resume la acción
  y pide confirmación; sin confirmación no hay llamada.
- **Idempotencia:** toda escritura lleva `Idempotency-Key` (derivada de `turnId` + acción) y el
  backend la respeta.
- **Auditoría:** cada tool call se registra en `ia.tool_call_log` (args, resultado, latencia, estado).
- La validación y ejecución ocurren **en NestJS** (R2), nunca en este servicio.

## Consecuencias

- **Positivas:** riesgo controlado y reversible con el flag; trazabilidad completa; sin dobles
  reservas ante reintentos.
- **Negativas:** flujo conversacional más largo (resumen + confirmación) y necesidad de manejar
  reintentos idempotentes en el backend.
