# Revisión conjunta de integración #2 — verificación de la ola 1 + J-04

- **Autor:** revisión externa (Hermes) a petición de la persona.
- **Fecha:** 2026-09-26 (madrugada del 27 UTC).
- **Ámbito:** solo lectura sobre `saaspa-backend`, `saaspa-IA`, `kamerinos-infra` (despliegue) y una lectura
  puntual de `saaspa-frontend` (consumidores del enum nuevo).
- **Refs verificadas (no lo que dicen los PR ni las ADR):**
  - `saaspa-backend` → `origin/develop@6181e7a` (PRs #77, #78, #79, #80), árbol de trabajo limpio.
  - `saaspa-IA` → `origin/develop@19d8e6c` (PRs #28, #29, #30), árbol de trabajo limpio.
  - `kamerinos-infra` → **árbol de trabajo con `docker-compose.yml` modificado y sin commitear** (51 líneas
    nuevas): es el único sitio donde existe el contenedor `ia-bot` hoy.
- **Informe anterior:** `docs/reviews/2026-09-26-joint-integration-review.md` (hallazgos J-01..J-13).
  Triaje: `docs/reviews/2026-09-26-joint-review-triage.md`. Este informe **no** lo sustituye ni lo edita.
- **Método:** lectura del código real de los cinco cierres, de sus pruebas y de las cinco ADR (0010–0014)
  para contrastar decisión ↔ implementación; lectura del compose/nginx de infra; lectura puntual del
  frontend para el enum nuevo. **No ejecuté ninguna suite** (escribirían en `target/`, `dist/` y
  `test-results/`): las cifras de tests son las que declaran los repos, no las re-verifiqué.

---

## 0. Veredicto

**Los cinco bloqueantes están cerrados en el código de verdad, y con pruebas que los sostienen** (no solo
en el changelog). La calidad de la implementación es alta: el filtro de ocupación es *uno solo* para
disponibilidad y solapes, el barrido es condicional e idempotente, la firma de sesión falla cerrada, la
prueba arquitectónica de identidad es real (descubre controladores del sistema de archivos y valida
`paramtypes`), y el handoff ya tiene destino, texto recuperable y reversión auditada.

Dicho eso, encontré **tres huecos que nacen justo al cerrar estos cinco** y tres menores:

1. **El despliegue revierte J-04 y deja J-01 abierto.** El `ia-bot` del compose fija
   `LLM_READ_TIMEOUT: 30s` / `LLM_TURN_DEADLINE: 35s`, es decir **los valores que J-04 vino a corregir**,
   mientras el backend mantiene 25 s: la escalera vuelve a estar invertida en producción. Y el contenedor
   no se puede ni construir: **`saaspa-IA/Dockerfile` no existe**, y ninguna de las variables del acople
   está en el `.env`/`.env.example` de infra. (H-02)
2. **B-01 deja una carrera pago ↔ expiración sin resolver, con dos desenlaces y los dos malos**: pago
   aprobado sin franja (la clienta paga y no recibe ni correo) o **dos citas CONFIRMADA en la misma
   franja**. Está **vivo hoy**, no es riesgo de Fase 2, porque el pago ya está en producción. (H-01)
3. **El aviso de handoff no tiene garantía de entrega**: el fallo del correo se traga con un `warn`, el
   estado no registra si se avisó y el latch no reintenta. (H-03)

Menores: el nuevo 429 llega a la clienta como 502 (H-04); `ia.turn_log` no contiene todos los turnos que
cuestan dinero, así que el tope de ADR 0010 mide menos de lo que cree (H-05); el estado `EXPIRADA` no
tiene consumidor en el frontend y un EXPIRADA se puede "cancelar" (H-06).

---

## 1. Estado verificado de los cinco

| Ítem | Estado real | Evidencia principal | Qué queda |
|---|---|---|---|
| **J-03** abuso/coste | **Cerrado en los dos repos.** BE: `TRUSTED_PROXY_HOPS = 1` y sesión anónima firmada con HMAC (falla cerrada sin `JWT_SECRET`). IA: `TurnCostGuard` con 4 topes sobre `ia.turn_log` y 429 con `scope/measure/measured/limit/window`. | BE `src/common/http/proxy-trust.ts:14,17-19`, `src/main.ts:30`, `src/modules/chat/chat-session.ts:11,20-25,33-56`, `src/modules/chat/chat.service.ts:105-110,259-294`; IA `usage/TurnCostGuard.java:32-39,64-88`, `api/ApiExceptionHandler.java:83-93` | 3 residuales: el 429 no llega como 429 al widget (H-04); el tope por conversación se salta rotando el `conversationId` (el control real es el throttle por IP); el tope de tenant (240/h) es alcanzable por una sola IP en ~12 min y deja a **todas** las clientas fuera (H-04) |
| **B-01** expiro de PENDIENTE_PAGO | **Cerrado y bien hecho.** Ventana configurable, barrido cada 5 min + pasada al arrancar, `markExpired` condicional, libera el lock de Redis y borra el evento de Calendar, tope de pendientes con 409, `confirm` y `reschedule` rechazan EXPIRADA, **un solo filtro de ocupación** para disponibilidad y solapes. | BE `bookings.service.ts:49,60,88,151-167,273-294,332-338`, `bookings.repository.ts:50-57,143-156,212-242`, `pending-payment-expiry.scheduler.ts`, `prisma/schema.prisma:17-24`, migraciones `20260926235900_*`; IA `docs/adr/0011…` + `eval/customer-agent.v1.jsonl:30` | **H-01** (carrera con el pago) y **H-06** (el enum nuevo no tiene consumidor en el frontend) |
| **J-08** identidad desde el token | **Cerrado como mecanismo, no ejercitado.** `@TurnContext` lee el token verificado, `requireTurnUser` da 403 sin identidad, y la prueba arquitectónica es de verdad (descubre controladores desde el sistema de archivos, inspecciona `paramtypes`, mira los campos del DTO y tiene autotests con controladores sonda). | BE `internal/decorators/turn-context.decorator.ts:9-15`, `internal/turn-identity.ts:31-39`, `guards/internal-auth.guard.ts:41`, `internal/__tests__/internal-identity-contract.spec.ts:189-245` | **Ningún endpoint interno consume identidad todavía** (correcto: Fase 2). El riesgo *cross-repo* que motivó J-08 sigue sin ejercitarse, y el inspector no cubre `@Req()` (leer `req.body.userId` a mano pasaría) |
| **J-09** idempotencia | **Cerrado en el endpoint que existe, pendiente donde importará.** `Idempotency-Key` en `POST /bookings` y `/bookings/admin`, índice único + `P2002` → devuelve la cita del ganador, 409 si la clave es de otra operación, con e2e. | BE `bookings.controller.ts:75-118`, `bookings.repository.ts:162-181`, `bookings.service.ts:200-214`, `prisma/schema.prisma:167`, `test/e2e/bookings-idempotency.e2e-spec.ts:114,132,141` | `POST /api/internal/v1/bookings` (el que llamará la herramienta de escritura) **no existe**: la mitad cross-repo sigue abierta por diseño. Anotarlo como puerta del primer `crearCita` |
| **J-05** handoff | **Cerrado con destino, texto y reversión.** El backend avisa por correo con motivo, conversación, turno, si es anónima o registrada y el **texto que disparó el handoff** (escapado), todo con el id para cerrarlo; persiste `handoffMessage`; `PATCH /chat/conversations/:id/handoff` con roles y auditoría; IA prueba que la memoria **no** se escribe en un turno con handoff. | BE `chat.service.ts:122-129,184-195,226-253,344-389,391-403`, `common/email/email.service.ts:379-410`, `prisma/schema.prisma:250-272`, `test/e2e/chat-handoff.e2e-spec.ts:121,145,170`; IA `ChatApiTurnIntegrationTest.java:179-198` | **H-03** (sin estado de entrega ni reintento) y la divergencia menor del §3. Sin endpoint de lectura ni bandeja (pospuesto: coherente con el ADR). Al cerrar, la clienta vuelve a un bot cuya memoria tiene un hueco |
| **J-04** escalera de plazos | **Cerrado en los dos repos y anclado con test.** 10 s / 20 s / 25 s, `turnId` en el `ProblemDetail` del 504 y fila `status = DEADLINE` (migración V2). | IA `application.yml:91-97`, `ChatController.java:123-125,163-188`, `TurnLogService.java:24-26,59-65,86-88`, `chat-api.openapi.yaml:85-89,209-212`; BE `chat.constants.ts:39-46`, `__tests__/timeout-ladder.spec.ts:20-32` | **H-02**: el despliegue lo revierte. Y el anclaje es por repo con los números de IA **copiados a mano** en el test de BE (`timeout-ladder.spec.ts:16-17`): un cambio en IA no lo detecta nadie (misma clase de gap que J-13) |

---

## 2. Hallazgos nuevos

### H-01 — La carrera entre el expiro y el pago no está resuelta, y los dos desenlaces son malos (Alta)

**Evidencia.**
- BE `bookings.service.ts:60` (`pendingPaymentDeadline` = ahora − `BOOKING_PAYMENT_TTL_MINUTES`, 30 min) y
  `bookings.repository.ts:50-57`: el filtro de ocupación **deja de contar** una `PENDIENTE_PAGO` cuya
  `createdAt` pasó el deadline, aunque el barrido (cada 5 min, `pending-payment-expiry.scheduler.ts:24-29`)
  todavía no le haya cambiado el estado.
- BE `booking-sync.service.ts:18-31`: `confirmAndSync` **rechaza** `EXPIRADA` (y CANCELADA/COMPLETADA/
  NO_ASISTIO) pero **no vuelve a comprobar la ventana ni el solape**.
- BE `payments.service.ts:134-151`: el webhook marca el pago `APROBADO` **y después** llama a
  `confirmAndSync` (`:147`). No hay ningún `try/catch` alrededor (los únicos del método son `:119-132`,
  para localizar el pago), así que la excepción **propaga** hasta `payments.controller.ts:98-103` (que
  devuelve la promesa del servicio tal cual, sin filtro global): Wompi recibe **400** en el primer intento.
  `:135-138` el reintento de Wompi entra por el atajo "webhook duplicado" y se responde `200 recibido` sin
  volver a intentar nada; `:153-218` el recibo al cliente y el aviso al salón van **después** de la
  llamada que lanza, así que no se envían.
- BE `payments.service.ts:49-51`: iniciar un pago nuevo **sí** se bloquea si la cita ya no está
  `PENDIENTE_PAGO` (esto reduce la exposición, no la elimina: la carrera es de un pago *ya iniciado*).

**Los dos desenlaces** (mutuamente excluyentes según si el barrido llegó a correr):

- **A — el barrido ya corrió (`EXPIRADA`):** el pago queda `APROBADO`, `confirmAndSync` **lanza**, la
  excepción sale de `handleWebhook` (Wompi recibe error), el reintento de Wompi se ignora como duplicado y
  **no se envía recibo ni aviso**. Resultado: la clienta pagó, su cita dice `EXPIRADA`, no recibe nada y
  nadie se entera. ADR 0011 punto 3 ya anticipa "un pago tardío queda `APROBADO` y necesita decisión
  manual", pero **la implementación no ofrece esa decisión**: no hay bandeja, aviso, métrica ni vínculo
  entre el pago aprobado y la cita expirada (habría que cruzarlos a mano entre `GET /api/payments` y
  `GET /api/bookings`).
- **B — el barrido todavía no corrió (sigue `PENDIENTE_PAGO`, ya fuera de ventana):** la franja se liberó
  para disponibilidad (30 min) así que otra clienta **puede reservarla**; cuando llega el webhook, la cita
  sigue `PENDIENTE_PAGO`, así que `confirmAndSync` **confirma sin mirar el solape** → **dos citas
  `CONFIRMADA` en la misma franja**. Este caso no está en la ADR ni en ninguna prueba.

**Cobertura de pruebas:** el e2e de expiro cubre "libera la franja" y "tope de pendientes"
(`test/e2e/pending-payment-expiry.e2e-spec.ts:143,173`) y el unitario cubre "confirmar una expirada lanza"
(`bookings.service.spec.ts:444`), pero **no hay ningún test de pago aprobado después del expiro ni de la
franja re-reservada**.

**Propuesta (sin implementar).** Que la confirmación sea atómica y con tres salidas explícitas: (1)
re-verificar en el webhook que la ventana sigue abierta *y* que la franja está libre (misma consulta de
solape, con `pendingPaymentDeadline` del momento), (2) si la ventana venció pero el pago llegó, **no**
lanzar: dejar la cita en un estado intermedio auditable ("pago recibido, franja por asignar") con aviso al
salón por el mismo módulo de correo y (3) si la franja ya se ocupó, marcar el pago como "revisar/
reembolsar" y avisar, en vez de confirmar. Además: subir el TTL del lock de Redis para que cubra toda la
ventana de pago (`LOCK_TTL = 10 * 60` en `bookings.service.ts:16` frente a
`DEFAULT_PAYMENT_TTL_MINUTES = 30` en `booking.constants.ts:14`; el comentario de `booking.constants.ts:10`
reconoce que el checkout de Wompi "normalmente" tarda 2-10 min, así que entre el minuto 10 y el 30 los
últimos 20 min los cubre solo la fila de la base), y añadir las dos pruebas que faltan (pago después del
expiro; pago después de que la franja se re-reservó).

### H-02 — El despliegue invierte la escalera de J-04, y el contenedor no se puede construir (Alta)

**Evidencia.** En `kamerinos-infra/docker-compose.yml` (modificado, **sin commitear**), servicio `ia-bot`:

- `:136-137` → `LLM_READ_TIMEOUT: 30s` y `LLM_TURN_DEADLINE: 35s`: **exactamente** los valores que ADR 0014
  vino a corregir (IA `application.yml:91-97` define 10 s y 20 s).
- El backend sigue con su valor por defecto (25 s): `IA_BOT_TIMEOUT_MS` **no está** en el bloque `backend`
  del compose ni en el `.env`.
- → En producción la escalera queda 30 s (lectura) / 35 s (deadline) **>** 25 s (timeout del backend):
  vuelven el turno huérfano, los tokens gastados por una respuesta que nadie vio y el 504 del backend en
  vez del de IA. Es el hallazgo J-04, tal cual, reintroducido por configuración.
- `:103-105` → el servicio construye desde `../saaspa-IA` con `dockerfile: Dockerfile`, y
  **`saaspa-IA/Dockerfile` no existe** (`find` sin resultados): `docker compose build` falla.
- `:119-133` → el `ia-bot` interpola `${TURN_TOKEN_PUBLIC_KEY}`, `${INTERNAL_API_KEY}`,
  `${IA_BOT_API_KEY}`, `${LLM_API_KEY}`, `${TENANT_ID}`, `${TENANT_TIMEZONE}`… y **ninguna** de las
  variables del acople (ni `LLM_*`, ni `IA_BOT_TIMEOUT_MS`, ni `IA_COST_GUARD_*`) existe en
  `kamerinos-infra/.env` ni en `.env.example` (verificado variable por variable: 0 coincidencias; solo
  `IA_BOT_URL`). Compose las resolvería a cadena vacía → 401/500 en el primer turno.
- Lo **bien** hecho en ese mismo archivo: `:89` `IA_BOT_URL: http://ia-bot:8000` (ya no `localhost`),
  `:138-140` el `ia-bot` **no publica puertos** y vive solo en `kamerinos_net`; el `backend` recibe
  `TURN_TOKEN_*`, `TENANT_ID/TENANT_TIMEZONE`, `INTERNAL_API_KEY`, `IA_BOT_API_KEY` y `SENDGRID_API_KEY`.
- Contraste que agrava el hallazgo: el `.env.example` de **`saaspa-IA`** sí cita la escalera nueva
  (`LLM_READ_TIMEOUT=10s` en `:47`, `LLM_TURN_DEADLINE=20s` en `:52`) y los cuatro topes del guard
  (`:56-65`), que es lo que promete ADR 0014 punto 1; el archivo que quedó desalineado es el del
  **despliegue**, que es justamente el que decide los valores efectivos.

**Propuesta.** (1) Alinear el compose con ADR 0014: `LLM_READ_TIMEOUT: 10s`, `LLM_TURN_DEADLINE: 20s` en
`ia-bot` y `IA_BOT_TIMEOUT_MS: 25000` en `backend` — o mejor, **quitar los tres del compose** y dejar que
cada repo use su valor por defecto documentado, que es el que las pruebas anclan; (2) crear el
`Dockerfile` de `saaspa-IA` (el de `saaspa-backend` sirve de plantilla) y añadirlo al CI de construcción;
(3) completar `.env.example` con las variables del acople y añadir un chequeo de arranque que falle si
falta alguna; (4) que el anclaje de la escalera sea **cruzado** (un test en cada repo que lea el número
del otro, o los tres números en un único archivo de referencia), no dos copias a mano.

### H-03 — El aviso de handoff no tiene garantía de entrega ni reintento (Media-alta)

**Evidencia.** `chat.service.ts:391-403`: el fallo del correo se registra con `warn` y se continúa
("una mail que falla no debe romper el turno" — correcto), pero además: el estado guardado
(`prisma/schema.prisma:250-272`) **no tiene campo de "avisado"** (`handoffMessage`, `handoffAt`,
`handoffClosedAt`, y nada más), el latch (`chat.service.ts:122-129`) responde y sale **antes** de
`notifyHandoff`, así que un turno posterior **no reintenta**, y `email.service.ts:428-434` convierte en
un `log` silencioso el envío cuando falta `SENDGRID_API_KEY` o el destinatario. No hay bandeja ni listado
de handoffs pendientes (pospuesto con razón por J-02). Consecuencia: **si el primer correo falla, esa
conversación queda muerta para siempre** — el bot no contesta (`HANDOFF_ACTIVE_MESSAGE`), nadie recibe
aviso, y el único rastro es un `warn`.

**Propuesta.** Registrar el resultado de la notificación (un `handoffNotifiedAt`/`handoffNotifyError`
basta) y usarlo para (a) reintentar el aviso en el turno siguiente o en un barrido corto, y (b) exponer un
listado mínimo de "conversaciones derivadas sin cerrar" que el salón pueda consultar aunque todavía no
haya widget (`GET` de solo lectura para ADMIN/EMPLEADO). Medir el fallo (métrica/registro consultable) en
vez de dejarlo en el log.

### H-04 — El 429 nuevo llega al widget como 502, y el techo del tenant es una denegación para todas (Media)

**Evidencia.** BE `ia-bot.client.ts:104-107`: `400 → 400`, `501 → 501`, **cualquier otra respuesta → 502**
("El asistente no está disponible"). IA ahora devuelve **429** por tope de coste
(`ApiExceptionHandler.java:83-93`) y su propio contrato lo admite: `chat-api.openapi.yaml:60-66`
("el widget no vera el 429 hasta que se acuerde ese mapeo"). Además el tope de tenant de IA (240 turnos/h
por defecto, `application.yml:76-82`) es alcanzable por **una sola IP** en ~12 min (el `@Throttle` es
20/min: `chat.controller.ts:38`), y a partir de ahí **todas** las clientas reciben el mensaje de
"asistente no disponible" — un atacante puede apagar el chat del salón sin gastar apenas dinero. Y el tope
por conversación se reinicia rotando el `conversationId` (IA cuenta por `conversation_id`; BE acepta
cualquier cadena ≤64, `dto/web-chat-request.dto.ts`), así que el único techo efectivo es el del tenant.

**Propuesta.** Mapear 429 → 429 con su propio texto ("hay mucha demanda, intenta en un momento") en
`mapError`, y avisar/métricas cuando salta el tope **de tenant** (es una señal de abuso o de necesidad de
subir el número, no un fallo del asistente). Valorar un componente por IP o por sesión firmada en el
guard de IA, no solo por conversación.

### H-05 — `ia.turn_log` no contiene todos los turnos que cuestan dinero (Media)

**Evidencia.** ADR 0010 declara `ia.turn_log` como **fuente de verdad** del consumo, y el guard la agrega
en una sola consulta (`TurnCostGuard.java:32-39`). Pero solo se registran dos desenlaces:
`status = OK` (`ChatController.java:131`, el camino que incluye el handoff, ver §3) y `status = DEADLINE`
(`:123-125`). Un turno que llama al modelo y falla por otra causa (error del proveedor tras los reintentos,
o el `IllegalStateException` de `:186`) **no deja fila** → se gastaron tokens que el tope no cuenta. Es el
resto de **A-08**, que ADR 0014 dejó explícitamente abierto y que ADR 0010 convirtió en dependencia de
dinero. Efecto secundario: un cliente puede consumir presupuesto sin acercarse al límite si sus turnos
fallan después de llamar al modelo.

**Propuesta.** Registrar el turno en **todos** los desenlaces que invocan al modelo (`ERROR`,
proveedor, timeout…) con su estado, en la misma pasada que A-08 pide; y que el guard cuente por
`status <> 'HANDOFF'` o por una columna de "¿llamó al modelo?" en vez de por `count(*)` a secas.

### H-06 — El estado `EXPIRADA` no tiene consumidor, y un EXPIRADA se puede cancelar (Media-baja)

**Evidencia.** `saaspa-frontend` no conoce el estado: `components/dashboard/bookings-table.tsx:17-29` y
`client-booking-calendar.tsx:16-20` mapean etiqueta y badge de `PENDIENTE_PAGO`, `CONFIRMADA`,
`COMPLETADA`, `CANCELADA`, `NO_ASISTIO` — **no `EXPIRADA`** → el dashboard muestra el enum crudo; y el
botón de pago/pendiente está condicionado a `PENDIENTE_PAGO`, así que una cita expirada se ve sin
etiqueta y sin acción. En el backend, `POST /api/payments/init` responde 400 ("La cita no está pendiente
de pago") a una clienta que llegó tarde al checkout, sin mensaje específico. Y `cancel`
(`bookings.service.ts:227-236`) sí acepta una cita `EXPIRADA` (solo rechaza COMPLETADA/NO_ASISTIO), lo que
convierte "el sistema expiró" en "la clienta canceló" — justo la distinción que ADR 0011 punto 2 decidió
preservar.

**Propuesta.** Añadir la etiqueta/badge de `EXPIRADA` y un mensaje claro en el checkout ("tu reserva se
liberó por falta de pago, puedes tomarla otra vez"), y decidir si `cancel` debe rechazarla (o dejarla
como no-op informativo) para no reescribir el hecho.

---

## 3. Contradicciones entre lo implementado y lo que dice su propia ADR

| ADR | Dice | El código hace | Gravedad |
|---|---|---|---|
| **0014** (escalera) | Invariante `read-timeout (10 s) < turn-deadline (20 s) < IA_BOT_TIMEOUT_MS (25 s)`, "y **nunca** al revés" | En los dos repos se cumple (con test en BE); **el compose de infra lo invierte** (`ia-bot` 30 s/35 s, backend sin `IA_BOT_TIMEOUT_MS`) | Alta (H-02). El código no contradice a la ADR: la contradice **el despliegue**, que es lo que se ejecuta |
| **0013** (handoff) punto 3 | "El turno derivado **se registra en `ia.turn_log` con estado y motivo**" | Deja fila, pero con `status = OK`, sin motivo y con tokens a cero (`ChatController.java:101-109` + `:131`; `TurnLogService.Status` solo tiene OK y DEADLINE) | Media-baja: desde `ia.turn_log` no se distingue un turno derivado; y ese turno **sí** cuenta para los topes por turno del guard aunque el guard nunca se consulte en esa rama |
| **0010** (coste) | `ia.turn_log` es la fuente de verdad del consumo | Registra solo `OK`/`DEADLINE`; los fallos posteriores a la llamada al modelo no dejan fila | Media (H-05), y engancha con A-08 |
| **0011** punto 3 | "Un pago tardío queda `APROBADO` y necesita decisión manual" | El desenlace es ese, pero **sin mecanismo**: sin aviso, sin listado, sin vínculo con la cita, sin recibo (el envío va después del `throw`), y el reintento de Wompi se ignora por "duplicado". Además la ADR no contempla el caso de la doble confirmación | Alta (H-01) |
| **0012** (identidad/idempotencia) | El sujeto sale del turn token; sin identidad, 403; `Idempotency-Key` de extremo a extremo | Mecanismo y prueba listos (`turn-identity.ts`, la prueba arquitectónica), `Idempotency-Key` real en el endpoint público equivalente; el endpoint interno de Fase 2 todavía no existe | Coherente. No hay contradicción; sí un "todavía no" |
| **0009/0014** retry | 2 intentos, `408/429` reintentables, el resto de 4xx no | Cumple tal cual (`application.yml:26-36`) | — |

---

## 4. Estado de los otros hallazgos (mención, sin reevaluar)

- **J-01 (despliegue):** **sigue abierto y ahora con más detalle** (H-02): el `ia-bot` existe en el árbol
  de trabajo de infra, pero falta el `Dockerfile` de IA, faltan las variables en `.env`/`.env.example` y
  los números contradicen ADR 0014. No cambia de naturaleza: bloquea el piloto, no el desarrollo.
- **J-02 (widget):** sin cambios. El frontend sigue sin cliente de chat (`*chat*` → 0 archivos), y por eso
  ADR 0013 pospone la bandeja. Sigue bloqueando el piloto.
- **J-06 (tenant/zona horaria):** sin cambios; sigue siendo advertencia manual por despliegue. Se agrava
  un poco con H-02 (ahora hay un segundo punto donde fijar la zona: `TZ` del `ia-bot` y
  `IA_TENANT_TIMEZONE`, ambos ausentes del `.env`).
- **J-07 (contrato de error):** **sin cambios y ahora más relevante**: sigue vigente `mapError` con
  400/501 y todo lo demás a 502, y el `detail` de IA se convierte en el texto que ve la clienta. La
  diferencia es que antes el 429 de IA no existía y ahora es alcanzable → H-04.
- **J-10 (id de conversación y retención):** sin cambios. Añado que el `conversationId` sigue siendo
  entrada del cliente (≤64 caracteres libres) y que ahora es *también* la clave del tope de coste de IA.
- **J-11 (dos secretos, una cabecera):** sin cambios (`X-Internal-Api-Key` sigue nombrando secretos
  distintos en cada dirección).
- **J-12 (`turnId` de vuelta):** sin cambios; el backend sigue ignorando el `turnId` que IA devuelve.
- **J-13 (casos de conformidad entre repos):** sin cambios de fondo, con un ejemplo nuevo y concreto: el
  test que fija la escalera en BE lleva **los números de IA escritos a mano**
  (`timeout-ladder.spec.ts:16-17`), así que detecta una regresión de BE pero no un cambio en IA.

---

## 5. Recomendación

Los cuatro bloqueantes de la ola 1 y J-04 **están materialmente cerrados**: la identidad ya no puede salir
del cuerpo sin romper la suite, la idempotencia es atómica de verdad, el abuso tiene dos capas honestas, la
franja ya no se puede bloquear gratis y el handoff tiene destino, texto y reversión. **Se puede empezar la
Fase 2** (diseño, contrato interno y `misCitas`) con dos condiciones:

1. **Antes de que exista la primera herramienta de escritura**, cerrar **H-01**: la ventana de pago y la
   confirmación son exactamente el camino que alimentará `crearCita`, y hoy tiene dos desenlaces que
   pierden dinero o duplican una franja — con el agravante de que **ya está vivo** para las citas que se
   crean a mano.
2. **Antes de desplegar cualquier cosa**, cerrar **H-02**: tal como está el compose, el contenedor no se
   construye y, si se construyera sin tocar los números, reintroduciría el turno huérfano de J-04.

H-03 y H-04 conviene resolverlos en la misma pasada (son de "no dejar un silencio" y de "que el 429 no
mienta"); H-05 depende de la decisión de A-08 y H-06 es el consumidor del enum nuevo, que conviene atar
cuando el frontend toque el chat.

---

## 6. Límites de esta revisión

- **No ejecuté ninguna suite** en ninguno de los repos (habrían escrito en `target/`, `dist/`,
  `test-results/`): los recuentos de tests son los que declaran los repos. La evidencia de este informe es
  lectura de código, de migraciones, de contratos y de las pruebas *como texto*.
- No leí los **valores** de `kamerinos-infra/.env` (fichero de secretos); sí comprobé, variable por
  variable, la **presencia** de nombres, y las líneas con secretos salen redactadas en mi lectura.
- No verifiqué el **runtime** de nada (que un turno real vuelva a funcionar, que el correo llegue, que
  Wompi reintente como digo). Del hallazgo H-01 sí verifiqué el **mecanismo en el código**, no solo su
  forma: que no hay `try/catch` alrededor de `confirmAndSync`, que el controlador devuelve la promesa sin
  filtrar, que el atajo de "webhook duplicado" precede a cualquier reintento, que `cancel` sí acepta una
  cita `EXPIRADA` y que `LOCK_TTL` (10 min) es menor que la ventana de pago (30 min). Lo que **no** está
  verificado es la reproducción: recomiendo escribir las dos pruebas propuestas (pago después del expiro;
  pago después de que la franja se re-reservó) **antes** de tocar el flujo, porque son las que fijarán cuál
  de los dos desenlaces ocurre de verdad.
- El árbol de `saaspa-IA` está en la rama `feature/f2-per-tenant-cost-guard` cuyo contenido es
  exactamente el que se fusionó como `origin/develop@19d8e6c` (comprobado), así que lo leído es lo
  mergeado; el de `kamerinos-infra` es **trabajo sin commitear** y, por tanto, puede cambiar sin dejar
  rastro en el historial.
