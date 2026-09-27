# ADR 0011: Expiración de citas en PENDIENTE_PAGO y tope de reservas pendientes

- **Estado:** Propuesta (pendiente de aceptación; no implementada)
- **Fecha:** 2026-09-26
- **Origen:** el expiro de `PENDIENTE_PAGO` del informe conjunto (§5 punto 1 y §9 bloqueante 2), etiquetado
  **B-01** en el triaje (`docs/reviews/2026-09-26-joint-review-triage.md`).

## Contexto

En `saaspa-backend`, `findOccupied` cuenta como ocupada toda cita que no esté `CANCELADA`/`NO_ASISTIO`, y no
hay ningún trabajo que expire una cita sin pagar (`crontab` solo hace backups). Hoy la cita se crea a mano;
con la herramienta de escritura de la Fase 2, una conversación podría **bloquear franjas gratis** sin pagar
nada, y el reintento de una clienta que sí quiere pagar se encontraría su propia franja ocupada.

De este lado, el agente no puede deducir ni inventar una expiración (R4 y R11): solo puede explicar lo que el
backend calcule y devuelva.

## Decisión

1. **La implementación es 100 % de `saaspa-backend`:** el trabajo que expira las citas sin pagar, el tope de
   reservas pendientes por usuario y su métrica. Este repo **no** implementa la expiración, no la simula y no
   la infiere; el agente se limita a leer el estado.
2. **Lo que sí queda de este lado es el contrato** (`docs/contracts/internal-api.openapi.yaml`): qué expone una
   cita expirada.
   - **Propuesta:** añadir `EXPIRADA` al enum de `Booking.status` (hoy `PENDIENTE_PAGO`, `CONFIRMADA`,
     `CANCELADA`, `COMPLETADA`, `NO_ASISTIO`), para que el agente pueda explicar que la franja se liberó por
     falta de pago sin confundirlo con una cancelación de la clienta.
   - **Alternativa descartada (por ahora):** representar la expiración como `CANCELADA` con un motivo. Se
     descarta porque mezcla dos hechos distintos —la clienta canceló / el sistema expiró— justo en el dato que
     la Fase 2 necesita para un reintento de pago.
   - La **disponibilidad no necesita campo nuevo**: una franja liberada por expiración es una franja libre más
     y `GET /api/internal/v1/availability` ya la devuelve.
3. **Dataset de evaluación** (R15) en la misma pasada: un caso nuevo en `eval/` (por ejemplo
   `F2-franja-liberada-por-expiracion`) en el que la clienta pregunta por una franja que acaba de liberarse por
   expiración. El agente debe (i) no afirmar que la cita anterior sigue en pie, (ii) ofrecer la hora que
   devuelve la herramienta y no la del mensaje, y (iii) no prometer que el pago anterior sigue vigente. Si el
   backend todavía no expone el estado, el caso se marca como brecha (mismo patrón que A-14).

## Consecuencias

- **Positivas:** la agenda deja de poder bloquearse gratis desde una conversación; el agente podrá explicar el
  estado real; el contrato queda definido **antes** de que exista la herramienta de escritura.
- **Negativas:** mientras el backend no implemente la expiración, este ADR no cambia nada en runtime y solo
  evita documentar un contrato que no existe; el caso de `eval/` no puede pasar hasta entonces.
- **Pendiente de la persona:** aceptar el ADR. El trabajo de este repo es pequeño (contrato + un caso del
  dataset) y no bloquea la ola 1 más allá de la parte de contrato de `bookings`.

## Referencias

- B-01 (informe conjunto §5 y §9); triaje §3 y §4.
- `docs/contracts/internal-api.openapi.yaml` (`Booking`, `BookingCreate`) y ADR 0008 (política de escritura).
- Reglas R4, R11 y R15 (`AGENTS.md` §5).
