# Tercera revisión conjunta de integración (saaspa-backend ↔ saaspa-IA)

**Fecha:** 2026-09-28
**Alcance:** revisión de **preparación** previa al diseño de la Fase 2 (herramientas de escritura), no de diseño.
**Método:** solo lectura sobre el código real de `develop` en los dos repos, más `kamerinos-infra` y
`saaspa-frontend` como contexto de despliegue. Ningún archivo de los otros repos fue tocado.

**Referencias verificadas con `git fetch` (no contra PRs ni ADRs):**

| Repo | Referencia | Nota |
|---|---|---|
| `saaspa-IA` | `develop@cfc392f` (árbol limpio, rama de trabajo `docs/web-chat-api-problem-json`) | contiene J-06 (ADR 0016), J-07 (ADR 0017), H-05 (ADR 0015) y ADR 0020 (`origin_cap` + `h04-origin-claim-validation.md`) |
| `saaspa-backend` | `develop@c84bbae` | contiene H-01 (#81), H-03 (#82), H-04 (#83), H-05 (#85/#86), J-07 (#86) |
| `kamerinos-infra` | `docker-compose.yml` **modificado sin commitear** (`main`, mtime 2026-09-26 23:40) | es el único archivo tocado de infra; se leyó, no se editó |
| `saaspa-frontend` | sin cambios relevantes | solo se comprobó la existencia de rutas y la ausencia del widget |

**Límites de esta revisión:** no se ejecutó ningún test ni build (solo lectura); los veredictos se apoyan en
código, tests y contratos leídos, no en una reproducción en runtime. Donde el veredicto depende de algo que no
se leyó línea a línea, se dice explícitamente en §7.

---

## 0. Veredicto en cinco líneas

1. **J-06 y J-07 están cerrados de verdad** en el código de este repo, con mecanismo, pruebas y contratos
   coherentes con ADR 0016 y ADR 0017. No introdujeron un hueco funcional nuevo.
2. Los dos dejaron **residuales menores y ya documentados** (aviso de acople "una vez por instancia"; los textos
   propios del gateway siguen siendo crudos) más **un cambio de política que la Fase 2 debe revisitar**: el acople
   de zona horaria se resuelve con un **aviso**, no con un fallo — aceptable para leer, arriesgado para escribir.
3. **Todos los hallazgos de la ola 1 y de la segunda revisión están cerrados** en código (H-01, H-03, H-04, H-05);
   B-01 y J-04 también. Lo que queda abierto de ellos es frontend (H-06) y una decisión manual de dinero (PAGO_TARDE).
4. **Dos cambios de estado no anotados**: el compose de infra **ya tiene la escalera correcta** (mi informe #2 decía
   lo contrario y el checklist de saaspa-IA sigue dándolo por pendiente), y el contrato `internal-api` de este repo
   **todavía afirma que `POST /api/bookings` no acepta `Idempotency-Key`**, cosa que es falsa desde el PR #78.
5. **Un hallazgo nuevo, de privacidad**: el hash de origen (`origin_hash`, ADR 0020) se calcula con una **sal que
   nadie inyecta en el despliegue** y cuyo valor por defecto es una constante pública del repo; en esa
   configuración la IP sí es recuperable por fuerza bruta. El ADR dice "en producción la sal es obligatoria",
   pero nada lo exige y la lista de pendientes de despliegue no lo menciona.

**Sobre abrir la Fase 2:** se puede abrir el diseño, el contrato y `misCitas` (lectura) ya; **la primera
herramienta de escritura no debería construirse antes de resolver tres cosas** (§5 y §6): el deep-link de pago no
tiene productor en ningún repo, la idempotencia está atada al **turno** y no a la **intención**, y el despliegue
sigue sin las variables del acople (el contenedor `ia-bot` no arrancaría por falta de secretos, no por el código).

---

## 1. J-06 / ADR 0016 — acople de tenant y zona horaria: cerrado

**Lo que el ADR promete y lo que hay.**

| Promesa del ADR 0016 | Implementación real | Veredicto |
|---|---|---|
| Comparar la zona que envía NestJS con la propia y **avisar una vez** si no coinciden | `TenantCouplingCheck.java:51-56` (compara y `warn` con `AtomicBoolean`), invocado **en cada turno** desde `api/ChatController.java:100` (`tenantCouplingCheck.check(request.timezone())`) | cumplido, y vivo (no es código muerto) |
| No cortar el turno (el campo es informativo, C-02) | el check devuelve `boolean` y el controlador lo ignora; ninguna rama lanza | cumplido |
| Validar la **propia** zona al arrancar (una errata impide arrancar) | `config/TenantProperties.java:31-46`, constructor compacto: tenant no vacío + `ZoneId.of(timezone)` con mensaje que cita la variable | cumplido |
| Publicar tenant/zona/prompt/modelo en `/actuator/info` | `tenant/TenantInfoContributor.java:39-44` | cumplido |
| El contrato no cambia (solo prosa) | `chat-api.openapi.yaml` v0.6.3 describe `timezone` como señal del acople y aclara que `locale`/`timezone`/`now` no deciden nada | cumplido |
| Pruebas | `tenant/TenantCouplingCheckTest.java` (aviso una sola vez, silencio si coinciden, tolerancia sin valor, predicado) y `config/TenantPropertiesTest.java` (existe, en `config/`, no en `tenant/`) | cumplido |

**En el lado de `saaspa-backend`:** el ADR no pedía cambios y no hacen falta; el backend envía
`timezone: configService.get('TENANT_TIMEZONE')` en el cuerpo del turno y la comparación es literal. Un
despliegue con `TENANT_TIMEZONE` sin definir envía el mismo valor por defecto que este servicio
(`America/Bogota`), así que el caso "los dos sin definir" no genera un falso positivo.

**Residuales (documentables, no huecos):**

- R-06.a — El aviso es **una vez por instancia** (`AtomicBoolean`): una segunda desalineación dentro del mismo
  JVM (p. ej. dos despliegues que se corrigen y se vuelven a romper sin reiniciar) no vuelve a avisar. Aceptado.
- R-06.b — **La política "avisar, no fallar" no sobrevive intacta a la Fase 2.** Con solo lectura, una zona
  equivocada devuelve una fecha equivocada; con `crearCita` en el medio, **agenda la cita a la hora equivocada** y,
  encima, con dinero. El ADR decidió bien para la Fase 1; antes de la primera escritura hay que decidir si el
  mismo desacople sigue siendo un `warn` o pasa a cortar el turno (o a bloquear solo las escrituras).
- R-06.c — `/actuator/**` no está publicado en nginx (`kamerinos-infra/nginx/kamerinos.conf`: sin ruta
  `actuator`) y `ia-bot` no publica puertos, así que el `info` con prompt/modelo no es alcanzable desde fuera de
  la red interna. Correcto.

---

## 2. J-07 / ADR 0017 — contrato de error y texto de cara a la clienta: cerrado

**Este repo (la mitad propia).**

- Catálogo cerrado `api/ProblemCode.java`: 12 códigos con estado, título y texto público
  (`TURN_CONTEXT_MISMATCH`, `INVALID_BODY`, `MALFORMED_BODY`, `UNAUTHENTICATED`, `ACCESS_DENIED`,
  `TENANT_NOT_ALLOWED`, `COST_LIMIT`, `AGENT_NOT_IMPLEMENTED`, `BACKEND_UNAVAILABLE`, `BACKEND_ERROR`,
  `MODEL_TIMEOUT`, `UNEXPECTED`). El `detail` del `ProblemDetail` es el texto público; el motivo técnico va al log.
- **Hay una prueba que sostiene el catálogo entero**: `api/ProblemCodeTest.java:22-54` recorre el enum y falla si
  un `title`/`detail` sale del alfabeto permitido, si contiene jerga interna o términos del roadmap (lista negra) o
  si un código no agrupa un estado de error. Es la pieza que impide que esto se degrade con el próximo PR.
- Los rechazos de seguridad ahora **dejan rastro** y el `code` viaja en los 401/403 (`security/ChatApiSecurityTest.java`).

**La mitad coordinada (verificada en el código de `saaspa-backend`, no en su PR).**

- `common/filters/problem-details.filter.ts` produce RFC 9457 (`application/problem+json`) con `type` =
  `about:blank`, `title`, `status`, `detail` e `instance`; `common/http/problem-extensions.ts:7` copia las cinco
  extensiones del tope (`scope`/`measure`/`measured`/`limit`/`window`) **solo** si vienen, y el resto del API
  conserva la forma de Nest.
- Está aplicado **solo a las rutas del chat**: `@UseFilters(ProblemDetailsFilter)` sobre `ChatController`
  (`chat/chat.controller.ts:36`). Es decir: **el frontend no cambió de forma de error en el resto del API** y
  `web-chat-api.openapi.yaml` v0.4.0 documenta la verdad confirmada.
- El mapeo de estados quedó como pedía el ADR: 429 de la IA → **429** (ya no 502), timeout → 504 con `turnId`,
  resto → 502, y un `warn` cuando el `scope` del tope es `tenant`.

**Residuales (todos ya anotados como "pedido de baja prioridad, redactado y NO enviado" en §11.5):**

- R-07.a — El filtro reenvía `source.message`, así que los errores que **genera el propio gateway** llegan a la
  clienta con su texto original. Dos casos concretos y verificados:
  - el throttler: `@nestjs/throttler` lanza `ThrottlerException: Too Many Requests`
    (`node_modules/@nestjs/throttler/dist/throttler.exception.js:5-8`) → ese es el `detail` que ve la clienta al
    pasar de 20 req/min;
  - el `ValidationPipe` del chat: mensajes de `class-validator` en inglés.
  No es un hueco nuevo (antes veía el mismo texto en `message`), pero es el **único tramo de J-07 que queda** y es
  baratamente arreglable en el backend (mensaje propio en el `ThrottlerModule`). La ADR 0017 ya lo deja de su lado.
- R-07.b — El filtro **no reenvía** `code` al widget (copia solo las cinco extensiones, a propósito), así que el
  widget distingue el tope de coste del throttler por la presencia de extensiones, no por código. Documentado en
  los dos contratos.
- R-07.c — Un 500 inesperado también sale con `problem+json` en las rutas de chat (el filtro es `@Catch()`): es
  coherente, pero conviene que el widget no asuma que un 500 ahí es un HTML de Nest. Ya está en el contrato.

---

## 3. Estado de los hallazgos previos (¿alguno cambió sin que nadie lo anote?)

| Hallazgo | Estado real verificado hoy | ¿Anotado? |
|---|---|---|
| **H-01** carrera pago ↔ expiración | **Cerrado en código.** `payments.service.ts` decide el desenlace **antes** de escribir el pago (`confirmOnPayment`, `booking-sync.service.ts:21-31`), persiste `metadata.paymentOutcome`, y el atajo de "webhook duplicado" para `ABONO` ahora exige `paymentOutcome !== undefined` → un reintento de Wompi ya no se traga el pago tardío; el camino de pago tardío **no lanza** (devuelve `{received:true}`) y notifica. Nuevo estado `PAGO_TARDE` + `reviewRequired`, excluido de la ocupación (`repositories/bookings.repository.ts`, `occupancyFilter`). | Sí (BE `AGENTS.md` + `docs/dev.md`) |
| **H-02** despliegue | **Mitad de este repo: cerrada** (`Dockerfile` + `.dockerignore` en `develop`, con job `image` en CI). **Mitad de infra: parcialmente hecha y sin commitear.** El compose **ya tiene la escalera correcta**: `IA_BOT_TIMEOUT_MS: ${IA_BOT_TIMEOUT_MS:-25000}` (`:99`), `LLM_READ_TIMEOUT: 10s` y `LLM_TURN_DEADLINE: 20s` (`:137-138`), más `LLM_*` y `dockerfile: Dockerfile`. **Sigue faltando**: ninguna variable del acople está en su `.env`/`.env.example` (`LLM_API_KEY`, `TURN_TOKEN_PRIVATE_KEY`, `TURN_TOKEN_PUBLIC_KEY`, `INTERNAL_API_KEY`, `IA_BOT_API_KEY`, `IA_COST_GUARD_ORIGIN_SALT`, `SALON_NOTIFICATION_EMAIL` → 0 aciertos en los dos archivos), y Compose las interpola a **cadena vacía**. Sin destinatario de handoff ni claves, el primer turno da 401/500. | **Parcialmente.** El checklist de `saaspa-IA` (`- [ ] H-02, lado de kamerinos-infra (...) escalera 10 s/20 s/25 s en su compose, IA_BOT_TIMEOUT_MS en el bloque backend y las variables del acople en su .env.example`) sigue dando por pendientes las **dos primeras**, que ya están en el árbol de trabajo. |
| **H-03** aviso de handoff | **Cerrado.** Campos `handoffNotifiedAt`/`handoffNotifyError`/`handoffNotifyAttempts` (`prisma/schema.prisma:273-280`, migración `20260928120000_add_chat_handoff_notify_status`), `chat.service.ts:124-128` reintenta en el siguiente turno si el aviso no salió, y el repositorio registra el resultado (`chat-conversation-state.repository.ts:83-97`). | Sí |
| **H-04** 429→502 y tope por origen | **Cerrado en los dos lados.** `ia-bot.client.ts` mapea 429→429 (con `warn` si `scope=tenant`); el backend emite el claim `clientIp` y este repo lo consume: `security/TurnTokenAuthenticationConverter.java:31`, `usage/OriginHasher.java`, `usage/TurnCostGuard.java:59-65` (la cubeta de origen entra en la **misma** consulta agregada sobre `ia.turn_log`, con `FILTER (WHERE origin_hash = :hash)`), `Scope.ORIGIN` y migración `V4` con `origin_hash` + índice. Evidencia de activación real en `docs/contracts/h04-origin-claim-validation.md`. | Sí |
| **H-05** `turn_log` incompleto | **Cerrado** (ADR 0015): migración `V3` con `handoff_reason`/`error_code`, `Status` = `OK`/`HANDOFF`/`DEADLINE`/`ERROR`, el guard cuenta todo lo que no es `HANDOFF` (`TurnCostGuard.java:59-65`) y `TurnOutcomeClassificationTest` fuerza una decisión si aparece un estado nuevo. **A-08 queda cerrado del todo** y la fila de `AGENTS.md` está actualizada. | Sí |
| **H-06** `EXPIRADA` sin consumidor | **Sin cambios, y crece.** El frontend sigue sin nada (no hay widget ni vistas nuevas), y ahora hay **dos** estados que el dashboard no conoce: `EXPIRADA` y `PAGO_TARDE`. Además `cancel` sigue aceptando una cita `EXPIRADA` y la convierte en `CANCELADA` (la traza del dinero sobrevive en la fila de `payments` y en `AuditLog`, pero el estado pierde el matiz). | Sí (BE `AGENTS.md`) |
| **J-04** escalera | **Cerrada en código y ahora también en el compose** (ver H-02). | Parcialmente (ver H-02) |
| **A-13** health/info | Parcialmente resuelto y anotado (`/actuator/info` ya no está vacío). Queda el estado del LLM/backend en `health`. | Sí |
| **J-01 / J-02** despliegue y widget | Sin cambios: no hay widget de chat en el frontend (los `.tsx` con "chat" son del dashboard y del widget **de pago**), y el despliegue sigue sin poder arrancar `ia-bot`. | Sí |
| **J-10** retención del id de conversación | Sin cambios (no hay purga ni retención en ningún repo; el crontab de infra solo hace backups). | Sí |
| **J-11 / J-12 / J-13** | Sin cambios. `h04-origin-claim-validation.md` es un paso real hacia J-13 (evidencia de conformidad escrita en este repo), pero no hay prueba de conformidad que corra en CI. | Parcial |

---

## 4. Hallazgo nuevo de esta revisión

### HN-01 (Media-alta, privacidad — ADR 0020) `origin_hash` se calcula con una sal que el despliegue no tiene

- **Mecanismo:** `usage/OriginHasher.java` calcula `HMAC-SHA256(sal, origen)` y **si no hay sal usa una constante
  literal del repo** (con un `warn` una sola vez). La sal se lee de `saaspa.cost-guard.origin-salt` →
  `application.yml:107` (`${IA_COST_GUARD_ORIGIN_SALT:}`, vacío por defecto).
- **Realidad del despliegue:** la variable **no está** ni en `kamerinos-infra/.env` ni en
  `kamerinos-infra/.env.example` (verificado: 0 aciertos), y el checklist de este repo tampoco la lista entre los
  pendientes de despliegue (§11.5 solo menciona la escalera y las claves del acople). Es decir: **el valor por
  defecto público es el que va a producción.**
- **Por qué importa:** con la clave conocida, `origin_hash` deja de ser pseudonimización y pasa a ser una
  **función reversible** del espacio IPv4 (2³² HMACs es un barrido trivial), y el propio ADR 0020 vende lo
  contrario ("no se puede revertir sin el secreto", "la IP no está en la tabla"). Además la tabla guarda una fila
  por turno con `(tenant_id, origin_hash, created_at)`, así que un volcado reconstruye el patrón de uso por IP.
- **Propuesta (sin implementar):** (1) añadir `IA_COST_GUARD_ORIGIN_SALT` a los tres sitios de despliegue
  (`kamerinos-infra/.env.example`, su `.env`, y el bloque de pendientes de §11.5 del `AGENTS.md` de este repo);
  (2) decidir el comportamiento sin sal: o **fallar cerrado al arrancar** en el perfil de producción, o **no
  escribir `origin_hash`** (el tope por origen queda inactivo y se ve en el `warn`, que ya existe) en vez de
  escribir un hash con clave pública. Hoy el sistema elige la opción silenciosa.

### HN-02 (Baja, deriva de documentación) el contrato `internal-api` afirma lo contrario que el backend

- `saaspa-IA/docs/contracts/internal-api.openapi.yaml:116` explica por qué existe el endpoint interno diciendo que
  **"hoy `POST /api/bookings` no acepta `Idempotency-Key`"**. Es falso desde el PR #78: el backend sí la acepta y
  la guarda en `bookings.idempotencyKey` (columna única; reintento → misma cita; clave de otro usuario → 409;
  formato `[A-Za-z0-9._:@-]{1,200}`), tal como documenta su `docs/dev.md` en "Escrituras de Fase 2 (ADR 0012)".
- **Por qué importa:** ese texto es el que va a guiar la implementación de las herramientas de escritura de este
  lado; dejarlo es empezar la Fase 2 con una premisa equivocada sobre qué garantiza el backend.
- **Propuesta:** reescribir la nota (el motivo real del endpoint interno es la **identidad del turno** y el
  **sujeto desde el token**, no la falta de cabecera) en la misma rama que abra la Fase 2.

### HN-03 (Baja, deriva de documentación) el triaje de `AGENTS.md` da por vivos dos estados que ya cambiaron

- `saaspa-IA/AGENTS.md:971-974` (sección del triaje) sigue diciendo que la mitad de J-03 "está implementada en
  `feature/f2-per-tenant-cost-guard`" (rama ya fusionada) y, sobre todo, que **"el 429 lo mapea hoy el backend a
  502 (J-07)"**. Lo segundo es falso desde el PR #83 del backend: `ia-bot.client.ts` mapea 429 → 429 (y emite un
  `warn` cuando el `scope` es `tenant`), que es justamente la mitad de H-04 que se cerró.
