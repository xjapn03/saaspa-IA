# ADR 0010: Control de abuso y tope de coste

- **Estado:** Propuesta (pendiente de aceptación; no implementada)
- **Fecha:** 2026-09-26
- **Origen:** hallazgo **J-03** de `docs/reviews/2026-09-26-joint-integration-review.md`; ola 1 del triaje
  (`docs/reviews/2026-09-26-joint-review-triage.md`).

## Contexto

Cada turno es una llamada de pago a un LLM y, desde la Fase 2, un posible cambio de estado (una cita). El
único control de abuso del sistema eran dos topes del backend, y **los dos eran evitables**:

- `app.set('trust proxy', true)` con Nginx añadiendo la IP real con `$proxy_add_x_forwarded_for`: Express leía
  la **primera** entrada de `X-Forwarded-For`, que es la que escribe el cliente, así que una cabecera bastaba
  para mover el bucket del límite de 20 req/min (y con él los logs y la auditoría).
- El tope de 30 mensajes/hora por sesión anónima se apoyaba en `sessionKeyHash` derivado de la cookie
  `kamerinos_chat_session`, y `resolveIdentity` aceptaba **cualquier** valor de 16+ caracteres: rotar la
  cookie reiniciaba el contador.

De este lado no había ningún límite (0 coincidencias de `Throttl|RateLimit|Bucket|CircuitBreaker` en
`src/main`).

## Decisión

**Mitad ya resuelta en `saaspa-backend`**, que este ADR da por buena y **no repite**: el PR #76
(`fix(security): trust one proxy hop and sign the chat session id`, fusionado el 2026-09-27 a las 00:38 UTC,
commit `16653735`) hace exactamente la mitad «sesión no falsificable»:

- `TRUSTED_PROXY_HOPS = 1` (`src/common/http/proxy-trust.ts`): Express lee la dirección que **añade Nginx**,
  no la que escribe el cliente. La alternativa de Nginx (`X-Forwarded-For $remote_addr`) queda descartada.
- El id de sesión anónima lo **emite y firma el servidor** (`src/modules/chat/chat-session.ts`): 128 bits,
  formato `^[0-9a-f]{32}$`, cookie `<id>.<hmac>` con clave derivada de `JWT_SECRET`, verificación en tiempo
  constante y fallo cerrado. El tope de mensajes por sesión ya descansa en un identificador del servidor.

**Lo que queda de este lado** (el objeto de este ADR):

- **Tope global por tenant** y **coste por conversación/turno** en este servicio (ya propuesto como **A-06**;
  el triaje lo eleva a bloqueante de cualquier herramienta de escritura). Mecanismo propuesto: límite de
  turnos por ventana y de tokens por conversación, contabilizados en el esquema `ia`, con respuesta **429**
  (`ProblemDetail`) al superarlos.
- **Fuente de verdad del consumo:** `ia.turn_log` (tokens y latencia por turno) y `ia.tool_call_log` (las
  herramientas). **Sin contador en memoria**: un reinicio no debe borrar el consumo.
- **Alcance por tenant:** la clave de memoria ya va namespaced (`{tenantId}:{channel}:{conversationId}`), así
  que el tope por tenant no exige cambiar el modelo de datos.
- **Los valores concretos no se deciden aquí:** el ADR fija el mecanismo, la fuente de verdad y la forma del
  error; los números se calibran en la Fase 5 con latencia y coste reales y quedan configurables por entorno.

**Fuera de esta ADR y de este repo:** el tope **por cuenta** para crear citas y el tope de reservas pendientes
(**B-01**, ADR 0011) son de `saaspa-backend`, en la misma pasada de escritura.

## Consecuencias

- **Positivas:** el coste del bot deja de ser ilimitado; el tope por tenant aísla a un tenant abusivo sin
  tocar a los demás; el 429 es explícito, medible y queda registrado.
- **Negativas:** hay que elegir ventana y umbral (con valores conservadores, un pico legítimo del salón —que
  comparte IP, como señala J-03— podría ver 429); el tope por conversación añade una consulta agregada por
  turno.
- **Pendiente de la persona:** aceptar el ADR; después, la rama de implementación
  (`feature/f2-per-tenant-cost-guard`, propuesta en el triaje).

## Referencias

- J-03 (`docs/reviews/2026-09-26-joint-integration-review.md`); triaje §3 y §4.
- A-06 (`AGENTS.md` §13); reglas R5, R9 y R11 (`AGENTS.md` §5).
- ADR 0008 (herramientas de escritura) y ADR 0011 (reservas pendientes).
- `saaspa-backend` PR #76 (`src/common/http/proxy-trust.ts`, `src/modules/chat/chat-session.ts`).
