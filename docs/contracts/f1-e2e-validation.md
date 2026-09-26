# Fase 1 — Cierre: E2E real y reconciliación de contratos

- **Estado:** cierre del criterio de aceptación E2E de la Fase 1 y reconciliación de los tres contratos
  de `docs/contracts/` con el código real que ya existe en `saaspa-backend`.
- **Fecha:** 2026-09-26 (rama `docs/f1-closeout-and-contract-reconciliation`).
- **Referencia leída del backend:** `xjapn03/saaspa-backend`, rama **`develop`**, commit **`9fc8b12`**
  (clon superficial en `/tmp`, solo lectura; el repositorio **no se modificó**).
- **Referencia de este servicio en el momento del E2E:** la punta de `develop` era **`5a9eaf2`** (merge de
  PR #19, 09:35 hora local); el registro persistido lo confirma con `prompt_version = customer-agent.v1`
  (PR #20 y PR #21 se fusionaron después, a las 17:38 y 17:57 hora local).
- **Método:** lectura del árbol y de los ficheros del backend con `gh api` y un clon superficial
  (`git clone --depth 1`), lectura de los specs E2E del backend, y consulta **de solo lectura** del
  volumen local `saaspa-ia_ia_pg_data` para recuperar la evidencia que dejó el turno real.
  Sin PII: no se copiaron textos de conversación, teléfonos ni `conversation_id`.

---

## 1. Qué se validó de punta a punta (criterio de aceptación)

Camino completo del turno, con todos los componentes reales (chat web anónimo → NestJS → turn token →
este servicio → herramienta → NestJS → catálogo):

1. La clienta escribe en el chat web (anónima). El backend resuelve tenant, canal, agente e identidad y
   firma un **turn token ES256** (`src/modules/chat/chat.service.ts`).
2. `IaBotClient` llama a `POST {IA_BOT_URL}/api/v1/chat` con `X-Internal-Api-Key: IA_BOT_API_KEY` y
   `Authorization: Bearer <turn token>` (`src/modules/chat/ia-bot.client.ts`).
3. Este servicio verifica la clave de servicio y el turn token, enruta al agente CLIENTAS y el modelo pide
   la herramienta `listarServicios`.
4. La herramienta llama a `GET /api/internal/v1/services` con `X-Internal-Api-Key: INTERNAL_API_KEY` y el
   turn token reenviado; `InternalAuthGuard` autoriza con la identidad del token
   (`src/modules/internal/guards/internal-auth.guard.ts`).
5. La respuesta llega a la clienta con **precios reales del catálogo** y el turno queda registrado.

**Resultado: PASS** (validación manual de la persona, 2026-09-26).

## 2. Evidencia persistida en el esquema `ia` (nuestra autoridad, R5)

`ia.turn_log` — 1 fila:

| Campo | Valor |
|---|---|
| `turn_id` | `dace6f16-0754-4a13-9990-1688e0b0baf3` |
| `tenant_id` | `kamerinos` |
| `channel` / `agent` | `WEB_WIDGET` / `CLIENTAS` |
| `prompt_version` | `customer-agent.v1` |
| `model` | `deepseek-flash` |
| `tokens_in` / `tokens_out` | `3750` / `317` |
| `latency_ms` | `3611` |
| `created_at` | `2026-09-26 21:35:45.910455+00` (16:35:45 en `America/Bogota`) |
| `user_id` / `role` | `NULL` / `NULL` (identidad anónima, coherente con el canal) |

`ia.tool_call_log` — 1 fila: `listarServicios`, `status = OK`, `latency_ms = 65`,
`args_json = {}`, `result_json` objeto, mismo `turn_id`, `created_at = 2026-09-26 21:35:44.246+00`.

`ia.spring_ai_chat_memory` — 3 filas: `USER` (16:11:26, 22 caracteres) y `USER` (16:35:42, 22 caracteres)
+ `ASSISTANT` (16:35:45, 780 caracteres).

Lecturas de esta evidencia:

- El turno **sí** ejecutó la herramienta real (65 ms) antes de redactar la respuesta, y los tokens y la
  latencia quedaron registrados: los criterios "precio real desde la herramienta" y "tokens registrados"
  quedan acreditados con datos persistidos, no con una impresión de pantalla.
- El turno se registró con `prompt_version = customer-agent.v1` porque el E2E corrió **antes** de que se
  fusionaran PR #20 (22:38 UTC) y PR #21 (22:57 UTC): el contenido del prompt era el de
  `customer-agent.v1.md` de entonces y la respuesta fue larga (780 caracteres). El cambio de brevedad es
  hoy `customer-agent.v2.md` (PR #20 lo introdujo; PR #21 lo versionó). La evidencia acredita el criterio
  E2E, no el prompt actual.
- La fila `USER` de las 16:11, **sin** `ASSISTANT` asociado y **sin** fila en `turn_log`, es un intento
  anterior del mismo turno. Corrobora **A-08**: un turno que falla no se registra en `turn_log`.

### Cómo se recuperó (reproducible, solo lectura)

```bash
docker run -d --name ia_pg_ro \
  -e POSTGRES_USER=saaspa -e POSTGRES_PASSWORD=saaspa -e POSTGRES_DB=saaspa_ia \
  -v saaspa-ia_ia_pg_data:/var/lib/postgresql/data pgvector/pgvector:pg15
docker exec ia_pg_ro psql -U saaspa -d saaspa_ia \
  -c "select turn_id, tenant_id, channel, agent, prompt_version, model,
             tokens_in, tokens_out, latency_ms, created_at,
             user_id is not null as con_user, role
      from ia.turn_log order by id desc limit 12;"
docker exec ia_pg_ro psql -U saaspa -d saaspa_ia \
  -c "select turn_id, tenant_id, tool_name, status, latency_ms, created_at,
             jsonb_typeof(args_json) as args_tipo, jsonb_typeof(result_json) as result_tipo
      from ia.tool_call_log order by id desc limit 12;"
docker exec ia_pg_ro psql -U saaspa -d saaspa_ia \
  -c "select type, timestamp, sequence_id, length(content) as chars
      from ia.spring_ai_chat_memory order by sequence_id;"
docker stop ia_pg_ro && docker rm ia_pg_ro
```

## 3. Reconciliación: borrador vs. código real (`saaspa-backend@develop@9fc8b12`)

### 3.1 Turn token (ADR 0006)

| Aspecto | Contrato borrador | Real en el backend | Resultado |
|---|---|---|---|
| Firma | ES256 (P-256), `kid` | ES256 (P-256), `keyid` desde `TURN_TOKEN_KID` | Cumplido |
| Claims | `iss`, `aud`, `iat`, `exp`, `jti`, `tenantId`, `conversationId`, `channel`, `agent`, `userId?`, `role?` | Idénticos; `jti = turnId` | Cumplido |
| `iss` / `aud` | `saaspa-ia` y emisor opcional | `TURN_TOKEN_ISSUER` (por defecto `saaspa-backend`) y `TURN_TOKEN_AUDIENCE` (por defecto `saaspa-ia`) | Cumplido |
| Vida | Minutos | `TURN_TOKEN_TTL_SECONDS` (por defecto **300 s**) | Cumplido |
| Material de la clave | Privada en NestJS, pública en este servicio | `TURN_TOKEN_PRIVATE_KEY` (PKCS#8 PEM en base64 o PEM con `\n` escapados); la pública se deriva de la privada | Cumplido (este servicio recibe la pública por `kid`) |
| Rotación | Dos claves por `kid` | **Una sola** ranura: el `kid` del token debe coincidir con `TURN_TOKEN_KID` o se responde 401 | **Pendiente en el backend**: la rotación sin cortar el servicio solo existe de nuestro lado (T1.1) |

### 3.2 API interna (`internal-api`)

| Aspecto | Real en el backend | Contrato resultante |
|---|---|---|
| Guard | `InternalAuthGuard` en los dos controladores: `X-Internal-Api-Key` (comparación en tiempo constante) **y** turn token en `Authorization: Bearer`; autoriza con la identidad del token | Igual; se añade **403** (tenant del token ≠ `TENANT_ID`) |
| `@SkipThrottle()` | Presente en `InternalServicesController` y `InternalAvailabilityController` | Pedido 2 cumplido |
| `GET /api/internal/v1/services` | `page` (≥1), `limit` (1–100), `featured` validado como texto `'true'`/`'false'`; devuelve `data/total/page/limit/totalPages` con el catálogo activo mapeado al contrato | Igual; `featured` documentado como `string` enumerado |
| `GET /api/internal/v1/services/{idOrSlug}` | Acepta UUID **o** slug; 404 si no existe | Parámetro renombrado a `idOrSlug` |
| `GET /api/internal/v1/availability?serviceId&date` | `serviceId` acepta UUID o slug; `date` debe ser `YYYY-MM-DD` **real** (si no, 400); devuelve `{serviceId (UUID), date, timezone, slots[{start,end}]}` | Igual; el offset explícito ya está implementado |
| Offset de las franjas | Calculado con `Intl.DateTimeFormat` + `longOffset` (no depende del `tzdata` del contenedor) | **C-02 resuelto**: la duda de la TZ del contenedor desaparece |
| Auditoría de llamadas internas | `AuditService` existe en el backend, pero `src/modules/internal/` **no** registra auditoría; el guard deja el turno en `request.turn` | Pedido sigue **abierto** (la traza de servicio no se audita) |
| Escrituras (bookings), reportes, `identity/resolve` | No existen | Siguen como pedidos de Fase 2/3/4 |

### 3.3 Chat del backend a este servicio (`chat-api`)

- Implementado en `src/modules/chat/ia-bot.client.ts`: `POST {IA_BOT_URL}/api/v1/chat`, cabeceras
  `X-Internal-Api-Key: IA_BOT_API_KEY` + `Authorization: Bearer <turn token>`, timeout
  `IA_BOT_TIMEOUT_MS` (20000 ms).
- El cuerpo coincide campo a campo con el contrato: `turnId`, `tenantId`, `conversationId`, `channel`,
  `agent`, `identity{kind,userId?,role?}`, `message{text}`, `locale = es-CO`, `timezone`
  (`TENANT_TIMEZONE`), `now` con offset explícito.
- Mapeo de errores del backend: 400 → 400, 501 → 501, timeout → **504**, cualquier otro (incluidos
  nuestros 401/403/502) → **502**. Nota de riesgo: un 403 de la validación de tenant llega al widget
  como 502.

### 3.4 Chat web público (`web-chat-api`)

- Implementado en `src/modules/chat/chat.controller.ts` + `chat.service.ts` (`POST /api/chat`,
  `@Public()`, 200).
- Anti-abuso real: `@Throttle 20 req/60 s` por IP; longitud máxima **1000** caracteres → **413**; tope de
  **30 mensajes por hora** por sesión anónima → **429**; cuerpo con `whitelist` +
  `forbidNonWhitelisted` → **400** ante campos desconocidos.
- Sesión: cookie `kamerinos_chat_session` (httpOnly, `sameSite=lax`, 7 días, `secure` en producción); la
  clave de sesión se guarda con hash SHA-256 y `conversationId` es aleatorio de 16 bytes en hex
  (32 caracteres) cuando no se envía.
- **403** si el `conversationId` no pertenece a la sesión o al tenant.
- Identidad logueada por la cookie `kamerinos_access_token`; una cookie inválida o caducada degrada a
  anónimo (no es error).
- Respuesta: `{conversationId, turnId, reply{text, links?}, handoff?, usage{model?, tokensIn, tokensOut}}`;
  `handoff` se **omite** cuando no se pide.

## 4. Decisiones y hallazgos que quedan cerrados

- **A-10 (estado del handoff):** implementado en el backend con la opción (a). La conversación se persiste
  en la tabla `chat_conversation_states` (migración `20260926180000_add_chat_conversation_state`) con
  `handoffActive` y `handoffReason`; cuando está activo, NestJS responde el texto canónico
  (`HANDOFF_ACTIVE_MESSAGE`) **sin** llamar a este servicio, así que el bot no puede retomar la
  conversación. Este servicio sigue sin guardar estado de sesión.
- **C-02 (zona horaria):** resuelto por el lado del backend (offset explícito con `Intl/ICU`); en este
  servicio se mantiene que la autoridad es `saaspa.tenant.timezone` y que `timezone`/`now` del cuerpo son
  contexto informativo para el modelo.
- **A-08 (turnos fallidos sin registro):** corroborado con la evidencia del E2E. Sigue abierto (Fase 2).

## 5. Pendientes en el backend (no bloquean la Fase 1)

1. Segunda ranura de `kid` para rotar la clave del turn token sin cortar el servicio.
2. Auditoría de las llamadas internas (actor de servicio).
3. Pedidos de Fase 2 (bookings con `Idempotency-Key` y deep-link de pago), Fase 3 (reportes) y Fase 4
   (`identity/resolve`).

## 6. Límites de esta validación

- No se levantaron el backend ni este servicio: la reconciliación es de **código leído** más la
  **evidencia persistida** del run manual. No se repitió el turno.
- El run manual ocurrió antes de PR #20 y PR #21, por lo que acredita el criterio E2E de la Fase 1, no el
  comportamiento del prompt actual (`customer-agent.v2`).
- Sin PII en este informe (R8) y sin datos de conversaciones reales.

