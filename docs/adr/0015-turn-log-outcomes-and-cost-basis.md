# ADR 0015: Desenlaces del turno en `ia.turn_log` y base del cómputo de coste

- **Estado:** Aceptada (2026-09-26)
- **Fecha:** 2026-09-26
- **Origen:** la segunda revisión conjunta
  (`docs/reviews/2026-09-26-joint-integration-review-2.md`): el **punto 3 de ADR 0013** queda contradicho
  por el código, **H-05** (`ia.turn_log` no contiene todos los turnos que cuestan dinero) y el resto de
  **A-08** que ADR 0014 dejó explícitamente abierto.
- **Base y relación con otras ADR:** implementa el punto 3 de **ADR 0013**, cierra lo que **ADR 0014**
  dejó pendiente en su punto 4 y precisa la **fuente de verdad de ADR 0010**. No cambia el contrato HTTP ni
  el comportamiento del agente.
- **Alcance:** 100 % de este repo. **No requiere coordinación con otros repos** (el contrato observable no
  cambia: mismos 200/429/502/504; lo que cambia es la fidelidad del registro durable).

## Contexto

`ia.turn_log` se declara en ADR 0010 como la **fuente de verdad del consumo** y el guard de coste la agrega
en una sola consulta. Antes de esta ADR solo tenía dos desenlaces:

- `OK` — se escribía **también** para los turnos resueltos por `HandoffPolicy` (aunque no llaman al modelo,
  no usan prompt ni modelo y van con tokens a cero), así que desde la tabla **no se podía distinguir un
  turno derivado de uno normal**: eso contradice el punto 3 de ADR 0013, que promete "estado y motivo".
- `DEADLINE` — el turno cortado por el deadline (ADR 0014).

Y un turno que **sí llamaba al modelo** y fallaba después (backend caído o con error, proveedor tras los
reintentos, fallo dentro del agente) **no dejaba ninguna fila**: gastaba presupuesto que el tope no contaba.
La evidencia de la Fase 1 ya lo había corroborado (una fila `USER` de memoria sin fila en `turn_log`).

Efecto secundario del primer punto, señalado en el informe: esas filas de handoff **sí** contaban para los
topes **por turnos** del guard, aunque el guard ni se consulta en esa rama.

## Decisión

1. **Taxonomía de desenlaces.** `ia.turn_log.status` pasa a tener cuatro valores, y dos columnas nuevas
   recogen el detalle de los dos desenlaces que lo necesitan:

   | `status` | ¿llamó al modelo? | tokens | columnas nuevas | Quién lo escribe |
   |---|---|---|---|---|
   | `OK` | sí | reales | — | camino normal |
   | `HANDOFF` | **no** | 0 | `handoff_reason` | rama de `HandoffPolicy` |
   | `DEADLINE` | sí | 0 (desconocidos) | — | deadline del turno (ADR 0014) |
   | `ERROR` | sí | 0 (desconocidos) | `error_code` | `catch` del fallo posterior al modelo |

   `handoff_reason` es el motivo de la política (`HEALTH_TOPIC` | `COMPLAINT` | `EXPLICIT_REQUEST`) y
   `error_code` es un **conjunto cerrado** mapeado en código (`BACKEND_UNAVAILABLE` | `BACKEND_ERROR` |
   `MODEL_ERROR`), nunca el mensaje ni el nombre crudo de la excepción del proveedor: el registro es estable
   y no filtra detalles internos (R8). El mapeo es **por origen** (lo que no sea error del backend es fallo
   del camino del modelo) y no inspecciona tipos de Spring AI, para que siga siendo válido si cambia la
   versión del proveedor. El conjunto nace con tres códigos, todos alcanzables desde el código: si la Fase 2
   añade orígenes nuevos (herramientas de escritura), se añade el código que corresponda.