- Por qué importa poco pero no cero: es el párrafo que resume el estado del triaje para quien llegue nuevo; deja
  un hallazgo cerrado pareciendo abierto y una rama fusionada pareciendo viva.
- Propuesta: actualizar esas dos frases (y el ítem de H-02 ya citado en la tabla de §3) en la misma rama que abra
  la Fase 2.

---

## 5. Prerrequisitos de la Fase 2: qué no existe todavía

**Regla de trabajo de esta sección:** se lista lo que **falta**, con evidencia. No se propone la arquitectura de
las herramientas (fuera del alcance pedido).

### 5.1 Lo que la herramienta necesitará y **no existe** en `saaspa-backend`

| Pieza | Estado verificado | Evidencia |
|---|---|---|
| `POST /api/internal/v1/bookings` | **No existe.** El módulo interno solo tiene `services` (2 GET) y `availability` (1 GET) | `find src/modules/internal -name "*.ts"` → solo `internal-services.controller.ts`, `internal-availability.controller.ts`, `turn-identity.ts`, guard y specs |
| `PATCH`/`DELETE /api/internal/v1/bookings/{id}` (reprogramar/cancelar) | **No existe** (ninguna ruta interna de bookings) | ídem |
| `GET /api/internal/v1/me/bookings` (`misCitas`) | **No existe.** El `GET /api/bookings` público filtra por `userId` del **JWT de usuario**, que el agente no tiene | ídem |
| `paymentUrl` (deep-link de pago) | **No existe en ningún repo.** El proveedor de pago devuelve por contrato solo la configuración del **widget** — su tipo de retorno es literalmente `PaymentWidgetConfig` con cinco campos y ningún URL: `providers/payment-provider.ts:1-7` (`publicKey`, `reference`, `amountInCents`, `currency`, `signature`) — y la página de checkout del frontend no arranca desde un `reference`/`bookingId` por URL (no lee query params) | `providers/payment-provider.ts:1-7,29`, `payments/payments.service.ts:72,94` (ABONO) y `:498` (carrito), `saaspa-frontend/src/app/(public)/checkout/page.tsx`; el propio contrato lo admite: `internal-api.openapi.yaml:328-331` ("hoy no existe en el backend") |
| Endpoints internos de Fase 2 en el contrato | **Sí existen** (declarados, con `Idempotency-Key` **obligatoria** y 409 para franja/tope) | `internal-api.openapi.yaml:111-180` (v0.3.1) |

