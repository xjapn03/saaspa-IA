# Revisión conjunta de integración — saaspa-backend ↔ saaspa-IA

- **Autor:** revisión externa (Hermes) a petición de la persona.
- **Fecha:** 2026-09-26.
- **Ámbito:** los **dos** repositorios juntos, más el despliegue como contexto.
  - `saaspa-backend` — rama `develop`, commit `9fc8b12` (árbol limpio; es exactamente el commit contra el
    que se reconciliaron los contratos).
  - `saaspa-IA` — rama `develop`, commit `e4fe8a2` (PR #22 fusionado: cierre de Fase 1).
  - `kamerinos-infra` — `docker-compose.yml`, `nginx/kamerinos.conf`, `scripts/crontab.txt`, `deploy.sh`
    (solo lectura, como contexto de despliegue).
  - `saaspa-frontend` — lectura puntual de `src/lib/api.ts` y `src/types/auth.ts` para responder a la
    pregunta 2. No se tocó nada.
- **Naturaleza:** informe de trabajo; no es una ADR. Cada punto que se acepte debe convertirse en ADR
  (R16) o en tarea del checklist antes de implementarse. Nada de lo propuesto aquí está implementado.
- **Modo:** lectura. No se creó ni modificó ningún archivo salvo **este**; no hubo `git commit/push/checkout`,
  ni `gh` de escritura, ni builds (habrían escrito en `target/` y `dist/`).
- **Relación con los informes previos:** `docs/reviews/2026-09-25-hermes-architecture-review.md` (IA) y los
  informes de contrato (`docs/contracts/f1-e2e-validation.md`, `t1.0-backend-validation.md`) cubren cada
  repo por separado. Aquí **no se repiten** sus hallazgos salvo cuando el problema real aparece solo al
  mirar los dos lados juntos.

---

## 0. Veredicto en una línea

**El código de los dos repos está alineado y la integración de Fase 1 es correcta, pero el *sistema*
todavía no puede ejecutar un solo turno en el despliegue, no tiene interfaz que lo consuma, su control de
abuso es evitable con una cabecera y la derivación a una persona no la recibe nadie.** Fase 2 (escritura de
citas) puede abrirse **en diseño y contrato**, pero ninguna herramienta de escritura debería fusionarse
antes de cerrar los cuatro puntos del §9.

---

## 1. Hallazgos priorizados

| ID | Sev. | Tema | ¿Bloquea la escritura? | Evidencia clave |
|---|---|---|---|---|
| J-01 | **Alta** | El despliegue no puede servir un turno: no existe `ia-bot` y al `backend` le faltan `TENANT_ID`, `TURN_TOKEN_PRIVATE_KEY`, `INTERNAL_API_KEY` e `IA_BOT_API_KEY`; `IA_BOT_URL` apunta por defecto al propio contenedor | Sí (operativo) | `infra/docker-compose.yml:40-91`; `turn-token.service.ts:46-49`; `internal-auth.guard.ts:48-52`; `ia-bot.client.ts:56-58` |
| J-02 | **Alta** | El «chat web» no tiene cliente: el frontend no implementa widget ni llama a `/api/chat`; el criterio E2E se validó con peticiones HTTP | Sí (producto) | grep `-i chat` en `saaspa-frontend/src` → 0 archivos; `docs/contracts/web-chat-api.openapi.yaml:1-26` |
| J-03 | **Alta** | El único control de abuso (20 req/min por IP + tope por sesión) es evitable: `trust proxy: true` con `X-Forwarded-For` reenviado por nginx deja el valor que envía el cliente; IA no tiene ningún límite | Sí (coste y agenda) | `nginx/kamerinos.conf:56,111`; `src/main.ts:28`; `chat.controller.ts:25`; `chat.service.ts:104,197-201`; en IA, 0 coincidencias de `Throttl/RateLimit` |
| J-04 | **Alta** | Escalera de timeouts invertida: BE corta a 20 s, IA sigue hasta 35 s y hasta ~61 s de peor caso → trabajo huérfano, tokens gastados y turno fantasma en memoria | Sí (reintentos de escritura) | `chat.constants.ts:28`; `application.yml:69-74`; `ChatController.java:134-158`; `chat.service.ts:121-150` |
| J-05 | **Media-alta** | El handoff no lo recibe nadie y es irreversible: texto canónico que promete un asesor, latch permanente por conversación, sin notificación ni vista, y la conversación no es legible por ninguna persona | Sí (una reserva perdida) | `HandoffPolicy.java:69-76`; `chat.service.ts:94-101`; `chat.constants.ts:21-22`; `whatsapp.service.ts:90-98` |
| J-06 | **Media** | La zona horaria y el tenant «deben coincidir» a mano: sin comprobación en runtime, y `now`/`timezone`/`locale` del cuerpo no los usa nadie (campos muertos) | Sí (horas de cita) | `application.yml:57-63`; `CustomerAgent.java:93-97`; `TurnContextValidator.java:25-51`; `bookings.service.ts:70-74`; `timezone.util.ts:10-29` |
| J-07 | **Media** | El contrato de error de los dos lados no es el que está implementado, y el texto interno de IA acaba en la cara de la clienta | Sí (diagnóstico) | `web-chat-api…yaml:51-55`; `internal-api…yaml:234-245`; `main.ts:50-59`; `ia-bot.client.ts:104-108`; `frontend/src/types/auth.ts:44-48` |
| J-08 | **Media** | La identidad del turn token **no la lee ningún endpoint**: la primera vez que se use será en la escritura de Fase 2, sin regla ni test que impida tomarla del cuerpo | Sí | `turn-context.decorator.ts:9-14` (único uso: su propio comentario); `internal-auth.guard.ts:30-43` |
| J-09 | **Baja-media** | El turn token es reutilizable durante todo su TTL (`jti` no se consume) y sirve para toda la superficie interna, sin distinción lectura/escritura | Sí (mitigable) | `turn-token.service.ts:89-100`; `internal-auth.guard.ts:30-43` |
| J-10 | **Baja-media** | El vínculo conversación↔sesión es perezoso y el `conversationId` es una cadena libre del cliente → filas y memorias ilimitadas en las dos bases, sin poda | No, pero conviene antes | `chat.service.ts:86,90,220-232,197-201`; `web-chat-request.dto.ts:24-35`; `crontab.txt:4-5` |
| J-11 | **Baja** | `X-Internal-Api-Key` nombra **dos secretos distintos** según la dirección; un cruce de variables no es diagnosticable | No | `ia-bot.client.ts:73-76`; `internal-auth.guard.ts:46-52`; `ServiceKeyVerifier`/`BackendClient` (IA) |
| J-12 | **Baja** | El backend ignora el `turnId` que IA devuelve: nada detecta una respuesta cruzada o vieja | No | `ia-bot.client.ts:36-42`; `chat.service.ts:157-170` |
| J-13 | **Baja** | No hay ninguna prueba de conformidad **entre** los repos: cada lado prueba su expectativa | No, pero es barato | `internal-*.spec.ts` (BE) y WireMock (IA) no se cruzan |

---

## 2. Pregunta 1 — ¿los contratos que cada repo asume del otro coinciden con lo implementado?

**En la forma de los datos de Fase 1, sí.** Verificado campo a campo, no solo contra el documento:

- `price` es `Decimal(10,2)` en Prisma (`prisma/schema.prisma:91`) pero el repositorio lo convierte a número
  antes de serializar (`services.repository.ts:14-18`), así que el `price: number` del contrato
  (`internal-api.openapi.yaml:260`) es cierto. Si alguien quita ese `toSafe`, IA seguiría funcionando por
  coerción de Jackson (string → Double) y el contrato se volvería mentira **en silencio**: es exactamente el
  tipo de detalle que hoy no vigila ninguna prueba.
- `category` es un objeto con `{id,name,slug}` en ambos lados (`services.repository.ts:11`,
  `internal-services.controller.ts:64`, `ServiceDto.java:32`); `featured` se valida como texto
  (`list-internal-services-query.dto.ts:23-25`) y IA lo envía como booleano que se serializa a `true`
  (`CustomerTools.java:99-101`): compatible.
- Disponibilidad: instantes con offset explícito calculado con ICU (`timezone.util.ts:10-29`) + `timezone`,
  y IA los deserializa como `OffsetDateTime` (`AvailabilitySlotDto.java:11`). Correcto.
- El 404 que IA espera para un servicio inexistente **sí existe**: `findById`/`findBySlug` lanzan
  `NotFoundException` (`services.repository.ts:51-59`), así que la rama específica de
  `CustomerTools.java:140-142` se puede alcanzar.

**Donde no coinciden:**

1. **El esquema de error, en los tres contratos** (J-07). `web-chat-api.openapi.yaml:51-55` y
   `internal-api.openapi.yaml:222-245` declaran `Problem` (RFC 9457) para los errores, y ambos contratos
   admiten en la descripción que el backend responde `{statusCode,message,error}`. La implementación confirma
   la descripción y desmiente el esquema: no hay filtro global de excepciones (`main.ts:50-59`, solo
   `MulterExceptionFilter`). El único consumidor real, el frontend, está tipado contra la implementación
   (`src/types/auth.ts:44-48`), así que nadie se rompe **hoy**; un cliente generado desde el contrato sí.
2. **El contrato documenta caminos inalcanzables** (J-04): `chat-api.openapi.yaml:14-15` dice que el backend
   mapea «timeout → 504» y que «cualquier otra respuesta (incluidos nuestros 401/403/502) → 502». Como IA
   solo emite 504 después de 35 s y BE aborta a los 20 s (`chat.constants.ts:28`), ese mapeo no se ejecuta
   nunca con el modelo lento: el 504 que ve la clienta es el de NestJS por su propio abort. El código no
   falla, pero la descripción del comportamiento es falsa y da falsa confianza al depurar.
3. **Campos muertos en `chat-api`** (J-06): `locale`, `timezone` y `now` son obligatorios en el contrato
   (`chat-api.openapi.yaml:111`) y se documentan como «contexto informativo para el LLM». IA no los usa para
   nada: `TurnContextValidator` solo contrasta `turnId`, `tenantId`, `conversationId`, `channel`, `agent` e
   `identity` (`TurnContextValidator.java:25-51`) y el agente construye la fecha con su propio reloj y la zona
   de su configuración (`CustomerAgent.java:93-97`). No llegan al prompt.
4. **No hay ninguna prueba de conformidad cruzada** (J-13): las de IA fijan *la expectativa de IA* (WireMock)
   y las de BE fijan los controladores de BE. Un cambio de forma en cualquiera de los dos lados no rompe
   nada hasta producción.

---

## 3. Pregunta 2 — ¿es consistente el manejo de errores end-to-end?

**No del todo: la cadena es de un solo sentido y pierde información.**

- **Colapso de códigos.** `ia-bot.client.ts:104-108` solo distingue 400 y 501; todo lo demás (403 de tenant,
  401 de clave de servicio, 500, 502 y el 504 de IA) se convierte en **502 Bad Gateway** para el navegador.
  Un `TENANT_ID` mal configurado —que rompe *todos* los turnos— se ve como «el asistente no está disponible».
- **Fuga de texto interno a la persona.** `readProblemDetail` prefiere `detail` y `title`
  (`ia-bot.client.ts:111-118`) y ese texto se usa tal cual como mensaje (`:105-107`). Como IA escribe
  `detail` en prosa y `title` en corto (`ApiExceptionHandler.java:69-92`, `problemDetailSecurityHandler`),
  una clienta puede leer «Tenant no permitido», «No autorizado» o «El modelo no respondio a tiempo»: mensajes
  internos en la cara del usuario, sin PII pero sí ruido y pistas de configuración.
- **El camino de error de IA no deja rastro.** `ChatController.java:116-120` registra en `ia.turn_log` solo
  después de una respuesta correcta; si hay handoff se registra, pero si salta el deadline (504) o falla el
  modelo, la excepción sale antes y **no se escribe ninguna fila** (`turn_log` no tiene columna de estado).
  BE sí deja una línea con el `turnId` (`chat.service.ts:153-155`), pero del lado de IA no hay nada con qué
  correlacionarla, y el `ProblemDetail` de IA no devuelve el `turnId`. Depurar un 504 real exige hoy adivinar.
- **El reloj del turno no está en un solo sitio** (J-04): como BE corta antes de que IA termine, IA sigue
  trabajando después de haber respondido al cliente. Con `future.cancel(true)` (`ChatController.java:141`)
  la cancelación es real, pero interrumpir un hilo no garantiza que el cliente HTTP corte la lectura; la
  consecuencia observable es que el turno puede terminar en segundo plano, gastar tokens y **dejar en la
  memoria conversacional una respuesta que la clienta nunca vio**, que el turno siguiente sí leerá. Es no
  determinista y merece una prueba, no una suposición (R17).
- **Lo que sí está bien:** el mapeo de 400→400 y 501→501; `BackendException`/`BackendUnavailableException`
  con timeouts explícitos (`application.yml:75-80`); los `ProblemDetail` de IA son consistentes con su propio
  contrato; y el frontend maneja `{statusCode,message}` sin romperse.

---

## 4. Pregunta 3 — supuestos que un repo da por hechos y que nadie garantiza

1. **«La red interna es de confianza».** El turn token y las **dos** claves de servicio viajan en claro por
   HTTP entre contenedores (`ia-bot.client.ts:71-80`, `BackendClient.java:84-92`; en infra el `ia-bot` no
   existe todavía). El `INTERNAL_API_KEY` es de larga vida y viaja en cada llamada interna. Además, en la
   misma red `kamerinos_net` están `dozzle` y `portainer` con el socket de Docker montado
   (`docker-compose.yml:147-170`): comprometer cualquiera de esos dos equivale a root en el host y a poder
   leer ese tráfico. *Propuesta:* el `ia-bot` sin `ports:` y solo en `kamerinos_net` (documentado en el
   compose), valorar mTLS o al menos HMAC sobre la petición interna, y sacar dozzle/portainer de esa red.
2. **«El otro lado limita el abuso».** IA no tiene ningún limitador (0 coincidencias de
   `Throttl|RateLimit|Bucket|resilience` en `src/main`); BE sí, pero por IP con un valor que el cliente
   puede escribir (J-03) y por cookie elegida por el cliente (`chat.service.ts:197-201` acepta cualquier
   valor de ≥16 caracteres). Resultado: el gasto de LLM del piloto queda acotado solo por el ancho de banda
   del atacante. *Propuesta:* `trust proxy` numérico (o que nginx sobrescriba `X-Forwarded-For`), un tope
   global por tenant en IA, y el tope por sesión atado a un identificador que emita el servidor.
3. **«Alguien atiende el handoff».** IA promete «Te paso con una persona del equipo»
   (`HandoffPolicy.java:71-74`), BE responde «Un asesor te responderá muy pronto»
   (`chat.constants.ts:21-22`) y **nadie recibe nada**: `handoffActive` se escribe y se lee solo en
   `chat.service.ts` (no hay notificación, ticket, webhook ni vista de administración). Encima, WhatsApp
   tiene su *propio* estado de handoff en otra tabla (`whatsapp.service.ts:90-98`, `ConversationState`), así
   que el sistema tiene dos mecanismos de derivación y ninguno tiene consumidor.
4. **«La zona horaria es la misma en los dos lados»** (J-06). Es un acople a mano documentado en
   `AGENTS.md` §11.5 y no comprobado en runtime. El caso concreto: IA valida la fecha en la zona de su
   configuración (`CustomerTools.java:207-209`), BE construye las franjas del día con
   `new Date(...)`/`setHours` —es decir, con la zona **del contenedor** (`bookings.service.ts:70-74`)— y las
   etiqueta con el offset calculado por ICU para `TENANT_TIMEZONE` (`timezone.util.ts:10-29`). Con
   `TZ=America/Bogota` en el compose (`docker-compose.yml:57`) coinciden; sin `TZ` en el contenedor, las
   horas serían otras pero seguirían etiquetadas como `-05:00`: el error se ve *correcto*.
5. **«El tenant está bien configurado en los dos lados».** IA falla cerrada y responde 403
   (`ChatController.java:80-82`); el guard de BE también compara, pero **falla abierto si `TENANT_ID` no
   está definido** (`internal-auth.guard.ts:36-39`: `if (tenantId && ...)`), y el emisor de tokens exige
   `TENANT_ID` (`turn-token.service.ts:46-49`). La asimetría no es explotable hoy, pero sí es una trampa de
   futuro.
6. **«El frontend existe y consume el contrato».** No existe widget (J-02). El contrato `web-chat-api` y su
   tratamiento de errores no los ha ejercitado ningún cliente real.

---

## 5. Pregunta 4 — qué reforzar ANTES de la escritura y qué puede esperar

**Antes (y por qué):**

| Prioridad | Qué | Por qué no puede esperar |
|---|---|---|
| 1 | **Expiro de las citas en `PENDIENTE_PAGO`** + tope de reservas por usuario | `findOccupied` cuenta como ocupada toda cita que no esté `CANCELADA`/`NO_ASISTIO` (`bookings.repository.ts:107-120`) y no hay ningún trabajo que expire una cita sin pagar (`crontab.txt` solo hace backups). Hoy hay que crear la cita a mano; con la herramienta de escritura se automatiza: una conversación puede bloquear franjas gratis. |
| 2 | **Idempotencia de la confirmación** (`Idempotency-Key` atada a `jti` + operación) | El turno tiene `jti = turnId` (`turn-token.service.ts:67`) y `POST /bookings` no acepta hoy `Idempotency-Key` (`internal-api.openapi.yaml:114-116`). Sin esto, el reintento del widget tras un timeout (J-04) es una segunda llamada al modelo y otra cita. |
| 3 | **La identidad siempre del token**, con regla y prueba | Ningún endpoint interno lee hoy `turn.userId`/`turn.role` (`@TurnContext` solo aparece en su propio comentario). El primer endpoint que lo necesite es justo el de escritura: hay que fijar «`misCitas` = `turn.userId`, nunca un parámetro» antes, no después. |
| 4 | **Notificación y reversibilidad del handoff** (J-05) | Con escritura, un falso positivo del detector de salud (`HandoffPolicy` hace `contains` sobre listas de subcadenas, p. ej. «medicament») deja a la clienta con un mensaje canónico **permanente** y sin poder reservar por chat. Hoy es una molestia; con Fase 2 es una reserva perdida. |
| 5 | **Escalera de timeouts y correlación** (J-04) + estado de los turnos fallidos en `turn_log` | El primer caso de uso con dinero exige poder reconstruir qué pasó con un turno que devolvió error. |
| 6 | **Control de abuso no evitable** (J-03) | Se justifica por sí solo al añadir escritura. |

**Puede esperar con seguridad:** el agente ADMIN y los reportes (Fase 3); el RAG (Fase 4); WhatsApp real y
la resolución por `waId`; la segunda ranura de `kid` en BE (la rotación hoy solo exige coordinar un cambio de
variable: los tokens en vuelo duran ≤300 s y el backend firma y verifica con su propia clave,
`turn-token.service.ts:71-100`); métricas/trazas (A-15 del informe de IA); y el endurecimiento del dataset de
evaluación. La poda de `chat_conversation_states` y de la memoria de IA (J-10) es barata: conviene meterla
con el trabajo de despliegue, no dejarla para Fase 5.

---

## 6. Detalle de los hallazgos

### J-01 — El despliegue no puede servir un turno (Alta)

**Evidencia.**
- `kamerinos-infra/docker-compose.yml:40-91`: el servicio `backend` declara `environment:` con ~30 variables
  y **no tiene `env_file:`**. Solo aparecen `TZ: America/Bogota` (`:57`), `REDIS_URL` (`:61`) e
  `IA_BOT_URL: ${IA_BOT_URL:-http://localhost:8000}` (`:89`). No están `TENANT_ID`, `TENANT_TIMEZONE`,
  `TURN_TOKEN_PRIVATE_KEY`, `TURN_TOKEN_KID`, `TURN_TOKEN_ISSUER`, `TURN_TOKEN_AUDIENCE`,
  `TURN_TOKEN_TTL_SECONDS`, `INTERNAL_API_KEY` ni `IA_BOT_API_KEY`.
- El `Dockerfile` de BE no define ninguna de esas variables (`Dockerfile:1-36`).
- Consecuencia en cadena, con el código de BE: `ChatService.handle` llama a `TurnTokenService.issue`
  (`chat.service.ts:112-119`), que **lanza** si falta `TENANT_ID` (`turn-token.service.ts:46-49`) y si falta
  la clave privada (`:123-126`) → 500 en cada turno. Aun con el token emitido, `IA_BOT_URL` apunta a
  `localhost:8000` **dentro del propio contenedor del backend** (que escucha en 3001): `fetch` da
  `ECONNREFUSED` → `BadGatewayException` (`ia-bot.client.ts:97-98`). Y `X-Internal-Api-Key` iría vacío
  (`:75`), que IA rechaza en cerrado (`ServiceKeyAuthenticationFilter.java:31-36`).
- No hay servicio `ia-bot` en el compose, coherente con `AGENTS.md` §11.5 («pendiente en kamerinos-infra»).
- `deploy.sh:30-35` solo **avisa** si el health check falla; no aborta el despliegue.

**Por qué importa.** El criterio E2E de Fase 1 se cumplió con el backend y el agente corriendo en el
host/equipo de desarrollo, no en esta topología. Tal como está el compose, en el VPS el chat devuelve error
en el primer mensaje, sin que nada lo detecte antes de la primera clienta. Además, el arranque no falla:
el síntoma aparece por turno, no al desplegar.

**Propuesta.** Añadir el servicio `ia-bot` (misma red, sin `ports:`), y en `backend` las variables de 11.4 de
`AGENTS.md` **sin valores por defecto** (`${TURN_TOKEN_PRIVATE_KEY}` y no `${TURN_TOKEN_PRIVATE_KEY:-}`);
`IA_BOT_URL: http://ia-bot:8000`. En BE, validar la configuración al arrancar (esquema de `ConfigModule`) de
modo que un despliegue sin `TENANT_ID`, `TURN_TOKEN_PRIVATE_KEY`, `INTERNAL_API_KEY` o `IA_BOT_API_KEY`;
falle en el arranque y no en el primer turno. Y que el health check del despliegue sea obligatorio, no un
aviso.

### J-02 — El «chat web» no tiene interfaz (Alta)

**Evidencia.** `find src -iname "*chat*"` en `saaspa-frontend` → solo `payment-widget.tsx` y
`payment-widget-script.tsx`; `grep -rn "api/chat"` en `src/**/*.ts{,x}` → 0 coincidencias; `grep -rln -i chat`
→ 0 archivos. El contrato del canal (`web-chat-api.openapi.yaml`) describe un endpoint sin cliente.

**Por qué importa.** El criterio de aceptación de Fase 1 («una consulta por chat web anónimo devuelve el
precio real») se verificó a nivel HTTP. Eso es válido como prueba de integración, pero significa que: (a) el
tratamiento de errores de cara a la clienta no lo ha visto nadie; (b) el trabajo del widget está entero por
hacer y es un tercer repo con su propio ciclo; (c) Fase 2 pide *confirmación explícita* de la clienta en un
diálogo (`R9`, ADR 0008) que hoy no tiene superficie donde ocurrir. Si el piloto es «una clienta usa el
chat», el piloto no está listo aunque Fase 2 lo esté.

**Propuesta.** Decidir explícitamente dónde vive el widget y en qué orden: (1) widget mínimo anónimo
(mensaje, `conversationId` persistido, errores con texto propio, 413/429/502/504 diferenciados), o (2) se
declara Fase 1 «validada por API» y el widget se planifica como tarea de la Fase 2 antes de cualquier
herramienta de escritura. En cualquiera de los dos casos, la respuesta de `handoff` debe tener una interfaz
(«un asesor te escribirá») y la de error un texto propio, no el `detail` de IA (J-07).

### J-03 — El control de abuso se evade con una cabecera (Alta)

**Evidencia.**
- `saaspa-backend/src/main.ts:28`: `app.set('trust proxy', true)`. Con `true`, Express toma como `req.ip` el
  **primer** valor de `X-Forwarded-For`, que es el que puede escribir el cliente.
- `kamerinos-infra/nginx/kamerinos.conf:56,111` (y `:138,168`): `proxy_set_header X-Forwarded-For
  $proxy_add_x_forwarded_for`, que **añade** la IP real al final de la lista del cliente; no la sustituye.
  Nginx sí conoce la IP real vía Cloudflare (`:16-33`, `real_ip_header CF-Connecting-IP` + `set_real_ip_from`
  de los rangos de Cloudflare) y la pone en `X-Real-IP`, que Express no usa para `req.ip`.
- El límite es `@Throttle({ default: { limit: 20, ttl: 60000 } })` (`chat.controller.ts:25`) aplicado por el
  `ThrottlerGuard` global (`app.module.ts:43,69`).
- El otro tope, «30 mensajes por hora por sesión anónima» (`chat.service.ts:103-109`,
  `chat.constants.ts:7,14`), se apoya en `sessionKeyHash` derivado de la cookie
  `kamerinos_chat_session`, y `resolveIdentity` acepta **cualquier** valor de esa cookie con longitud ≥16
  (`chat.service.ts:197-201`): rotarla reinicia el contador.
- IA no limita nada: 0 coincidencias de `Throttl|RateLimit|Bucket|resilience|CircuitBreaker` en `src/main`.

**Por qué importa.** Cada turno es una llamada a un LLM de pago y (desde Fase 2) un posible cambio de
estado. Sin control efectivo, el coste y la agenda quedan a merced de cualquiera que sepa añadir una
cabecera. Además, el umbral de 20/min por IP es frágil en el caso legítimo del salón: el personal y las
clientas comparten la IP del wifi del local.

**Propuesta.** (1) `app.set('trust proxy', 1)` (o que nginx envíe `X-Forwarded-For $remote_addr`
sobrescribiendo) y una prueba que verifique que un `X-Forwarded-For` inyectado no cambia el bucket;
(2) tope global por tenant y coste por conversación en IA (ya propuesto como A-06 en el informe de IA);
(3) que el tope de sesión use un identificador emitido por el servidor, no la cookie cruda del cliente;
(4) y, con escritura, un tope por cuenta para crear citas.

### J-04 — Escalera de timeouts invertida y turno fantasma (Alta)

**Evidencia.**
- BE: `DEFAULT_IA_BOT_TIMEOUT_MS = 20000` (`chat.constants.ts:28`) y `AbortController` a ese plazo
  (`ia-bot.client.ts:67-68,93-96`).
- IA: `read-timeout: 30s`, `turn-deadline: 35s` (`application.yml:69-74`) y `max-attempts: 2` con backoff
  (`:26-36`), así que el peor caso de una llamada es ≈ 30 s + backoff + 30 s ≈ 61 s.
- IA corta por deadline con `future.get(deadline)` + `future.cancel(true)` (`ChatController.java:134-158`).
- Ninguna prueba ni comprobación de arranque compara los dos números; cada uno vive en su configuración y en
  su `.env.example`.

**Por qué importa.** El orden lógico debería ser `timeout de BE > deadline de IA > peor caso de un intento`.
Hoy es al revés: BE responde 504 a los 20 s y IA sigue hasta 35 s (y su reintento hasta ~61 s), gastando
tokens por un turno que ya nadie espera, pudiendo llamar a la API interna después de la respuesta, y
**pudiendo dejar en `ia.spring_ai_chat_memory` una respuesta que la clienta nunca vio** —que el turno
siguiente sí leerá como contexto—. La reacción natural de la clienta (reenviar) crea un turno nuevo sobre
esa memoria contaminada. Con escritura, el mismo patrón es una cita duplicada.

**Propuesta.** (1) Fijar la escalera: IA `read-timeout` 8-10 s, `turn-deadline` 20 s, y BE 25 s; dejar los
números en una nota compartida de `docs/contracts/` y citarlos en las dos configuraciones.
(2) Verificar con prueba que la cancelación corta la lectura HTTP y que un turno abandonado no escribe en la
memoria (o decidir qué se hace si escribe).
(3) Que IA devuelva el `turnId` en su `ProblemDetail` y registre el turno fallido en `turn_log` con estado
(hoy no deja fila: `ChatController.java:116-120`).

### J-05 — El handoff no lo recibe nadie y es irreversible (Media-alta)

**Evidencia.**
- IA decide por subcadenas sobre el mensaje (`HandoffPolicy.java:21-58`), no llama al modelo y devuelve texto
  canónico que promete una persona (`:69-76`).
- BE, al ver `handoff.requested`, escribe `handoffActive: true` + `handoffReason` en
  `chat_conversation_states` (`chat.service.ts:140-150,245-282`) y desde entonces responde siempre el mismo
  texto, sin llamar a IA (`chat.service.ts:94-101`, `chat.constants.ts:21-22`).
- Búsqueda en todo `saaspa-backend/src` de `handoff`: solo el repositorio de estado y `chat.service`. No hay
  notificación, correo, webhook, tabla de tickets ni endpoint de administración; y **ningún campo del estado
  permite desactivarlo**.
- WhatsApp ya tiene otro estado de handoff distinto (`whatsapp.service.ts:88-99`, `ConversationState`).
- La conversación no es legible por nadie: BE guarda solo contadores y el último `turnId`
  (`chat-conversation-state.repository.ts`), e IA no guarda los turnos con handoff (no llama al modelo) y su
  memoria no tiene API de lectura.

**Por qué importa.** Es una promesa al cliente que el sistema no puede cumplir, y en el peor momento: el
detector es un `contains` con falsos positivos conocidos («¿venden medicamentos?», «¿atienden personas con
diabetes?»). Con el latch permanente, una pregunta de catálogo puede dejar a esa clienta sin chat para
siempre, y una persona del salón que quisiera retomarlo no tiene ni el hilo de la conversación. Con escritura,
esto es una reserva perdida y una reclamación sin contexto.

**Propuesta.** Antes de Fase 2: (1) decidir el destino real del handoff (aviso al salón por el canal que ya
usan —WhatsApp/correo del panel—, o una bandeja en el chat del dashboard) y quién lo cierra; (2) hacer el
latch reversible (caducidad o reapertura manual) y registrar motivo + `turnId` para poder auditar;
(3) persistir el turno derivado en algún sitio legible (hoy ni IA ni BE guardan el texto);
(4) unificar o documentar la relación con el handoff de WhatsApp antes de Fase 4.

### J-06 — Zona horaria y tenant acoplados a mano (Media)

**Evidencia.** IA: `saaspa.tenant.timezone` (`application.yml:63`) es la autoridad (R13) y el agente calcula
«hoy» con su reloj y esa zona (`CustomerAgent.java:93-97`, `CustomerTools.java:207-209`). BE:
`TENANT_TIMEZONE` (`internal-availability.controller.ts:42`) se usa para **etiquetar** los instantes
(`timezone.util.ts:10-29`, independiente del `tzdata`) mientras las franjas se construyen con `new Date` +
`setHours` en la zona del contenedor (`bookings.service.ts:70-74`); el compose fuerza `TZ=America/Bogota`
(`docker-compose.yml:57`). El acople está escrito en `AGENTS.md` §11.5 como advertencia manual y **no hay
comprobación en runtime ni en CI**.

**Por qué importa.** Dos relojes y dos zonas que deben ser la misma sin verificarse. Si `TZ` desaparece del
contenedor de BE (o cambia la zona del negocio), las franjas del día se calculan en otra zona pero se
etiquetan con el offset correcto: el resultado *parece* correcto y es falso, justo en el dato que Fase 2
convertirá en la hora de una cita. Y los campos `timezone`/`now` que BE envía en cada turno no los usa IA
para nada: la mitad del contrato es decorativa.

**Propuesta.** (1) Que BE calcule las franjas con offset explícito (o al menos afirme `process.env.TZ` en el
arranque y lo haga obligatorio en el compose); (2) una comprobación de acople entre los dos servicios —por
ejemplo, que IA publique su tenant y zona en `/actuator/info` y que BE avise/fallé en el arranque si no
coinciden, o una prueba de conformidad que compare las dos configuraciones—; (3) decidir qué hacer con
`locale`/`timezone`/`now`: usarlos (no pueden validar nada, porque el cuerpo no está firmado) o quitarlos del
contrato para no prometer algo que no ocurre.

### J-07 — El contrato de error no es el implementado (Media)

**Evidencia.** `web-chat-api.openapi.yaml:51-55` y `internal-api.openapi.yaml:222-245` declaran `Problem`
(RFC 9457) para los errores; BE no tiene filtro global (`main.ts:50-59`) y devuelve el formato de Nest
(`{statusCode,message,error}`), que es el que el frontend espera (`src/types/auth.ts:44-48`). IA sí emite
RFC 9457 (`ApiExceptionHandler.java:94-98`, `ProblemDetailSecurityHandler`). `ia-bot.client.ts:104-108`
colapsa todo lo no-400/501 en 502 y reutiliza el texto interno (`:111-118`) como mensaje para la clienta.

**Por qué importa.** Un cliente generado desde los contratos (el frontend de la Fase 2, o el propio
dashboard) no sabrá leer ni los errores de BE ni los de IA; y el texto interno acaba delante de la clienta
(«Tenant no permitido»). Además, nada detecta si un lado cambia la forma de error: no hay contrato de error
ejercitado entre los dos repos.

**Propuesta.** (1) Que cada contrato describa la forma **real** (o añadir en BE un filtro global RFC 9457 y
actualizar el tipo del frontend: es un cambio coordinado, no unilateral); (2) en BE, mapear los errores de IA
a un texto fijo para la clienta y registrar el `detail` con el `turnId` (sin PII); (3) una prueba de
conformidad compartida: un archivo de casos (cuerpo de petición + código + forma de error esperada) que
ambos repos ejecuten contra su implementación.

### J-08 — La identidad del token no la lee nadie (Media)

**Evidencia.** El guard deja la identidad en `request.turn` (`internal-auth.guard.ts:30-43`) y existe el
decorador `@TurnContext` (`turn-context.decorator.ts:9-14`), pero **ningún** controlador lo usa: el único
resultado de la búsqueda es el comentario del propio archivo. Los dos controladores internos de Fase 1 solo
usan la ruta y la query (`internal-services.controller.ts:27-45`, `internal-availability.controller.ts:32-54`).
`RolesGuard` solo actúa si hay `@Roles` (`common/guards/roles.guard.ts:11-19`) y las rutas internas no lo
llevan.

**Por qué importa.** La regla que sostiene todo el diseño («la identidad sale del token, nunca del cuerpo ni
de los argumentos del modelo») está implementada en la emisión y en la verificación, pero **nunca ejercitada
en un endpoint**. La primera vez será en Fase 2, con escrituras: si el implementador de `GET /me/bookings` o
de `crearCita` acepta un `userId`/`serviceId` del cuerpo «porque es más fácil», nada en el repo lo impedirá,
y la guardia `InternalAuthGuard` seguirá pareciendo suficiente.

**Propuesta.** Antes de la primera herramienta de escritura: declarar en `internal-api.openapi.yaml` que todo
endpoint de Fase 2 resuelve el sujeto desde `turn.userId`; añadir en BE una prueba que falle si un handler
interno lee identidad de `body`/`query`; y usar `@TurnContext()` en el primer endpoint que la necesite
(con lo que el decorador deja de ser código muerto).

### J-09 — Turn token reutilizable y sin ámbitos (Baja-media)

**Evidencia.** `TurnTokenService.verify` comprueba firma, `kid`, issuer, audience, expiración y presencia de
campos (`turn-token.service.ts:71-100`), pero el `jti` no se registra ni se consume en ninguna parte: el mismo
token sirve para N llamadas internas hasta que expira (300 s por defecto). El guard no distingue lectura de
escritura (`internal-auth.guard.ts:30-43`) y el contrato no define ámbitos.

**Por qué importa.** Para la Fase 1 de solo lectura, reutilizar el token es lo correcto y necesario (varias
herramientas por turno). Con escritura, un token filtrado (logs, red interna, contenedor comprometido) da
capacidad de escritura durante 5 minutos. Nótese que el cuerpo de la petición de chat **no** está firmado: la
autoridad es el token, así que su manejo debe endurecerse justo cuando empiece a mover dinero.

**Propuesta.** TTL más corto (60-120 s) o tokens de un solo uso para las operaciones de escritura; que
`Idempotency-Key` se derive de `jti` + operación para que un token reutilizado no pueda crear dos recursos
distintos; y que los endpoints de escritura exijan `agent`/`channel` esperados además del tenant.

### J-10 — Vínculo entre conversación y sesión perezoso (Baja-media)

**Evidencia.** `conversationId = dto.conversationId || randomBytes(16)` (`chat.service.ts:86`); el 403 solo
se aplica si ya existe fila (`:220-232`: `if (!state) return;`), es decir, **la primera vez la creación la
decide quien primero use ese id**; el DTO acepta cualquier cadena de hasta 64 caracteres
(`web-chat-request.dto.ts:24-35`); y `resolveIdentity` acepta como sesión cualquier cookie de ≥16 caracteres
(`:197-201`).

**Por qué importa.** Con un id de 128 bits la suplantación práctica es inviable, así que no es un agujero de
autorización: es un problema de **coste y de higiene**. Cada combinación (cookie nueva + `conversationId`
nuevo) inserta una fila en `chat_conversation_states` de BE y crea una clave de memoria en
`ia.spring_ai_chat_memory`; no hay poda en ninguno de los dos lados (`crontab.txt` solo hace backups). El id
del cliente entra además en la clave de memoria de IA como concatenación (`CustomerAgent.java:89-91`): hoy no
colisiona porque el prefijo lo pone el servidor, pero nada valida el formato.

**Propuesta.** Emitir el `conversationId` siempre en el servidor y devolverlo en una cookie firmada (o exigir
que exista fila), validar `^[a-f0-9]{32}$`, y añadir poda de conversaciones/memorias antiguas en los dos
esquemas (con la política de retención que reclama A-17 en IA).

### J-11 — Una cabecera, dos secretos (Baja)

**Evidencia.** BE→IA envía `X-Internal-Api-Key: IA_BOT_API_KEY` (`ia-bot.client.ts:73-76`) e IA lo valida con
`IA_BOT_API_KEY` (`application.yml:81-83`); IA→BE envía la misma cabecera con `INTERNAL_API_KEY`
(`BackendClient.java:84-90`) y BE la valida con `INTERNAL_API_KEY` (`internal-auth.guard.ts:46-52`). Dos
secretos distintos bajo el mismo nombre de cabecera, documentados juntos en los dos `.env.example`.

**Por qué importa.** Si alguien intercambia los dos valores al configurar el despliegue, el síntoma es un 401
en una dirección y otro 401 en la otra, indistinguibles en los logs: «Clave de servicio ausente o invalida»
sin decir de cuál.

**Propuesta.** Nombres distintos por dirección (por ejemplo `X-IA-Service-Key` para BE→IA y
`X-Internal-Api-Key` para IA→BE) y registrar la dirección y el `turnId` en los fallos de clave en los dos
lados.

### J-12 — El `turnId` de vuelta no se comprueba (Baja)

**Evidencia.** IA devuelve el `turnId` que recibió (`ChatController.java:122-126`) y BE lo declara en su
interfaz (`ia-bot.client.ts:36-42`), pero `chat.service.ts:157-170` usa el local `turnId` y descarta el de la
respuesta.

**Por qué importa.** El `turnId` es la única clave de correlación entre los dos sistemas; hoy nada detecta
que la respuesta que se le muestra a la clienta corresponda al turno que pidió (ni a una conversación
cruzada, ni a una respuesta cacheada).

**Propuesta.** Comparar `iaResponse.turnId` con el `turnId` emitido; si no coincide, 502 con registro del
incidente (es una anomalía, no un caso normal).

### J-13 — Sin pruebas de conformidad entre repos (Baja)

**Evidencia.** IA tiene pruebas de contrato con WireMock que fijan *su expectativa* del backend; BE tiene
`src/modules/internal/__tests__/internal-*.spec.ts` y `chat/__tests__/*.spec.ts` que fijan sus controladores.
No hay nada que ejecute la expectativa de un lado contra la implementación del otro. Los dos contratos
`*.openapi.yaml` viven en `saaspa-IA/docs/contracts/` y solo se reconcilian a mano.

**Por qué importa.** Es la causa raíz de J-07, J-11 y de los campos muertos de J-06: los desajustes no se
detectan hasta que alguien los lee, y en Fase 2 el número de campos compartidos crece (citas, estados de
pago, deep-link).

**Propuesta.** Un archivo de casos compartido (JSON/YAML) versionado en un único sitio —lo más simple: dentro
de `saaspa-IA/docs/contracts/`— con `petición → respuesta/código/forma de error`, que BE ejecute contra sus
controladores (supertest) e IA contra su cliente (WireMock). Se puede empezar por los errores y por los dos
endpoints internos que ya existen.

---

## 7. Lo que verifiqué y está bien (para no reabrirlo)

- **Dirección de las dos claves de servicio**: no se mezclan; cada dirección usa un secreto distinto y IA
  falla en cerrado si la suya no está configurada (`ServiceKeyAuthenticationFilter.java:31-36`).
- **Comprobación de tenant** en los dos lados con `403` (IA `ChatController.java:80-82`; BE
  `internal-auth.guard.ts:36-39`), con la salvedad del fallo abierto de J-06/§4.5.
- **El turn token se reenvía tal cual** (`BackendClient.java:84-92`) y BE autoriza con el token, no con
  parámetros de la petición (`internal-auth.guard.ts:20-21`).
- **`@SkipThrottle()` en los dos controladores internos** (`internal-services.controller.ts:22`,
  `internal-availability.controller.ts:23`): las llamadas internas no consumen el cupo del widget.
- **Disponibilidad consistente con la del sitio**: el endpoint interno usa la misma función que el público
  (`bookings.service.ts:35-38,44-67`), incluidas las reservas temporales en Redis, así que IA no ofrece
  franjas que la web ya ocupa.
- **`price`/`compareAtPrice` numéricos** (`services.repository.ts:14-18`) y **offset explícito + `timezone`**
  en disponibilidad: el contrato interno se cumple.
- **Sin PII en los logs de ninguno de los dos lados**: BE registra solo `turnId`, canal y conversación
  (`chat.service.ts:152-155`); IA registra el tipo de excepción (R8).
- **Cookie de sesión anónima bien formada** (httpOnly, `sameSite=lax`, `secure` en producción,
  `chat.service.ts:210-218`) y `conversationId` de 128 bits cuando lo genera el servidor.
- **La decisión de handoff y de identidad vive en el código**, no en el prompt
  (`HandoffPolicy.java:10-18`, `ChatController.java:97-105`).
- **Timeouts explícitos en IA** hacia el modelo y hacia BE (`application.yml:69-80`), con
  `ProblemDetail` y deadline cancelable.

---

## 8. Límites de esta revisión

1. **No ejecuté los builds ni las pruebas** de ninguno de los dos repos (habrían escrito en `target/` y
   `dist/`). Todo lo afirmado sobre comportamiento sale de leer el código y la configuración, no de ejecutarlos.
2. **No leí `.env.example` de `kamerinos-infra` ni ningún `.env` real** (archivos con posibles secretos). Si
   ese archivo documenta las variables de J-01, el problema sigue siendo el mismo: **Docker Compose solo
   inyecta en el contenedor lo que está en `environment:` o en `env_file:`**, y el servicio `backend` no tiene
   `env_file:` ni esas claves.
3. **No re-audité el resto de `saaspa-backend`** (auth, pagos, Wompi, Google Calendar, WhatsApp) más allá de
   lo que toca la integración. Las rutas de escritura de Fase 2 (`bookings`) las leí solo para valorar los
   riesgos de esa fase.
4. **No revisé `saaspa-frontend`** más que el manejo de errores y la ausencia de cliente de chat (J-02). Si
   existe un widget fuera de `src` o servido por otro origen, el hallazgo cambiaría: basta con buscar
   `/api/chat` en todo el repo.
5. **El 504 de IA y el 502 de BE no los he provocado en un entorno real**; su encadenado es una conclusión de
   leer los dos clientes y sus plazos (J-04, J-07).
6. No he podido comprobar el despliegue en el VPS; J-01 se basa en el compose y en el `Dockerfile`, que son
   la fuente de verdad de la configuración del contenedor.

---

## 9. Recomendación final: ¿procede abrir Fase 2 tal como está?

**No tal como está. Sí se puede abrir en diseño y contrato, y sí se puede cerrar antes lo que bloquea.**

- **Se puede empezar ya, sin riesgo:** el diseño de las herramientas de escritura, el contrato
  (`internal-api.openapi.yaml` con `Idempotency-Key`, estados y deep-link), el dataset de evaluación de los
  flujos de escritura, `misCitas` (solo lectura, resuelto desde `turn.userId`) y las pruebas de conformidad
  de J-13 sobre los endpoints que ya existen.
- **Ninguna herramienta de escritura debería fusionarse antes de cerrar estos cuatro bloqueantes:**
  1. **J-03 — abuso/coste**: `trust proxy` acotado (o nginx sobrescribiendo `X-Forwarded-For`), tope global
     por tenant en IA y tope por cuenta para crear citas. Sin esto, la escritura es un ataque automatizable.
  2. **Expiro de `PENDIENTE_PAGO`** y tope de reservas pendientes por usuario: sin ello, una conversación
     puede bloquear la agenda sin pagar nada.
  3. **J-08 + J-09 — identidad y ámbito**: la regla «el sujeto sale del token» declarada en el contrato y
     con prueba, y `Idempotency-Key` atada a `jti` + operación. Son las dos condiciones que hacen que una
     escritura sea atribuible y no duplicable.
  4. **J-05 — handoff con destino y reversible**: con escritura, un falso positivo del detector de salud deja
     a la clienta fuera del canal de reservas para siempre.
- **J-01 y J-02 no bloquean escribir código, pero sí bloquean el piloto**: sin `ia-bot` en el despliegue y
  sin widget, ni la Fase 1 ni la Fase 2 se pueden usar de verdad. El trabajo de despliegue (contenedor,
  variables, health check obligatorio y validación de configuración al arrancar) debería empezar en paralelo
  a Fase 2, no después.
- **J-04, J-06 y J-07 son deuda técnica barata que conviene pagar en la misma pasada**, porque los tres se
  vuelven caros justo cuando hay dinero de por medio: la escalera de timeouts (reintentos = citas
  duplicadas), la zona horaria (la hora de la cita) y la forma de los errores (el diagnóstico de un fallo de
  reserva).

En una frase: **el código de los dos repos está listo para leerse entre sí; el sistema no está listo para
mover una cita. Cerrar J-03, el expiro de `PENDIENTE_PAGO`, J-08/J-09 y J-05 primero; el resto, con ellos.**
