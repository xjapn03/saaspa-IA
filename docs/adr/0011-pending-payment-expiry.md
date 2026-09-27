# ADR 0011: Expiración de citas en PENDIENTE_PAGO y tope de reservas pendientes

- **Estado:** Aceptada (2026-09-26)
- **Fecha:** 2026-09-26
- **Implementación:** hecha en `saaspa-backend` (PR #77, fusionado el 2026-09-27 01:09 UTC). De este lado ya
  están el contrato (el enum de `Booking.status` expone `EXPIRADA`) y el caso
  `B01-franja-liberada-por-expiracion` del dataset `eval/`.
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
2. **Lo que queda de este lado es el contrato** (`docs/contracts/internal-api.openapi.yaml`), y **ya está
   hecho**: `Booking.status` expone `EXPIRADA` junto a `PENDIENTE_PAGO`, `CONFIRMADA`, `CANCELADA`,
   `COMPLETADA` y `NO_ASISTIO`, para que el agente pueda explicar que la franja se liberó por falta de pago
   sin confundirlo con una cancelación de la clienta.
   - **Alternativa descartada:** representar la expiración como `CANCELADA` con un motivo. Se descarta porque
     mezcla dos hechos distintos —la clienta canceló / el sistema expiró— justo en el dato que la Fase 2
     necesita para un reintento de pago.
   - La **disponibilidad no necesita campo nuevo**: una franja liberada por expiración es una franja libre más
     y `GET /api/internal/v1/availability` ya la devuelve.
3. **Lo que implementó el backend (PR #77)** y que este contrato asume: ventana de pago configurable
   (`BOOKING_PAYMENT_TTL_MINUTES`, 30 min por defecto); barrido cada 5 min más una pasada al arrancar que pasa
   las citas vencidas a `EXPIRADA` (condicional e idempotente, libera el lock de Redis y borra el evento de
   Calendar); el mismo filtro de ocupación para disponibilidad y solapes, así que la franja se libera aunque el
   barrido no haya corrido; tope de `BOOKING_MAX_PENDING_PER_USER` (2 por defecto) con **409** en
   `POST /api/bookings`; un pago tardío queda `APROBADO` y necesita decisión manual; `confirm` y `reschedule`
   rechazan una cita `EXPIRADA`.
4. **Dataset de evaluación** (R15), ya implementado: el caso **`B01-franja-liberada-por-expiracion`** (real, no
   brecha). La clienta dice que no alcanzó a pagar y pregunta si la hora sigue libre; el caso exige que la
   respuesta traiga una hora concreta (`HH:mm`, la que devuelve la herramienta) y que no afirme que la cita o
   el pago anteriores siguen vigentes. Depende de la evaluación con LLM real (el modelo guionizado del build no
   produce la hora) y de que el backend siga exponiendo `EXPIRADA`.

## Consecuencias

- **Positivas:** la agenda deja de poder bloquearse gratis desde una conversación; el agente podrá explicar el
  estado real; el contrato queda definido **antes** de que exista la herramienta de escritura.
- **Negativas:** el caso de `eval/` depende de la evaluación con LLM real (no la ejercita el modelo guionizado
  del build) y de que el backend siga exponiendo `EXPIRADA`: si dejara de hacerlo, el caso volvería a
  marcarse como brecha, con el patrón de A-14.
- **Estado:** aceptada. La parte del backend está implementada (PR #77) y la de este repo (contrato + caso del
  dataset `B01-franja-liberada-por-expiracion`) también; no queda nada pendiente salvo la verificación con el
  LLM real, que es de la Fase 5.

## Referencias

- B-01 (informe conjunto §5 y §9); triaje §3 y §4.
- `docs/contracts/internal-api.openapi.yaml` (`Booking`, `BookingCreate`) y ADR 0008 (política de escritura).
- Reglas R4, R11 y R15 (`AGENTS.md` §5).