Lo que **sí** está listo del lado del backend y conviene no duplicar: el sujeto desde el token
(`internal/turn-identity.ts`, `requireTurnUser` → 403 sin identidad), la idempotencia del público
(`bookings.idempotencyKey`, índice único), la ocupación que ya excluye `EXPIRADA`/`PAGO_TARDE`, y dos pruebas
arquitectónicas que **cubrirán solas** los controladores nuevos: `internal-identity-contract.spec.ts` (un handler
que tome identidad del cuerpo/query/ruta/cabecera, o que reciba cuerpo sin leer el turno, **rompe la suite**) y
`internal-guards-metadata.spec.ts:17-24` (todo controlador interno debe ser `@Public` + `@SkipThrottle` +
`InternalAuthGuard`).

### 5.2 Lo que no existe en `saaspa-IA`

| Pieza | Estado verificado |
|---|---|
| Cliente HTTP de **escritura** | `backend/BackendClient.java` solo expone `get` (`:48`, `:62`, `:81`): no hay POST/PATCH, ni forma de mandar cuerpo, ni cabecera de idempotencia |
| Construcción de la `Idempotency-Key` | **No existe en el código** (`src/`): la palabra solo aparece en documentos (`docs/contracts/f1-e2e-validation.md`, `t1.0-backend-validation.md`). ADR 0008/0012 dicen que la construye el código a partir de `jti` + operación, y el backend documenta el formato exigido |
| Herramientas de escritura y **feature flag** | las 3 herramientas registradas (`CustomerAgentConfig` → `listarServicios`, `consultarServicio`, `consultarDisponibilidad`) son de lectura; no existe `ia.tools.write.enabled` ni equivalente |
| Cliente HTTP del roadmap | el `Dockerfile` y `.dockerignore` sí están en `develop`, así que este punto ya no bloquea |
| Casos de agenda en `eval/` | **No existen**: `eval/customer-agent.v1.jsonl` (30 líneas) solo menciona cita/agendar en 4 (R11 y el caso B-01) → R15 exige crecerlo en la misma rama que cambie el comportamiento |
| Prompt con flujo de confirmación | `prompts/customer-agent.v2.md` no tiene política de confirmación ni de escritura (esto es diseño, no prerrequisito: solo se anota que la R15 lo ata al dataset) |