2. **Base del cómputo de coste.** El guard cuenta **todo desenlace que llamó al modelo**:
   `status <> 'HANDOFF'` en la consulta agregada. Un turno derivado no gasta tokens y no debe acercar a
   nadie a su tope; uno que falló después de llamarlo **sí** lo gasta y por tanto **sí** cuenta.
   Se elige la lista negra (y no una lista blanca ni una columna `model_called`) porque **fracasa del lado
   conservador**: si mañana aparece un estado nuevo y nadie lo clasifica, se cuenta (a lo sumo un usuario
   legítimo ve el 429 algo antes), mientras que una lista blanca dejaría turnos gratis sin que nadie lo
   note. Para que la decisión no se pueda olvidar, `TurnOutcomeClassificationTest` clasifica los estados con
   un **`switch` exhaustivo**: añadir un valor a `Status` **rompe la compilación del test** hasta que
   alguien decida si gasta presupuesto. Ese es el mecanismo que sustituye a una columna redundante.
   El nombre del estado se toma del enum al construir la consulta, de modo que un renombrado no pueda dejar
   el filtro mintiendo en silencio.

3. **Un turno rechazado por el tope no se registra** (se mantiene la decisión de ADR 0010): si cada rechazo
   escribiera, un abuso alimentaría su propio tope. Tampoco se registran los rechazos previos al modelo
   (400 de validación, 403 de tenant), que no llegan a gastar nada.

4. **Limitación aceptada y documentada:** los tokens de un turno `ERROR` (y de un `DEADLINE`) son
   **desconocidos**, no cero. Si el modelo ya generó y el fallo ocurre después, Spring AI solo expone el
   `usage` en la respuesta exitosa y esas cifras se pierden; se registran en 0. Consecuencia, dicha sin
   adornos: **el conteo de turnos es exacto y las medidas de tokens son una cota inferior**. Lo que acota el
   abuso son los topes de turnos, que ahora sí son completos. Capturar el uso parcial del proveedor queda
   como posible hallazgo diferido (Fase 5) si la calibración lo pide.

5. **Alcance del guardado:** el registro sigue sin poder tumbar el turno (ADR 0007) y sigue llevando
   `tenant_id` en toda fila (R5).

## Consecuencias

- **Positivas:** `ia.turn_log` pasa a ser de verdad la fuente de verdad que ADR 0010 promete; un turno
  derivado es distinguible y auditable (con su motivo) sin abrir la memoria ni el backend; el coste real de
  los turnos fallidos deja de ser invisible; el tope de turnos deja de inflarse con handoffs.
- **Negativas / riesgos:** dos columnas más en la tabla de registro y un `catch` más en el controlador (con
  su mapeo de códigos, que hay que mantener); la cota inferior de tokens descrita en el punto 4; una
  migración más (`V3`, aditiva y sin backfill: `HANDOFF` y `ERROR` no existen en ninguna base todavía).
- **Nada que coordinar:** el contrato HTTP no cambia, así que esta ADR no bloquea ni espera a otro repo.
  El consumo de `handoff_reason` por el aviso al staff es de ADR 0013 y sigue del lado del backend.

## Referencias

- H-05 y §3 (contradicciones) de `docs/reviews/2026-09-26-joint-integration-review-2.md`; ADR 0013
  (punto 3), ADR 0010 (fuente de verdad y medida), ADR 0014 (punto 4: el resto de A-08), ADR 0007
  (persistencia) y ADR 0009 (plazos).
- Código: `usage/TurnLogService.java` (`Status`, `ErrorCode`), `api/ChatController.java` (rama de handoff y
  `catch` de error), `usage/TurnCostGuard.java` (filtro del cómputo) y
  `src/main/resources/db/migration/V3__turn_log_outcomes.sql`.
- Tests: `TurnOutcomeClassificationTest` (clasificación exhaustiva), `TurnCostGuardTest` (handoff no cuenta,
  error sí, contra PostgreSQL), `ChatControllerTest` (los tres códigos de error y el estado del turno
  derivado), `ChatControllerHandoffTest` y `ChatApiTurnIntegrationTest` (fila `HANDOFF` real en la base).
- A-08 y C-13 (`AGENTS.md` §13).

