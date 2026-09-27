# ADR 0014: Escalera de plazos del turno, correlación y estado de los turnos fallidos

- **Estado:** Aceptada (2026-09-26)
- **Fecha:** 2026-09-26
- **Origen:** hallazgo **J-04** del informe conjunto (escalera de timeouts invertida y turno fantasma);
  ola 3 del triaje.
- **Base:** **ADR 0009** ya decidió el mecanismo (timeout de lectura explícito, 2 intentos con backoff
  acotado y un deadline por turno que **cancela** la llamada en vuelo). Esta ADR **no cambia el
  mecanismo**: ajusta los valores para que la escalera end-to-end quede en el orden correcto y añade la
  correlación y el registro del turno cortado.
- **Coordinación:** **requiere coordinación con saaspa-backend, no fusionar de un solo lado.** Los
  números solo tienen sentido si los dos lados cambian a la vez y se revisan juntos antes del merge.

## Contexto

El orden lógico es `timeout de NestJS > deadline de IA > peor caso de un intento del modelo`. Estaba al
revés:

- NestJS cortaba a los 20 s (`IA_BOT_TIMEOUT_MS = 20000`).
- Este servicio seguía hasta 35 s (`saaspa.llm.turn-deadline`) y hasta ~61 s de peor caso (30 s de
  `read-timeout` + backoff + otros 30 s del reintento).

Consecuencias observadas por la revisión: trabajo huérfano (el modelo sigue generando cuando ya nadie
espera la respuesta), tokens gastados por un turno abandonado, llamadas a la API interna después de que
el backend ya respondió 504 y una respuesta que la clienta **nunca vio** pudiendo quedar en
`ia.spring_ai_chat_memory` como contexto del turno siguiente. Además, el 504 que veía la clienta era el
del backend por su propio abort, no el de este servicio, así que el mapeo de error documentado en el
contrato no se ejecutaba nunca con un modelo lento.

Y un turno cortado por el deadline **no dejaba ninguna fila** en `ia.turn_log` (hallazgo A-08): no había
forma de saber, del lado de este servicio, que ese turno existió y por qué falló.

## Decisión

1. **Escalera de plazos (valores ajustados):**

   | Nivel | Valor | Quién lo configura |
   |---|---|---|
   | Un intento de lectura del modelo | **10 s** | `saaspa.llm.read-timeout` (`LLM_READ_TIMEOUT`) |
   | Deadline del turno (tope duro) | **20 s** | `saaspa.llm.turn-deadline` (`LLM_TURN_DEADLINE`) |
   | Timeout con el que NestJS llama a este servicio | **25 s** | `IA_BOT_TIMEOUT_MS` (backend, PR paralelo) |

   **Invariante:** `read-timeout < turn-deadline < IA_BOT_TIMEOUT_MS`, y **nunca** al revés. Si el
   deadline supera el timeout del backend, el backend corta antes: vuelven los turnos huérfanos y el 504
   que ve la clienta deja de ser el de este servicio. Con 2 intentos y backoff acotado el peor caso
   teórico de un turno sigue por encima del deadline (2 × 10 s + backoff): es **deliberado**, el deadline
   es el tope duro y cancela lo que quede en vuelo.
   Los números quedan citados en los dos repos (aquí en `application.yml`, `.env.example` y esta ADR).

2. **Correlación:** el `ProblemDetail` del 504 incluye el **`turnId`** (propiedad `turnId`), para poder
   cruzar el fallo con el lado del backend y con la fila registrada aquí. Es la única clave de
   correlación entre los dos sistemas y hasta ahora el 504 no la llevaba.

3. **Estado de los turnos en `ia.turn_log`:** se añade la columna `status` (migración `V2`, valor por
   defecto `OK` para las filas ya existentes) y el turno cortado por el deadline se registra con
   `status = DEADLINE`, sin versión de prompt ni modelo y con tokens a cero (el modelo no llegó a
   responder). El turno atendido se registra con `OK`. El registro sigue sin poder tumbar el turno.

4. **Alcance:** esta ADR cubre el deadline y la correlación. Los demás fallos (502 del backend, errores
   inesperados) **siguen sin registrarse**: el resto de **A-08** continúa abierto y se cerrará cuando se
   decida registrar todos los desenlaces del turno (`ERROR`, códigos de error por tipo) en la misma
   pasada de la escritura.

## Consecuencias

- **Positivas:** el backend corta después de este servicio, así que el 504 que ve la clienta es el
  nuestro y el mapeo del contrato vuelve a ser cierto; se deja de gastar tokens por turnos abandonados;
  un fallo por deadline deja rastro consultable y correlacionable con el backend.
- **Negativas:** un turno legítimamente lento (catálogo + disponibilidad + redacción) puede acercarse al
  deadline de 20 s y perder alguna respuesta que antes llegaba a los 25-30 s; los valores quedan como
  algo a vigilar con datos reales de latencia (el dataset y la evaluación de la Fase 5 dan esa señal).
- **Riesgo si se despliega un solo lado:** con este servicio en 20 s y el backend todavía en 20 s, el
  backend cortaría justo antes; **no se fusiona este PR hasta tener el 25 s del backend delante.**

## Referencias

- J-04 (`docs/reviews/2026-09-26-joint-integration-review.md`); triaje §3 y §4.
- ADR 0009 (mecanismo de timeouts, reintentos y cancelación); A-08 y A-15 (`AGENTS.md` §13).
- `application.yml` (`saaspa.llm.*`), `.env.example`, `src/main/resources/db/migration/V2__turn_log_status.sql`
  y `docs/contracts/chat-api.openapi.yaml` (504 con `turnId`).