Lo que **sí** está listo de este lado: el turn token ya expone lo que la escritura necesita (`userId`, `role`,
`channel`, `agent`, `conversationId`, `clientIp`/`origin`), el `Reply.links` del DTO existe (vacío en la Fase 1) y
las rutas del frontend que la R12 quiere enlazar **existen**: `saaspa-frontend/src/app/(public)/agendar` y
`(public)/shop`.

### 5.3 Tres realidades que condicionan el orden (no son piezas, son restricciones)

1. **La identidad del canal anónimo no escribe.** `requireTurnUser` responde 403 sin `userId`, y en el widget
   anónimo no hay identidad por diseño (ADR 0005/0012). Consecuencia: **el primer camino real de `crearCita` es
   una clienta logueada** (`WEB_LOGGED`); para el widget anónimo la respuesta correcta sigue siendo el enlace
   pre-diligenciado a `/agendar`. Cualquier E2E de la Fase 2 tiene que incluir el caso 403 del anónimo.
2. **El turno no es idempotente (A-07 sigue abierto).** Si el backend corta a los 25 s y la escritura ya ocurrió,
   la clienta reintenta y **el `jti` es nuevo** → clave nueva → segunda cita con el mismo tope de pendientes como
   único freno. La `Idempotency-Key` derivada de `jti` protege de un reintento **dentro** de un turno, no de dos
   turnos equivalentes.
3. **La escalera de plazos es corta para una escritura.** `LLM_READ_TIMEOUT=10s` / `LLM_TURN_DEADLINE=20s` /
   `IA_BOT_TIMEOUT_MS=25000` están calibrados para un turno de lectura; una escritura que además dispare correo o
   config de pago puede pasarse del deadline y dejar una escritura huérfana (lo que vuelve al punto 2).

---

## 6. Orden recomendado y riesgos de ese orden

*(Recomendación de secuencia, no diseño de herramientas.)*

**Paso 0 — antes de escribir la primera herramienta (barato, sin código de agente):**
1. Cerrar HN-02 (la nota del contrato `internal-api`) y HN-01 (la sal, o la decisión de no escribir `origin_hash`).
2. Decidir el deep-link de pago: quién produce el `paymentUrl` (backend con una URL de frontend pre-diligenciada, o
   el frontend aceptando un `reference`/`bookingId` por URL). Sin esta decisión, `crearCita` no puede cumplir la
   R12 y el ADR 0012 queda a medias.
3. Decidir si el acople de zona (R-06.b) sigue siendo aviso cuando hay escritura de por medio.
4. Actualizar el checklist de H-02 (2 de 3 puntos hechos) y añadir la sal a los pendientes de despliegue.

**Paso 1 — backend primero, y solo el endpoint que el primer tool va a llamar.** Riesgo del orden inverso: si
este repo construye la herramienta antes de que el endpoint exista, **no hay E2E posible** (solo WireMock) y la
semántica de idempotencia —que es justo el criterio de aceptación de la Fase 2 en `AGENTS.md` §10 ("sin duplicar
citas ante reintentos")— solo se puede validar contra el índice único real. El backend tiene además la ventaja de
que sus dos pruebas arquitectónicas ya obligan a resolver la identidad desde el token.

**Paso 2 — `misCitas` (lectura) primero entre las herramientas.** Es la única de las cuatro sin escritura, sin
idempotencia y sin confirmación; ejercita el camino nuevo (cliente HTTP con el turn token + identidad `CLIENTE`)
con riesgo cero de duplicar o cobrar, y sirve de banco de pruebas para el cliente HTTP de escritura.

**Paso 3 — `crearCita` con el flag apagado y la clave derivada de la intención, no solo del turno.** Aquí está el
riesgo mayor del orden: si la clave sale del `jti`, el bug de "dos turnos equivalentes, dos citas" **no lo detecta
ninguna prueba de idempotencia** (cada turno es único por definición). Hay que decidir antes: o la clave se deriva
de algo estable entre reintentos, o la herramienta busca antes una reserva pendiente equivalente, o se acepta el
duplicado y se documenta. Las tres opciones cambian el contrato interno; decidirlo después de construirlo es
retrabajo.

**Paso 4 — `cancelarCita` y `reprogramarCita`.** Van último por dos motivos concretos: (a) tocan estados que hoy
tienen caminos de dinero vivos (`EXPIRADA`, `PAGO_TARDE`, y el reembolso de H-01 es una decisión manual que el
dashboard ni siquiera muestra), y (b) `cancel` hoy acepta una cita `EXPIRADA` y la convierte en `CANCELADA`; si el
agente cancela por chat hay que decidir ese matiz antes, no después.

**Riesgos transversales del orden elegido:**

- **Riesgo de coordinación:** los tres endpoints internos son de otro repo y ninguno está pedido formalmente
  todavía (el contrato sí los describe). Sin un pedido explícito en §11, la Fase 2 empieza con trabajo que este
  repo no puede completar solo.
- **Riesgo de doble fuente en la validación de fechas:** hoy la fecha relativa la resuelve este repo en la zona del
  tenant y el backend la vuelve a validar; con escritura eso está bien, pero cualquier cambio de política de zona
  (R-06.b) hay que hacerlo en los dos lados a la vez.
- **Riesgo de observabilidad:** los intentos de escritura del agente quedarán en `ia.tool_call_log` (no en
  `ia.turn_log` más allá del desenlace), y del lado del backend **la auditoría de las llamadas internas sigue
  pendiente** (heredado de la Fase 1): una escritura duplicada o rechazada no deja rastro de quién la pidió. Antes
  de habilitar escritura conviene cerrar ese agujero o aceptarlo por escrito.
- **Riesgo de tope:** el límite de reservas pendientes por cuenta (2) y el tope de coste del tenant (240 turnos/h)
  pueden confundirse entre sí desde el chat (dos 409/429 con causas distintas) y el modelo tenderá a reintentar.

**Sobre el checklist de la Fase 2 en `AGENTS.md` §10:** los cuatro "bloqueantes antes de la primera herramienta de
escritura" (J-03, B-01, J-08+J-09, J-05) **están efectivamente cerrados**; el bloqueante real que queda ya no es
de esa lista, es el de §6 paso 0 (deep-link, intención vs turno, sal y despliegue).

---

## 7. Qué no se verificó (límites)

- No se ejecutó ningún test ni build en ninguno de los dos repos (revisión de solo lectura).
- H-01 se verificó leyendo el camino del webhook aprobado y el atajo de duplicado; **no** se leyó línea a línea
  `handleLatePayment` ni el email de pago tardío (asumo su contenido por el `diff` y las pruebas que menciona el
  commit, no por lectura directa).
- La afirmación "el frontend no puede arrancar un pago desde una URL" se apoya en que la página de checkout no lee
  `searchParams`; no se revisó todo el flujo de pago del frontend.
- No se comprobó el estado del despliegue en runtime (ningún contenedor corriendo), solo el archivo de compose y
  los `.env`.
- Los informes anteriores (`2026-09-26-joint-integration-review.md`, `-2.md`, el triaje y el de Hermes) **no están
  editados**; este es un archivo nuevo.

---

## 8. Recomendación final

**Sí a abrir la Fase 2 como trabajo de preparación** (contrato, pedido formal de los endpooints internos, `misCitas`
y el cliente HTTP de escritura), porque nada de lo ya construido lo impide y el mecanismo de identidad está probado.

**No a construir todavía `crearCita` / `reprogramarCita` / `cancelarCita`** hasta que existan: el endpoint interno
correspondiente en `saaspa-backend`; una decisión escrita sobre **idempotencia por intención vs por turno**; un
productor para el `paymentUrl`; y las variables del acople (más la sal del hash de origen) en el despliegue. Las
cuatro son baratas de resolver y caras de descubrir con dinero en medio.
