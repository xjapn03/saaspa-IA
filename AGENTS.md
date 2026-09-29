# AGENTS.md — saaspa-IA

> **Lee este archivo completo antes de tocar nada.** Es la fuente de verdad para cualquier agente de IA
> (o persona) que trabaje en este repositorio. Si algo aquí contradice `README.md` o el roadmap largo,
> **gana este archivo y las ADRs en `docs/adr/`**. Al terminar cada tarea, actualiza la sección
> [12. Checklist de progreso](#12-checklist-de-progreso) y el [registro de cambios](#14-registro-de-cambios).

Última actualización: 2026-09-26

---

## 1. Qué es este proyecto

**saaspa-IA** es el "cerebro" conversacional de **Kamerinos SPA Bogotá** (centro de estética y bienestar).
Es un servicio **Java 21 + Spring Boot 4.1 + Spring AI 2.0** que:

- conversa con clientas (WhatsApp y chat web) y con la administración (chat del dashboard);
- decide qué herramienta usar y redacta las respuestas;
- **no es dueño de datos ni de lógica de negocio**: agenda, precios, stock, pagos y reportes los calcula y valida
  `saaspa-backend` (NestJS), que este servicio consume por HTTP interno.

Objetivo doble: (1) un piloto real y útil para Kamerinos; (2) un proyecto de portfolio profesional
(multi-tenant, evaluado, medido en costos, seguro).

**Lo que NO se hace en este repo:** entrenar o fine-tunear modelos, correr LLMs locales en producción,
pagos en línea (los hace el backend con Wompi), lógica de agenda/precios/reportes, acceso directo a la
base de datos de negocio.

---

## 2. Repositorios y responsabilidades

| Repo | Rol |
|---|---|
| `saaspa-backend` | NestJS 11 + Prisma + PostgreSQL/pgvector + Redis. **Sistema de registro.** Auth (JWT, roles `CLIENTE`/`EMPLEADO`/`ADMIN`), webhook de WhatsApp, `ConversationState`, agenda con slot-locking en Redis, pagos Wompi, e-commerce, Google Calendar, Meta CAPI. Expone el API interno `/api/internal/*`. |
| `saaspa-frontend` | Next.js 16. Chat web (anónimo y logueado) y chat del dashboard. |
| `saaspa-IA` (**este**) | Cerebro de los agentes CLIENTAS y ADMIN. |
| `kamerinos-infra` | Docker Compose + Nginx. Añade el contenedor `ia-bot` a la red interna. |

**Regla:** desde este repo **nunca se modifican los otros repos** (ni commits, ni push, ni PRs allí).
Sí se pueden **leer** con `gh` en modo solo lectura para validar contratos (ver sección 9). Si se necesita un
endpoint nuevo en NestJS, se documenta en `docs/contracts/` y en la sección
[11. Pedidos a otros repos](#11-pedidos-a-otros-repos); la persona lo implementa en su repo.

---

## 3. Arquitectura y flujo de un turno

```text
 Clienta / Admin
      |
 WhatsApp | Chat web anónimo | Chat web logueado | Chat dashboard
      |
      v
 saaspa-backend (NestJS)  --- único punto de entrada de todos los canales
   - resuelve tenant, identidad y rol
   - elige agente (CLIENTAS | ADMIN)
   - emite un "turn token" firmado y de vida corta
      |
      |  POST {IA_BOT_URL}/api/v1/chat      (Authorization: servicio + turn token)
      v
 saaspa-IA (este repo)
   - ChatClient + Advisors + memoria + prompt versionado
   - si el modelo pide una herramienta:
        |
        |  {BACKEND_URL}/api/internal/v1/...   (turn token en cada llamada)
        v
      NestJS autoriza con la identidad DEL TOKEN, ejecuta y devuelve datos
   - devuelve: respuesta, enlaces, handoff, uso de tokens, fuentes
```

Principios:

1. **La IA conversa; el código decide.** Este servicio orquesta; NestJS calcula y valida.
2. **Precios, horarios, disponibilidad y stock NUNCA salen de RAG ni del modelo**: salen de herramientas.
   RAG es solo para texto descriptivo y políticas (Fase 4).
3. **Cada agente tiene solo sus herramientas.** El agente CLIENTAS no tiene herramientas de reporte.
4. **La identidad nunca la dicta el modelo** (ver regla R1).

### Canales, identidad y agente

| Canal | Identidad | Rol | Agente |
|---|---|---|---|
| WhatsApp | anónimo (identificado por `waId`/teléfono verificado por Meta) | — | CLIENTAS |
| Chat web (widget) | anónimo | — | CLIENTAS |
| Chat web | logueado | `CLIENTE` | CLIENTAS (ve sus citas/pedidos) |
| Chat dashboard | logueado | `EMPLEADO` | ADMIN (solo agenda) |
| Chat dashboard | logueado | `ADMIN` | ADMIN (ventas y reportes) |

---

## 4. Decisiones de arquitectura (ADRs)

Aceptadas: 0001 a 0005 (2026-09-23). Ver `docs/adr/`.

| ADR | Decisión |
|---|---|
| 0001 | Java 21 + Spring Boot 4 + Spring AI 2.0 como servicio separado |
| 0002 | NestJS = único gateway de canales y único ejecutor de herramientas |
| 0003 | Multi-tenancy híbrida: `tenant_id` en todo dato del agente y filtro obligatorio en RAG |
| 0004 | Herramientas vía API HTTP interno de NestJS, nunca a la BD directamente |
| 0005 | Identidad del cliente por teléfono (`waId` → `User`), con auto-creación política a definir |

**Escritas en la Fase 0** (2026-09-23) — ver `docs/adr/0006-turn-token-and-identity-propagation.md`,
`0007-memory-and-persistence.md` y `0008-write-tools-policy.md`:

| ADR | Tema | Decisión |
|---|---|---|
| 0006 | Propagación de identidad y autorización de herramientas | NestJS emite un **turn token** (JWT firmado, vida de minutos) con `tenantId`, `userId?`, `role?`, `conversationId`, `turnId`, `agent`. Java lo reenvía tal cual en cada llamada interna. NestJS **autoriza usando el token**, no campos que envíe Java ni argumentos del modelo. Se puede pasar al contexto de las herramientas con `ToolContext` de Spring AI (verificar en la doc). Alternativa más simple: headers + `INTERNAL_API_KEY`; se descarta por permitir suplantación si Java falla. |
| 0007 | Memoria y persistencia del agente | Ver [decisión D-MEM](#decisiones-abiertas). Esquema propio `ia` en PostgreSQL. Log durable de mensajes, tool calls y uso. |
| 0008 | Política de herramientas de escritura | Solo lectura primero; escritura tras feature flag; confirmación explícita de la clienta; **idempotencia** (`Idempotency-Key`); auditoría de cada tool call. |

**Aceptada después:** `docs/adr/0009-llm-timeouts-and-retry.md` (2026-09-25),
`docs/adr/0014-turn-deadline-ladder-and-correlation.md` (2026-09-26, ola 3 del triaje: ajusta los valores
de la escalera de plazos y añade la correlación del 504 y el registro del turno cortado) y
`docs/adr/0015-turn-log-outcomes-and-cost-basis.md` (2026-09-26, H-05 de la segunda revisión conjunta:
implementa el punto 3 de la ADR 0013, cierra el resto de A-08 que la 0014 dejó abierto y fija la base del
cómputo del guard de coste: `status` con `HANDOFF`/`ERROR`, `handoff_reason` y `error_code`) y
`docs/adr/0020-origin-scoped-cost-cap.md` (2026-09-26, la mitad de H-04 que cae de este lado: cuarto tope de
coste **por origen del turno** y el claim `clientIp`, con la variante tolerante como red de seguridad —el
backend ya lo emite desde su PR #84 y la activación está verificada en
`docs/contracts/h04-origin-claim-validation.md`—) y `docs/adr/0016-tenant-coupling-check.md` (2026-09-26,
ola 3 del triaje, J-06: comprobación del acople de tenant y zona horaria —aviso en el primer turno,
validación propia al arrancar y valores publicados en `/actuator/info`—) y
`docs/adr/0017-error-contract-and-client-facing-detail.md` (2026-09-26, J-07: la frontera entre el error
interno y el texto de la clienta: catálogo cerrado de textos, `code` estable y el motivo técnico al log).

**Ola 1 de la Fase 2** (2026-09-26 — ver el triaje conjunto, §3). **Las cuatro están Aceptadas**; la 0013 con el
destino ya decidido:

| ADR | Estado | Tema | Decisión |
|---|---|---|---|
| 0010 | **Aceptada** | Abuso y tope de coste | La mitad «sesión no falsificable» ya está resuelta en el backend (PR #76 fusionado: `trust proxy` de un salto y sesión anónima emitida y firmada por el servidor). Queda de este lado el **tope global por tenant** y el **coste por conversación**, con `ia.turn_log` como fuente de verdad y 429 al superar. |
| 0011 | **Aceptada** | Expiración de `PENDIENTE_PAGO` | Implementación **100 % de `saaspa-backend`**, ya hecha (PR #77: estado `EXPIRADA`, ventana de pago configurable y tope de reservas pendientes). Este repo aporta el contrato (el enum de `Booking.status` ya expone `EXPIRADA`) y el caso `B01-franja-liberada-por-expiracion` del dataset `eval/`. |
| 0012 | **Aceptada** | Identidad e idempotencia de escritura | Precisión de ADR 0008 y ADR 0006: el sujeto sale **siempre** de `turn.userId` (nunca del cuerpo), sin identidad no hay escritura (403, sin auto-creación) y la `Idempotency-Key` (construida por código, no por el modelo) hace que un reintento devuelva el mismo recurso. |
| 0013 | **Aceptada** | Handoff con destino y reversible | El estado sigue en NestJS (A-10a) y debe ser **reversible** y **auditable**. Destino decidido: **aviso por correo al staff** reutilizando el módulo de correo de `saaspa-backend` (SendGrid), **no WhatsApp** (fuera de la ventana de 24 h la API exige plantilla pre-aprobada por Meta). La bandeja del dashboard queda pospuesta hasta el widget (J-02). El backend **debe capturar el texto** del turno derivado: ni su base ni nuestra memoria lo guardan (evidencia en el ADR). |

**Correcciones aplicadas en la Fase 0** (2026-09-23):
- ADR 0003: eliminar la línea "Sustituye a ADR 0003 (single-tenant) — descartado antes de su publicación" (confunde).
- ADR 0005: añadir addendum: la identidad por teléfono **solo aplica a WhatsApp** (el número lo verifica Meta).
  En el **chat web anónimo nunca** se resuelve identidad por un teléfono que la persona teclea. La auto-creación de
  usuarios debe definir consentimiento y su efecto en Meta CAPI.

---

## 5. Reglas no negociables

**Seguridad e identidad**
- **R1.** El modelo nunca decide `tenantId`, `userId`, rol ni permisos. Salen del turn token / contexto de turno,
  jamás de argumentos de herramientas generados por el modelo.
- **R2.** Toda llamada a NestJS lleva el turn token. NestJS es quien autoriza.
- **R3.** El agente CLIENTAS solo ve datos de la clienta que escribe y del catálogo público. No tiene herramientas
  de reporte, aunque el usuario lo pida ("ignora tus instrucciones…").
- **R4.** El agente ADMIN es **solo lectura**. Las cifras las calcula NestJS; el modelo las explica.
  Nada de SQL generado por el modelo.
- **R5.** Filtro por `tenant_id` obligatorio y centralizado en todo dato propio (mensajes, tool_calls, uso,
  documentos/vectores, evaluación). Tests de aislamiento entre tenants.
- **R6.** Este servicio no toca la base de datos de negocio ni el esquema `public` del backend (Prisma).
  Solo su esquema propio `ia`.
- **R7.** Sin secretos en el repo. Todo por variables de entorno. `.env` en `.gitignore`.
- **R8.** No registrar PII en logs (teléfonos enmascarados; no volcar prompts completos en INFO).

**Comportamiento del agente**
- **R9.** Acciones que modifican datos (crear/reprogramar/cancelar cita, pedidos) requieren confirmación
  explícita de la clienta y son **idempotentes**. Empiezan detrás de un feature flag.
- **R10.** Temas sensibles de salud (alergias, embarazo, condiciones de la piel, medicación, reacciones,
  contraindicaciones) → el agente **no da consejo**; deriva a una profesional (handoff).
  Reclamos y cobros disputados → handoff.
- **R11.** Si no hay información suficiente (herramienta sin datos, RAG débil), el agente lo dice y ofrece handoff.
  Nunca inventa precios, horarios ni políticas.
- **R12.** El agente no cobra ni despacha: entrega **enlaces pre-diligenciados** a `/agendar` y `/shop` y el
  deep-link de pago Wompi que devuelve el backend.
- **R13.** Fechas relativas ("el jueves", "mañana"): inyectar en el prompt la fecha/hora actual y la zona
  horaria del tenant (`America/Bogota`) y **validar** las fechas en la herramienta.

**Calidad**
- **R14.** Ningún test del build normal llama a un LLM real (usar dobles). La evaluación con LLM real corre aparte
  (perfil `eval`, manual o programada), porque cuesta tokens.
- **R15.** Todo cambio de comportamiento del agente actualiza el dataset de evaluación en `eval/`.
- **R16.** Toda decisión de arquitectura nueva se registra como ADR antes de implementarla.
- **R17.** No verificar APIs "de memoria": Spring AI 2.0 es reciente y muchos tutoriales son de la serie 1.x.
  Confirmar nombres de artefactos y APIs en la documentación oficial o Maven Central.

---

## 6. Stack y versiones

| Elemento | Valor | Notas |
|---|---|---|
| Java | 21 | Spring Boot 4.1.x exige Java 17 mínimo y soporta hasta 26 |
| Spring Boot | **4.1.1** (o 4.1.x posterior) | No usar 4.0.x: los starters de Spring AI 2.0.0 se reportaron alineados con dependencias de Boot 4.1.0 |
| Spring AI | **2.0.1** vía `spring-ai-bom` | Starters: `spring-ai-starter-model-{proveedor}`, `spring-ai-starter-vector-store-{store}` |
| Build | Maven (`./mvnw`) | `pom.xml` en la raíz del repo |
| BD propia | PostgreSQL (esquema `ia`) + Flyway | pgvector desde la Fase 4 |
| Redis | **Retirado en T1.9** (no se usaba en `src/main`); vuelve en la **Fase 2** con uso real (idempotencia, rate limiting) | **No** es Redis Stack (ver trampas) |
| LLM | **DeepSeek** (`deepseek-flash`) detrás de `ChatClient` | Soporta tool calling (verificado). Intercambiable por configuración |
| Testing | JUnit (Boot 4), Testcontainers, WireMock, dataset `eval/` | Boot 4 usa Jackson 3 y JUnit 6 según sus guías de migración: verificar imports |
| CI | GitHub Actions: `./mvnw -B verify` | |

### Trampas conocidas de Spring AI 2.0 / Boot 4

- `RedisChatMemoryRepository` **requiere Redis Stack 7+** (Query Engine + RedisJSON). Un Redis normal no basta.
- `JdbcChatMemoryRepository` **descarta silenciosamente** los mensajes con tool calls y sus respuestas al guardar.
  Con la política de guardar solo turnos finales usuario/asistente no es un problema; hay que saberlo.
- El esquema de `PgVectorStore` **ya no se inicializa por defecto** (opt-in). Preferir crearlo con Flyway.
- Si cambia el modelo de embeddings hay que **reindexar** todo. Guardar nombre/versión del modelo en la metadata.
- Verificar si el proveedor de chat elegido ofrece embeddings; si no, hay que elegir otro proveedor o uno local.
- Boot 4 está modularizado: los nombres de starters cambiaron (p. ej. `spring-boot-starter-webmvc`,
  `spring-boot-starter-*-test`). Verificar el nombre del starter de Flyway y de WireMock antes de añadirlos.
- **Spring Security 7 / Boot 4:** el starter del resource server canónico es
  `spring-boot-starter-security-oauth2-resource-server` (el antiguo
  `spring-boot-starter-oauth2-resource-server` está deprecado en su favor) y las clases de
  configuración de cliente HTTP viven en el módulo `spring-boot-http-client`
  (`org.springframework.boot.http.client.HttpClientSettings` /
  `ClientHttpRequestFactoryBuilder.detect()`).
- **Verificación ES256:** `NimbusJwtDecoder.withPublicKey(...)` solo acepta **RSA**; para claves
  públicas EC hay que usar `withJwkSource(...)` con un `JWKSet` de `ECKey`
  (`new ECKey.Builder(Curve.P_256, ecPublicKey).keyID(kid)`) y restringir el algoritmo con
  `jwsAlgorithm(SignatureAlgorithm.ES256)`. Comprobado contra spring-security-oauth2-jose 7.1.1.
- **Codificación de rutas con `RestClient`:** `UriBuilder.path(valor)` trata el valor como plantilla,
  así que un segmento ya codificado se **codifica dos veces** (`%20` → `%2520`). Usar plantillas con
  variables (`/services/{servicio}` + `build(variables)`), que expanden y codifican una sola vez;
  `BackendClient` ya expone esa sobrecarga.
- **Spring AI 2.0, herramientas y memoria:** las herramientas de `ChatClient` no viajan en
  `Prompt.getOptions()` (las gestiona un advisor de tool calling), así que para probarlas sin LLM hay que
  usar `ToolCallbacks.from(bean)` y el contrato HTTP con WireMock. El id de conversación de la memoria se
  pasa por turno con el parámetro `ChatMemory.CONVERSATION_ID`; declarar un `ChatMemory` propio
  (`MessageWindowChatMemory`) fija la ventana sin depender de los valores por defecto de la
  autoconfiguración. Para auditar cada herramienta, envolver la `ToolCallback` en un decorador y
  registrarlas con `defaultTools(...)` (el resultado JSON trae el campo `ok` que indica si la
  herramienta respondió).
- **Spring AI 2.0, deprecaciones verificadas (2026-09-25):** en `ChatClient.Builder` y en
  `ChatClientRequestSpec` los tres `toolCallbacks(...)`/`defaultToolCallbacks(...)` están
  **deprecados y marcados para eliminar en 3.0.0**: el reemplazo es `tools(...)`/`defaultTools(...)`,
  que acepta tanto POJOs con `@Tool` como instancias de `ToolCallback` (las registra tal cual). En
  `ChatModel`, `getDefaultOptions()` está deprecado en favor de `getOptions()` (método `default`, no
  hace falta sobrescribirlo en los dobles de test).

---

## 7. Estado del repo

### Fase 0 — completada (2026-09-23)

Resuelto respecto del snapshot inicial de Initializr:

- `pom.xml`: `groupId=com.juanp`, `artifactId=saaspa-ia`, `name`/`description` con valor y sin bloques
  vacíos; añadidos `spring-ai-starter-model-deepseek`, `spring-boot-starter-jdbc`,
  `spring-boot-starter-flyway` + `flyway-database-postgresql`, WireMock 3.13.2 y
  `spring-ai-starter-model-chat-memory-repository-jdbc` (se retiró el de Redis).
- Paquete base `com.juanp.saaspa.ia`; clase `SaaspaIaApplication`; `HELP.md` eliminado.
- `application.yml` (+ perfil `local`), `.env.example` y `.gitignore` (ya ignora `.env`).
- `docker-compose.yml` de desarrollo (`pgvector/pgvector:pg15`; el Redis de desarrollo se retiró en T1.9/A-18).
- Flyway `V1__init_ia_schema.sql` (esquema `ia`: memoria JDBC, `turn_log`, `tool_call_log`).
- ADRs 0006, 0007 y 0008 escritas; 0003 y 0005 corregidas.
- Contratos `docs/contracts/*.openapi.yaml`; CI `.github/workflows/verify.yml`.
- README y roadmap alineados con este archivo.

### Discrepancias verificadas durante la Fase 0

- El esquema que trae Spring AI para la memoria usa `conversation_id VARCHAR(36)`; nuestro
  `conversationId` va namespaced (`{tenantId}:{channel}:{conversationId}`) → se amplió a
  `VARCHAR(255)` en la migración (Flyway es la fuente; `initialize-schema: never`).
- El `pom` de Initializr declaraba **dos** beans `@ServiceConnection(name="redis")` en
  `TestcontainersConfiguration` (redis y redis-stack) → ambigüedad; se dejó solo Redis estándar.
- El `.gitignore` de Initializr **no** ignoraba `.env` (violaba R7) → corregido.
- `spring-boot-starter-flyway` de Boot 4.1 **no** incluye el módulo de PostgreSQL: hay que añadir
  `flyway-database-postgresql` explícito. `org.wiremock:wiremock-standalone` no lo gestiona Boot
  (su `latest` es una beta) → versión explícita estable 3.13.2.
- En el equipo de desarrollo **no hay `javac` en el `PATH`** (solo un JRE 25 headless); el build
  local se hace con `JAVA_HOME` apuntando a un JDK (p. ej. el JBR de JetBrains, que trae `javac`).
  En CI se usa temurin 21.
- El remoto `origin` usa el alias `git@github-personal:xjapn03/saaspa-IA.git` y en la Fase 0 no había un
  `Host github-personal` en `~/.ssh/config`. Desde entonces la persona autenticó `gh` con protocolo SSH
  (ver sección 9). **Verificar** con `git ls-remote origin` antes de la primera operación remota.

### Fase 1 — completada (2026-09-26)

- T1.0–T1.10 hechos: endpoint de chat con verificación de servicio y turn token, cliente HTTP hacia NestJS,
  herramientas de lectura, agente CLIENTAS con prompt versionado (`customer-agent.v2`), memoria con ventana,
  handoff en código, registro durable en el esquema `ia`, dataset `eval/` con runner y pruebas de integración
  con Testcontainers.
- El criterio de aceptación **E2E** se cumplió con el backend real y el turno quedó registrado en
  `ia.turn_log` / `ia.tool_call_log`. Evidencia y reconciliación de los contratos:
  `docs/contracts/f1-e2e-validation.md`.
- Lo que falta para un despliegue real ya **no** es el contenedor (el `Dockerfile` está en este repo desde
  H-02, 2026-09-26): son los **valores y los números** en `kamerinos-infra` (ver §11.5 y la nota de H-02 en §7).

### Discrepancias verificadas durante T1.0 (2026-09-24)

> **Superado (2026-09-26):** todo lo de abajo era cierto antes de la Fase 1. Los pedidos 1 a 4 ya están
> implementados en `saaspa-backend@develop@9fc8b12`; el estado real y la evidencia están en
> `docs/contracts/f1-e2e-validation.md`. Se conserva como punto de partida.

Validación de los contratos contra `saaspa-backend` (rama `develop`, commit `ce41e487`), leyendo con `gh` en
solo lectura. Informe completo: `docs/contracts/t1.0-backend-validation.md`. Resumen:

- **No existe** `/api/internal/v1/*`: ningún módulo, controlador ni ruta con `internal` en el backend.
- **No existe turn token**: el único JWT es el de sesión de usuario (HS256, `JWT_SECRET`, claims
  `sub`/`email`/`role`, 15m/7d). El ADR 0006 está sin implementar.
- **No existe `tenantId`**: 0 ocurrencias en `prisma/schema.prisma`; el tenant efectivo es el despliegue.
- **No existe punto de entrada de chat** ni cliente HTTP hacia este servicio: `iaBot.url`/`iaBot.apiKey`
  están configurados y nunca se usan.
- Sí existen y se reutilizarán para leer: `GET /api/services/public…` (paginado, `price` numérico, detalle
  por `slug`) y `GET /api/bookings/slots?serviceId&date` (un día, instantes ISO UTC, 8–18 con
  `Date.setHours` en la TZ del contenedor).
- **Dos claves de servicio distintas**, una por dirección: `IA_BOT_API_KEY` (NestJS → IA) e
  `INTERNAL_API_KEY` (IA → NestJS). No se unifican.

### Estado verificado tras el E2E de la Fase 1 (2026-09-26)

Validación en solo lectura contra `saaspa-backend@develop@9fc8b12`, más la evidencia del turno real
persistida en `ia.turn_log`/`ia.tool_call_log`. Informe completo:
`docs/contracts/f1-e2e-validation.md`. Resumen:

- El **turn token** existe y es ES256 (P-256) con `kid`, `jti = turnId`, `iss` por defecto
  `saaspa-backend`, `aud` por defecto `saaspa-ia` y `TTL` por defecto 300 s. El backend tiene **una sola
  ranura de `kid`**: la rotación con dos claves solo está implementada en este servicio (T1.1).
- Existe `/api/internal/v1/*` con `InternalAuthGuard` (clave de servicio + turn token, **403** si el
  tenant del token no es el `TENANT_ID` del backend) y `@SkipThrottle()` en los dos controladores de
  Fase 1. La **auditoría de llamadas internas sigue pendiente** (`AuditService` existe, pero
  `src/modules/internal/` no lo usa).
- `/availability` ya devuelve **offset explícito** calculado con `Intl/ICU`: la duda de la TZ del
  contenedor (C-02) queda resuelta.
- Existe `POST /api/chat` público (throttle 20 req/60 s, 413 > 1000 caracteres, 429 con tope de 30
  mensajes/hora por sesión anónima, 403 si el `conversationId` no es de la sesión) y el backend llama a
  este servicio con `IA_BOT_API_KEY` + turn token, timeout de 20 s.
- El **estado del handoff vive en NestJS** (`chat_conversation_states.handoffActive`): con la
  conversación derivada, el backend responde su texto canónico **sin** llamar a este servicio (A-10a).
- El registro del E2E guardó `prompt_version = customer-agent.v1` (el run fue anterior a PR #20/#21) y
  dejó una fila `USER` sin `turn_log`: corrobora **A-08** (los turnos fallidos no se registran).

### Contenedor `ia-bot` (H-02, primera mitad hecha 2026-09-26)

La segunda revisión conjunta (`docs/reviews/2026-09-26-joint-integration-review-2.md`, hallazgo **H-02**)
detectó que `kamerinos-infra` construye el `ia-bot` desde `../saaspa-IA` con `dockerfile: Dockerfile` y que
este repo **no tenía `Dockerfile`**: el contenedor no se podía construir. Resuelto en la rama
`fix/h02-dockerfile-and-boot`:

- `Dockerfile` multi-etapa (build `maven:3.9-eclipse-temurin-21` → runtime `eclipse-temurin:21-jre`), usuario
  no root, `MaxRAMPercentage=75` porque el compose limita el contenedor a 768 MB, y `EXPOSE 8000`. Es el
  **patrón** del Dockerfile de `saaspa-backend` adaptado a Java (allí es Node), no una copia. Con su
  `.dockerignore` (cuidando de no excluir `src/main/resources/prompts/*.md`).
- **Segundo agujero del mismo hallazgo, encontrado al verificar:** las credenciales del datasource vivían solo
  en el perfil `local`, así que el contenedor habría arrancado sin URL de datasource (`Failed to configure a
  DataSource` + Flyway) **aunque el `Dockerfile` existiera**. `spring.datasource.url/username/password` pasan
  a `application.yml` con los nombres que inyecta el despliegue (`DATABASE_URL` / `DATABASE_USER` /
  `DATABASE_PASSWORD`, que Spring Boot **no** traduce por sí solo), y `application-local.yml` queda solo con el
  nivel de log (una sola fuente de verdad; si falta la variable en producción el arranque falla cerrado).
- Job `image` en el CI (`.github/workflows/verify.yml`, `docker build` después de `verify`) para que el fichero
  no vuelva a desaparecer o romperse sin que nadie lo note. El CI valida **construcción**; el **arranque** se
  validó a mano: `docker run` contra el Postgres del `docker-compose.yml` del repo, `/actuator/health` en `UP`
  y el esquema `ia` migrado por Flyway.
- **Lo que queda (y no es de este repo):** el `ia-bot` del compose fija `LLM_READ_TIMEOUT: 30s` /
  `LLM_TURN_DEADLINE: 35s` —la escalera invertida que ADR 0014 vino a corregir— y no trae `IA_BOT_TIMEOUT_MS`
  en el bloque `backend`; ninguna de las variables del acople está en su `.env.example`. Pedido en **11.5**.
  Mientras no se aplique, el despliegue **reintroduce J-04**.

---

## 8. Estructura objetivo y convenciones de código

```text
saaspa-IA/
├── AGENTS.md
├── README.md
├── pom.xml  mvnw  mvnw.cmd
├── Dockerfile  .dockerignore          # imagen del contenedor `ia-bot` (kamerinos-infra)
├── docker-compose.yml                 # solo desarrollo local (Postgres+pgvector; Redis vuelve en Fase 2)
├── .env.example
├── docs/
│   ├── adr/                           # 0001..000N
│   └── contracts/
│       ├── chat-api.openapi.yaml      # NestJS -> IA
│       └── internal-api.openapi.yaml  # IA -> NestJS (a implementar en saaspa-backend)
├── eval/                              # datasets de evaluación (jsonl) y runner
└── src/
    ├── main/java/com/juanp/saaspa/ia/
    │   ├── api/         # controladores (/api/v1/chat), DTOs (records), manejo de errores (ProblemDetail)
    │   ├── security/    # verificación de servicio y turn token
    │   ├── agent/       # customer/, admin/: ChatClient, prompts, políticas, handoff
    │   ├── tools/       # herramientas del agente (delgadas: validan y llaman al backend)
    │   ├── backend/     # cliente HTTP hacia NestJS (RestClient), timeouts, mapeo de errores
    │   ├── memory/      # configuración de ChatMemory
    │   ├── usage/       # tokens, latencia, costo estimado
    │   ├── tenant/      # contexto de tenant, filtro central
    │   ├── knowledge/   # RAG (Fase 4)
    │   └── config/      # @ConfigurationProperties (records)
    ├── main/resources/
    │   ├── application.yml  application-local.yml
    │   ├── prompts/     # prompts versionados (es-CO), con versión en el nombre
    │   └── db/migration/  # Flyway: V1__..., esquema `ia`
    └── test/java/...
```

Convenciones:
- **Idioma:** código, nombres y commits en **inglés**; documentación, ADRs y prompts del bot en **español (es-CO)**.
- Java 21: `record` para DTOs y configuración, inyección por constructor, sin Lombok.
- Paquete por funcionalidad. Herramientas delgadas: validar entrada, llamar al backend, devolver datos.
- Errores HTTP con `ProblemDetail`. Hilos virtuales activados (`spring.threads.virtual.enabled=true`).
- Timeouts explícitos en toda llamada externa (LLM y backend). `max_tokens` acotado por agente.
- Prompts versionados y con la versión registrada en cada turno.
- Migraciones Flyway inmutables; nunca editar una migración ya aplicada.
- `conversationId` de memoria con namespace: `{tenantId}:{channel}:{conversationId}`.

---

## 9. Git flow, GitHub y pull requests

### Entorno de desarrollo

- Sistema: **Fedora Linux** (equipo de la persona), shell bash. Se necesita un **JDK 21 completo** (con `javac`).
  Si falta, avisar a la persona; **no instalar paquetes del sistema** sin pedir permiso.
- **GitHub CLI (`gh`)** instalado y autenticado. Estado verificado por la persona el 2026-09-23
  (`gh auth status`): sesión activa de la cuenta **`xjapn03`** (keyring), protocolo de Git **ssh**, scopes
  `admin:public_key`, `gist`, `read:org` y `repo`.
- Con esa sesión el agente puede revisar el estado del repo y del CI, hacer push de sus ramas y abrir PRs,
  siguiendo las reglas de abajo. Los scopes `admin:public_key` y `gist` **no se usan**.
- Antes de la primera operación remota de la sesión: `gh auth status` y `git ls-remote origin`. Si `origin` falla
  por el alias `github-personal`, **no editar `~/.ssh/config` ni cambiar el remoto por cuenta propia**: informar y
  proponer `git remote set-url origin git@github.com:xjapn03/saaspa-IA.git`.
- **`mvnw` y el bit de ejecución:** en este equipo `core.fileMode=false`, así que git puede guardar `mvnw` como
  `100644` y el CI falla con *exit 126*; se corrige con `git update-index --chmod=+x mvnw` (queda `100755`).

### Ramas

| Rama | Uso |
|---|---|
| `main` | Siempre desplegable. Protegida. Solo recibe PRs de release desde `develop` (o `hotfix/*`), y solo cuando la persona lo pida. Cada fase terminada se etiqueta `vX.Y.0`. |
| `develop` | Integración. Solo recibe PRs desde ramas de trabajo. |
| `feature/<fase>-<slug>` | Trabajo nuevo, p. ej. `feature/f1-chat-endpoint`. Sale de `develop`. |
| `fix/<slug>` | Corrección de bugs. Sale de `develop`. |
| `docs/<slug>` · `chore/<slug>` | Documentación / mantenimiento. Salen de `develop`. |
| `hotfix/<slug>` | Urgente sobre `main`; luego se fusiona también a `develop`. |

### Commits

- [Conventional Commits](https://www.conventionalcommits.org/) en inglés: `feat:`, `fix:`, `docs:`, `test:`,
  `refactor:`, `chore:`, `build:`, `ci:`. Pequeños y enfocados. Ejemplo: `feat(tools): add listServices tool`.
- **Sin emojis** en commits, código ni PRs.
- Antes de cada commit: `./mvnw -B verify` en verde.
- Nunca commitear secretos, `.env`, dumps de datos reales ni conversaciones reales sin anonimizar.

### Qué puede y qué no puede hacer el agente con git y gh

**Permitido**
- **Leer:** `git fetch`, `git ls-remote`, `gh auth status`, `gh repo view`, `gh pr list|view|diff|checks|status`,
  `gh run list|view|watch`, `gh run view --log-failed`, `gh issue list|view`.
- **Leer otros repos (solo lectura)** para validar contratos, p. ej. `saaspa-backend`: `gh repo view`,
  `gh api` con método GET (contenidos, árboles), `gh search code`, o un clon superficial en un directorio
  temporal **fuera de este repo** (`gh repo clone <owner>/<repo> /tmp/... -- --depth 1`). Sin commits, push ni
  PRs en esos repos. La persona confirma el `owner/nombre` exacto si no es evidente (`gh repo list`).
- **Escribir en este repo:** crear ramas desde `develop`, hacer commits, `git push -u origin <rama-propia>`
  (solo `feature/*`, `fix/*`, `docs/*`, `chore/*`), volver a hacer push (sin force) para corregir CI o comentarios,
  `gh pr create --base develop`, y `gh pr edit` / `gh pr comment` en **sus propios** PRs.

**Prohibido sin instrucción explícita de la persona**
- **Fusionar PRs** de cualquier forma: `gh pr merge` (incluidos `--auto` y `--admin`), fusión por API, o `git merge`
  o push directo a `main` o `develop`. **La persona valida y fusiona manualmente.**
- Aprobar, cerrar o reabrir PRs (`gh pr review`, `gh pr close`, `gh pr reopen`).
- `--force` y `--force-with-lease`, rebase de ramas ya subidas, borrar ramas remotas, reescribir historia.
- Cambiar configuración del repo o de la cuenta: `gh repo edit|delete|rename|archive`, `gh secret`,
  `gh variable`, `gh ruleset`, protección de ramas, `gh ssh-key`, `gh gpg-key`, `gh gist`,
  `gh auth login|logout|refresh|token|setup-git`, y cualquier `gh api` de escritura (POST/PUT/PATCH/DELETE).
- Crear releases o tags, o lanzar workflows a mano (`gh workflow run`), salvo que la persona lo pida.
- **Imprimir, registrar o guardar el token de GitHub** (nunca `gh auth token` ni `gh auth status --show-token`).
  No pegar salidas con credenciales en commits, PRs, issues o logs.

### Ciclo de una rama

1. `git fetch origin && git checkout develop && git pull --ff-only`.
2. `git checkout -b feature/f1-<slug>`.
3. Commits atómicos, con `verify` verde antes de cada uno.
4. **El agente decide cuándo abrir el PR:** cuando el trabajo cumple la definición de "hecho" (o cierra el grupo de
   tareas del plan). No abre PRs por cada commit ni con trabajo sin verificar. Si tiene que dejar algo a medias,
   lo abre como borrador (`--draft`).
   `git push -u origin <rama>` y `gh pr create --base develop --title "..." --body-file /tmp/<archivo>.md`
   (el cuerpo va en un archivo temporal **fuera del repo**, para evitar problemas de comillas).
5. Revisar el CI: `gh pr checks <n>` o `gh run watch`. Si falla, corregir en la misma rama con un commit nuevo y push
   (sin force) y anotarlo en el PR.
6. **Detenerse y resumir a la persona.** El PR queda creado y abierto, **esperando validación y merge manual**
   (squash merge). El agente no fusiona ni da por hecho el merge.
7. Cuando la persona indique que fusionó: `git checkout develop && git pull --ff-only` y `git branch -d <rama>`.
   No empezar la siguiente rama hasta que el PR anterior esté fusionado, salvo autorización expresa
   (evita conflictos con el squash).

### Reglas de los pull requests

- **Idioma: inglés. Sin emojis** en título, descripción ni comentarios. Tono técnico y directo, sin marketing.
- **Título:** estilo Conventional Commits, en imperativo, máximo 72 caracteres, sin punto final.
  Ejemplo: `feat(chat): add POST /api/v1/chat with turn token validation`.
- **Descripción completa y útil**, con esta plantilla (también en `.github/pull_request_template.md`):

```markdown
## Summary
One or two sentences: what this PR does and why.

## Context
Relevant ADR, AGENTS.md task IDs (for example T1.2) and any constraint or decision behind the change.

## Changes
- Main changes, grouped by area.

## Testing
- Commands run (for example `./mvnw -B verify`) and their results.
- New or updated tests and what they cover.
- What was not tested and why.

## AGENTS.md checklist
- [x] Exact checklist lines completed by this PR

## Risks and notes
Security, data or cost implications, contract changes, follow-ups and open questions.
```

- **Un PR = una tarea del checklist o un grupo coherente de tareas** definido en el plan de la fase, con commits
  atómicos por tarea. Pequeños y revisables.
- Base siempre `develop`. Nada de secretos, PII ni conversaciones reales en el título, la descripción o los comentarios.
- Los cambios de contrato se mencionan en "Risks and notes" y en la sección 11 de este archivo.

### Protección de ramas (la configura la persona, no el agente)

Recomendado: PR obligatorio hacia `develop` y `main`, check `verify` obligatorio, sin push directo y squash merge.

### Definición de "hecho" (para cada tarea)
- [ ] Compila y `./mvnw -B verify` en verde
- [ ] Tests nuevos/actualizados (unitarios, y contrato o Testcontainers cuando aplique)
- [ ] Reglas R1–R17 respetadas
- [ ] Dataset `eval/` actualizado si cambió el comportamiento del agente
- [ ] Documentación/ADR/contratos actualizados
- [ ] Checklist y registro de cambios de este archivo actualizados
- [ ] Rama subida, PR abierto contra `develop` (en inglés, sin emojis, con la plantilla) y CI en verde

---

## 10. Fases

Transversal desde la Fase 1: registro de tokens por turno, dataset de evaluación que crece en cada fase,
`tenant_id` en todo, tests de aislamiento, y **solo lectura antes que escritura**.

### Fase 0 — Alineación y contratos
Objetivo: dejar el repo coherente y los contratos definidos antes de escribir lógica.
- Corregir `pom.xml` y estructura; añadir CI, `docker-compose.yml`, `.env.example`, Flyway `ia`.
- Escribir ADR 0006, 0007, 0008 y corregir 0003/0005.
- Borradores OpenAPI en `docs/contracts/`.
- Alinear README y roadmap con este archivo.
- **Criterio de aceptación:** `./mvnw -B verify` verde, `/actuator/health` en UP, ADRs y contratos revisados por la persona.

### Fase 1 — Cerebro mínimo + chat web anónimo (solo lectura)
- **T1.0 Validar contratos contra el código real de `saaspa-backend`**, leyéndolo con `gh` en solo lectura:
  qué endpoints internos existen, cómo se emite hoy el JWT, forma de servicios y disponibilidad. Registrar las
  diferencias en `docs/contracts/` y en "Pedidos a otros repos". No modificar el backend.
- `POST /api/v1/chat`: autenticación de servicio + verificación del turn token, validación, `ProblemDetail`.
- Agente CLIENTAS con `ChatClient`, prompt versionado en es-CO (`customer-agent.v2`, antes v1), memoria (ver
  D-MEM) y ventana corta.
- Herramientas de lectura: `listarServicios`, `consultarServicio`, `consultarDisponibilidad` (cliente HTTP hacia el
  backend, probado con WireMock).
- Reglas de handoff y de temas sensibles en el prompt y en pruebas.
- Registro durable de mensajes, tool calls y uso de tokens en el esquema `ia`.
- Dataset `eval/customer-agent.v1.jsonl` (10–15 casos) y runner.
- **Criterio de aceptación:** una consulta por chat web anónimo devuelve el precio correcto obtenido de la herramienta;
  nunca inventa precios; un tema sensible deriva a handoff; los tokens quedan registrados; tests de contrato en verde.
  **Cumplido el 2026-09-26** con el backend real (turn token, tenant, herramienta y LLM reales): evidencia en
  `docs/contracts/f1-e2e-validation.md`.

### Fase 2 — Agenda por chat + cliente logueado
- Herramientas de escritura con confirmación e idempotencia: `crearCita` (queda en `PENDIENTE_PAGO` y devuelve el
  deep-link de pago), `reprogramarCita`, `cancelarCita`; `misCitas` para `CLIENTE` logueado.
- Enlaces pre-diligenciados a `/agendar` y `/shop`.
- Feature flag `ia.tools.write.enabled`, apagado por defecto.
- Tests de idempotencia y de fechas relativas.
- **Criterio de aceptación:** una clienta logueada agenda, reprograma y cancela por chat sin duplicar citas ante reintentos.

### Fase 3 — Agente ADMIN + reportes
- Agente ADMIN para `ADMIN` y `EMPLEADO`, con permisos por rol; solo lectura.
- Herramientas de reporte que llaman al backend: ventas por período, servicios más solicitados, productos más
  vendidos, citas del día, stock bajo.
- Auditoría de cada consulta; registro de preguntas no soportadas.
- **Criterio de aceptación:** "¿cuánto vendimos hoy?" devuelve la cifra exacta del backend; `EMPLEADO` no accede a ventas.

### Fase 4 — WhatsApp + RAG
- Resolución de identidad por `waId`/teléfono según ADR 0005 (con su addendum).
- RAG multi-tenant con pgvector: ingesta de documentos, filtro obligatorio por tenant, umbral de similitud, citas
  de fuente, mensaje de "no sé". Decidir proveedor de embeddings.
- **Criterio de aceptación:** test de aislamiento verde; el agente responde políticas y cuidados solo con los
  documentos del tenant.

### Fase 5 — Evaluación, costos y piloto
- LLM-as-a-Judge (programado o manual, no en cada build), umbral de calidad.
- Métricas y paneles de costo por conversación y por tenant; límites y rate limiting.
- Piloto con Kamerinos: métricas de éxito, documentación, demo, README final.
- **Criterio de aceptación:** reporte de evaluación reproducible; costo por conversación medido; piloto en marcha.

### Fuera de alcance
Fine-tuning, modelo local en producción, pagos en línea desde este servicio, agente ADMIN por WhatsApp,
recordatorios/promociones proactivas (requieren consentimiento y plantillas de Meta: revisar reglas vigentes antes).

---

## 11. Pedidos a otros repos

**Estado (2026-09-26):** los pedidos **1 a 4 están implementados** en `saaspa-backend@develop@9fc8b12`
(turn token + guard, `/api/internal/v1/*` de Fase 1, `POST /api/chat` público y las variables de entorno);
el criterio E2E de la Fase 1 pasó y la evidencia está en `docs/contracts/f1-e2e-validation.md`.
Sigue **pendiente en el backend**: la auditoría de las llamadas internas, la segunda ranura de `kid` para
rotar la clave del turn token y los pedidos de Fase 2 en adelante.

Los contratos se validaron por primera vez contra `develop@ce41e487` (2026-09-24; informe en
`docs/contracts/t1.0-backend-validation.md`, superado) y se reconciliaron después contra
`develop@9fc8b12` (informe de cierre en `docs/contracts/f1-e2e-validation.md`). Los pedidos van **en
orden de dependencia**.

### 11.1 Turn token ES256 + guard (bloquea lo demás) — IMPLEMENTADO

- Estado: implementado en `saaspa-backend@develop@9fc8b12` (`src/modules/internal/turn-token.service.ts`
  y `guards/internal-auth.guard.ts`). Reconciliado el 2026-09-26
  (`docs/contracts/f1-e2e-validation.md`).
- Queda pendiente en el backend la **segunda ranura de `kid`**: hoy solo se acepta el `kid` configurado,
  así que rotar la clave sin cortar el servicio todavía no es posible desde el lado de NestJS.

- Par de claves asimétrico **ES256 (P-256, PEM)** con cabecera `kid`; la privada vive en NestJS y la pública se
  entrega a este servicio en `TURN_TOKEN_KEY_CURRENT_PUBLIC_KEY` / `TURN_TOKEN_KEY_PREVIOUS_PUBLIC_KEY`
  (implementado en T1.1: dos ranuras por `kid` para rotar sin cortar el servicio; el material va en base64
  o PEM).
- Claims mínimos: `iss`, `aud` (`saaspa-ia`), `iat`, `exp` corta (minutos), `jti` = `turnId`, `tenantId`,
  `conversationId`, `channel`, `agent`, `userId?`, `role?`.
- **Identidad (A-11):** `waId` **no** viaja en el cuerpo de `chat-api`. La identidad llega solo como
  `userId`/`role` firmados en el turn token. Si en la Fase 4 hace falta resolver `waId`, lo hace NestJS por su
  lado (ADR 0005) y firma el resultado (`userId`) en el token; nunca lo manda en el cuerpo.
- **Autoridad de la zona horaria (C-02):** `saaspa-IA` es la autoridad de `timezone` (la del tenant,
  `saaspa.tenant.timezone`). Los campos `timezone`/`now` del cuerpo de `chat-api` son **contexto informativo
  para el LLM**, no la fuente de verdad: la validación de fechas y de disponibilidad usa la zona del tenant
  de este servicio.
- Guard dedicado en las rutas internas: autoriza con la identidad **del token**, nunca con parámetros de la
  petición ni con argumentos generados por el modelo.
- Este servicio reenvía el turn token tal cual en cada llamada interna.

### 11.2 `/api/internal/v1/*` con `@SkipThrottle` — Fase 1 IMPLEMENTADA

Contrato: `docs/contracts/internal-api.openapi.yaml`. Autenticación: `X-Internal-Api-Key` con el valor de
`INTERNAL_API_KEY` + `Authorization: Bearer <turn token>`.

- Fase 1 (implementada): `GET /services` (paginado, espejo de `/api/services/public`),
  `GET /services/{idOrSlug}` (UUID o slug) y `GET /availability?serviceId&date`.
- **Hecho:** `/availability` **ya** devuelve offset explícito y el campo `timezone`, calculado con
  `Intl.DateTimeFormat`/`longOffset` (no depende del `tzdata` del contenedor): el riesgo de TZ del informe
  T1.0 queda cerrado.
- **Hecho:** `@SkipThrottle()` está aplicado en los dos controladores internos de Fase 1. **Pendiente:** la
  auditoría de las llamadas internas (`AuditService` existe, pero `src/modules/internal/` no lo usa).
- Fase 2: `POST /bookings` con `Idempotency-Key`, `PATCH`/`DELETE /bookings/{id}` y `GET /me/bookings`.
  Fase 3: reportes. Fase 4: NestJS resuelve el `waId` de WhatsApp por su lado (ADR 0005) y firma el
  `userId` en el turn token; este servicio no pide resolverlo (A-11).

### 11.3 `POST /api/chat` público (chat web) — IMPLEMENTADO

Contrato: `docs/contracts/web-chat-api.openapi.yaml`.

- Único punto de entrada del canal web (anónimo y logueado): resuelve `tenantId`, `channel`, `agent`, rol e
  identidad en el servidor, emite el turn token y llama a `POST {IA_BOT_URL}/api/v1/chat`.
- El cuerpo del frontend solo lleva `message` y `conversationId`; el `conversationId` anónimo es **aleatorio de
  128 bits** (impredecible) y está atado a la sesión. Implementado: 16 bytes en hex (32 caracteres), cookie
  `kamerinos_chat_session` (httpOnly, 7 días) con hash SHA-256 de la clave de sesión guardado en la base de
  datos, y **403** si el `conversationId` no pertenece a la sesión.
- **Estado del handoff (A-10a, implementado):** NestJS **mantiene el estado del handoff por conversación** en
  la tabla `chat_conversation_states` (`handoffActive` + `handoffReason`): recuerda si una conversación quedó
  en handoff y **no deja que el bot la retome**; en ese caso responde su texto canónico
  (`HANDOFF_ACTIVE_MESSAGE`) **sin llamar** a este servicio. Este servicio solo informa de
  `handoff.requested`/`reason` en la respuesta; no guarda estado de sesión.
- **Anti-abuso:** throttling por IP y por sesión, longitud máxima de mensaje, tope de mensajes por sesión
  anónima y **sin PII en logs**.

**`POST /api/v1/chat`** (NestJS → IA) — contrato: `docs/contracts/chat-api.openapi.yaml`

```json
{
  "turnId": "uuid",
  "tenantId": "kamerinos",
  "conversationId": "string",
  "channel": "WHATSAPP | WEB_WIDGET | WEB_LOGGED | DASHBOARD",
  "agent": "CLIENTAS | ADMIN",
  "identity": { "kind": "ANONYMOUS | USER", "userId": "string?", "role": "CLIENTE | EMPLEADO | ADMIN | null" },
  "message": { "text": "string" },
  "locale": "es-CO",
  "timezone": "America/Bogota",
  "now": "2026-09-23T10:00:00-05:00"
}
```

Respuesta:

```json
{
  "turnId": "uuid",
  "reply": { "text": "string", "links": [ { "label": "string", "url": "string" } ] },
  "handoff": { "requested": false, "reason": null },
  "usage": { "model": "string", "tokensIn": 0, "tokensOut": 0 },
  "sources": []
}
```

Autenticación: `X-Internal-Api-Key` con el valor de `IA_BOT_API_KEY` (NestJS → IA) + **turn token** (ADR 0006) en
`Authorization: Bearer`. Streaming (SSE) queda para después de la Fase 1.

### 11.4 Variables de entorno y claves de servicio — IMPLEMENTADO (quedan los valores de producción)

- Son **dos secretos distintos**, uno por dirección, y **no se unifican**: `IA_BOT_API_KEY` (NestJS → IA) e
  `INTERNAL_API_KEY` (IA → NestJS). Documentados en los `.env.example` de los dos repos.
- `saaspa-backend` ya tiene `IA_BOT_URL`, `IA_BOT_TIMEOUT_MS`, `IA_BOT_API_KEY`, `INTERNAL_API_KEY`,
  `TURN_TOKEN_PRIVATE_KEY` (PKCS#8 PEM EC P-256 en base64), `TURN_TOKEN_KID`, `TURN_TOKEN_ISSUER`
  (`saaspa-backend`), `TURN_TOKEN_AUDIENCE` (`saaspa-ia`), `TURN_TOKEN_TTL_SECONDS` (300), `TENANT_ID` y
  `TENANT_TIMEZONE`.
- `saaspa-IA` usa `TURN_TOKEN_KEY_CURRENT_PUBLIC_KEY`, `TURN_TOKEN_KEY_PREVIOUS_PUBLIC_KEY`,
  `TURN_TOKEN_AUDIENCE` (por defecto `saaspa-ia`), `TURN_TOKEN_ISSUER` (opcional), `INTERNAL_API_KEY` e
  `IA_BOT_API_KEY`; además `LLM_API_KEY`, `BACKEND_URL`, `DATABASE_URL` e `IA_TENANT_DEFAULT=kamerinos`.
  (`REDIS_URL` ya no aplica: Redis se retiró en T1.9/A-18 y volverá en la Fase 2 con uso real.)
- Lo que falta es operativo, no de contrato: los **valores reales** en el despliegue (kamerinos-infra).
- Los acoples con `saaspa-backend` (tenant, zona horaria y claves del turn token) están detallados, en espejo
  de su sección 6, en **11.6**.

### 11.5 Otros repos y contratos

- **kamerinos-infra (pendiente; pedido de H-02, 2026-09-26):** añadir el contenedor `ia-bot` a la red interna
  (el `Dockerfile` ya existe en este repo); PostgreSQL con pgvector y usuario con permisos solo sobre el
  esquema `ia`; variables de entorno de 11.4. Lo que falta **en su compose/`.env`**:
  (1) los tres números de la escalera de ADR 0014 —`LLM_READ_TIMEOUT: 10s` y `LLM_TURN_DEADLINE: 20s` en
  `ia-bot`, y `IA_BOT_TIMEOUT_MS: 25000` en `backend`, o **quitar los tres** y dejar los valores por defecto
  que anclan las pruebas—, porque hoy trae 30 s/35 s y **reintroduce J-04**;
  (2) las variables del acople que no están en su `.env.example` (`LLM_*`, `TURN_TOKEN_PUBLIC_KEY`, las claves
  de servicio…): Compose las resuelve a cadena vacía y el primer turno da 401/500;
  (3) `TZ` del contenedor (`America/Bogota`) coherente con `IA_TENANT_TIMEZONE` (J-06).
  El repositorio no se toca desde 2026-09-18: es la coordinación que falta **antes de abrir la Fase 2**.
  (La antigua duda de la TZ del contenedor del backend quedó resuelta: la disponibilidad se devuelve con
  offset explícito calculado con `Intl/ICU`.)
- **saaspa-backend (H-04, hecho el 2026-09-26):** el claim **`clientIp`** ya se emite (su PR #84, commit
  `a08985e`): es la IP que resuelve con `TRUSTED_PROXY_HOPS = 1` —**la misma** con la que agrupa su
  `Throttler`— y va como claim opcional del turn token. Con eso el **tope de coste por origen** (ADR 0020) está
  activo también en el canal anónimo, verificado con un turno real por HTTP y el volcado de la fila:
  `docs/contracts/h04-origin-claim-validation.md`. Queda como **pedido de baja prioridad** (no bloquea nada y
  **no se le ha pedido todavía**) que su registro del tope de coste no se limite a `scope === 'tenant'` y avise
  también de un `scope = origin`.
- **saaspa-backend (pedido de baja prioridad, J-06/ADR 0016, 2026-09-26 — *redactado y NO enviado*):** que
  consulte `/actuator/info` de este servicio al arrancar y compare `saaspa.tenant.id`/`saaspa.tenant.timezone`
  con su `TENANT_ID`/`TENANT_TIMEZONE`. **No es necesario** para detectar el desacople (este servicio ya avisa
  en el primer turno comparando la zona del cuerpo, ADR 0016) y tiene un coste: haría de `ia-bot` dependencia
  de **arranque** del backend y exigiría `healthcheck`/`depends_on` en `kamerinos-infra`. Se documenta para no
  perder la opción, no se le ha pedido.
- **saaspa-backend (pedido de baja prioridad, J-07/ADR 0017, 2026-09-26 — *redactado y NO enviado*):** que su
  `mapError`/`readProblemDetail` **registren** el `detail` y el `code` que enviamos (hoy solo leen `detail`, y
  el `scope` en el 429) junto al `turnId`, y que el texto que ve la clienta sea una decisión suya en vez de un
  reenvío del nuestro. Con nuestro `detail` ya apto para la clienta (ADR 0017) no es urgente: se documenta
  para no perder la opción. Si algún día el salón quiere otro tono, ese cambio es suyo.
- **`web-chat-api` (hecho, J-07/ADR 0017, 2026-09-26):** el contrato declaraba `Problem` (RFC 9457) mientras el
  backend respondía `{statusCode, message, error}`. Con el `problem+json` del backend ya fusionado, el
  contrato **se corrigió para confirmarlo**: `application/problem+json` en 400/403/413/429/502/504, el esquema
  con `type` (`about:blank`), `title` por estado, `status`, `detail`, `instance` y las extensiones del tope
  (`scope`/`measure`/`measured`/`limit`/`window`, solo en el 429 de coste), **verificado leyendo su código**
  (`src/common/filters/problem-details.filter.ts` y `src/common/http/problem-extensions.ts`), no su PR.
- **saaspa-backend (mejora opcional de baja prioridad, J-07/ADR 0017, 2026-09-26 — *redactado y NO enviado*):**
  su filtro de chat **no reenvía** el `code` que este servicio añade al `ProblemDetail` (copia solo las cinco
  extensiones documentadas, a propósito), así que el widget no puede distinguir el tipo de error por código y
  tiene que hacerlo por `status` y prosa. Que lo reenvíe (añadiéndolo a `PROBLEM_EXTENSIONS`) es una mejora
  pequeña y no bloquea nada: se documenta para no perderla. Mientras tanto, `code` existe **solo** en el tramo
  IA → backend (`chat-api`) y así está dicho en los dos contratos.
- **saaspa-frontend:** el chat web habla con `POST /api/chat` de NestJS, **nunca** directamente con este servicio.
- **Pedidos derivados del triaje conjunto (2026-09-26):** la lista consolidada por destino
  (`kamerinos-infra`, `saaspa-backend` y `saaspa-frontend`) está en el §5 de
  `docs/reviews/2026-09-26-joint-review-triage.md` (hallazgos J-01 a J-13).
- **Contratos:** `chat-api.openapi.yaml` (NestJS → IA, v0.6.3), `internal-api.openapi.yaml` (IA → NestJS, v0.3.1),
  `web-chat-api.openapi.yaml` (frontend → NestJS, v0.4.0), `f1-e2e-validation.md` (cierre de la Fase 1 y
  reconciliación), `h04-origin-claim-validation.md` (activación del tope por origen, 2026-09-26) y
  `t1.0-backend-validation.md` (informe histórico de T1.0, superado).

### 11.6 Acoplamiento de configuracion con saaspa-backend (critico)

Espejo, desde este lado del acople, de la tabla de acoplamientos de `saaspa-backend/AGENTS.md` sección 6;
cierra el pedido que ese repositorio anota en su sección 9. Los tres valores viajan por variables de entorno
del **mismo despliegue**, así que `kamerinos-infra` debe inyectarlos de forma coherente en los dos
contenedores (`backend` e `ia-bot`).

| Este repo | `saaspa-backend` | Consecuencia si no coinciden |
|---|---|---|
| `IA_TENANT_DEFAULT` (`saaspa.tenant.default`; default `kamerinos`) | `TENANT_ID` (default `kamerinos`) | Fallo **cerrado**: este servicio responde **403** a todos los turnos (A-03). `ChatController` compara el `tenantId` **firmado en el turn token** con `saaspa.tenant.default`. |
| `saaspa.tenant.timezone` (`IA_TENANT_TIMEZONE`; default `America/Bogota`) | `TENANT_TIMEZONE` (default `America/Bogota`) | Fallo **silencioso**: las fechas relativas del prompt y la disponibilidad real se interpretan en zonas distintas. No hay 403 ni error visible (C-02). |
| `TURN_TOKEN_KEY_CURRENT_PUBLIC_KEY` / `TURN_TOKEN_KEY_PREVIOUS_PUBLIC_KEY` (cada una con su `TURN_TOKEN_KEY_CURRENT_KID` / `_PREVIOUS_KID`) | `TURN_TOKEN_KID` / `TURN_TOKEN_PRIVATE_KEY` | **401** en todos los turnos: el `kid` del token no está entre las claves públicas configuradas (`TurnTokenDecoderFactory`). |
| Claim `clientIp` del turn token (opcional; ADR 0020) | `issueTurnToken` lo incluye con la IP resuelta por `TRUSTED_PROXY_HOPS = 1` (nunca una cabecera reenviada) — **hecho: su PR #84** | **Cubierto**: es la clave del tope de coste por origen. Si un despliegue dejara de emitirlo, el turno se registraría con `origin_hash` nulo y **sin** ese tope (volvería el riesgo de H-04 desde una sola IP): lo delatan el `WARN` del guard y `origin_hash IS NULL` en la tabla |

Notas del lado de este repo:

- **Comprobación del acople (J-06/ADR 0016):** este servicio compara la `timezone` que NestJS envía en cada
  turno con la suya y **avisa una vez** si no coinciden (nunca corta el turno), valida su propia zona **al
  arrancar** (una errata impide el arranque, con la variable en el mensaje) y publica
  `saaspa.tenant.id`/`saaspa.tenant.timezone` (con el prompt y el modelo) en `/actuator/info` como punto de
  comparación. El endpoint vive en la red interna: `ia-bot` no publica puertos.

- La identidad del turno (incluido `tenantId`) sale de los claims **firmados** del turn token, nunca del
  cuerpo de la petición ni de argumentos generados por el modelo (R1). El acople es con el valor con el que
  NestJS **firma**, no con lo que envíe el chat.
- `saaspa-IA` es la **autoridad de la zona horaria** (C-02): el prompt usa `saaspa.tenant.timezone` para las
  fechas relativas (`LocalDate.now(zoneId)`, R13) y el backend devuelve los instantes de
  `/api/internal/v1/availability` con offset explícito calculado con `Intl/ICU` desde `TENANT_TIMEZONE`. Un
  desacople aquí no rompe el servicio: responde con la fecha o la hora equivocada.
- El turn token es ES256 (ADR 0006). Este servicio solo lo verifica con la clave pública de su `kid` y lo
  reenvía tal cual en cada llamada interna. La rotación usa dos ranuras (`current`/`previous`), pero el
  backend todavía tiene **una sola ranura de `kid`** (T1.10: `docs/contracts/f1-e2e-validation.md`), así que
  hoy no se puede rotar la clave sin cortar el servicio.
- Verificación antes de desplegar: que `TENANT_ID` del `backend` y `IA_TENANT_DEFAULT` del `ia-bot` sean
  idénticos, y que `TENANT_TIMEZONE` y `saaspa.tenant.timezone` (hoy `IA_TENANT_TIMEZONE`) también.
  `./mvnw -B verify` cubre por pruebas el 403 del tenant y el 401 de las claves, pero **no** puede detectar
  un desacople de zona horaria entre dos despliegues.
- Ese límite (**advertencia manual, sin comprobación en runtime ni en CI**) está documentado en detalle, con
  propuestas aún no implementadas, en el hallazgo **J-06** de
  `docs/reviews/2026-09-26-joint-integration-review.md`.

---

## 12. Checklist de progreso

Marca con `[x]` al terminar y anota la fecha. No marques nada que no esté verificado con `./mvnw -B verify` o revisión.

### Hecho
- [x] ADR 0001–0005 redactadas y aceptadas (2026-09-23)
- [x] README reescrito para la arquitectura NestJS + Java (2026-09-23)
- [x] Proyecto generado con Spring Initializr: Boot 4.1.1, Java 21, BOM de Spring AI 2.0.1 (pom por corregir)
- [x] Auditoría inicial del `pom.xml` y del repo (hallazgos en la sección 7)
- [x] Informe de la revisión conjunta de integración (`saaspa-backend` ↔ `saaspa-IA`) versionado en
      `docs/reviews/2026-09-26-joint-integration-review.md` (2026-09-26; sus hallazgos J-01 a J-13 quedan por triar)
- [x] Acoplamiento con `saaspa-backend` documentado desde este lado y en espejo de su sección 6
      (`IA_TENANT_DEFAULT` ↔ `TENANT_ID` con 403, `saaspa.tenant.timezone` ↔ `TENANT_TIMEZONE` con fallo
      silencioso y las claves del turn token ↔ `TURN_TOKEN_KID` con 401) — ver **11.6**, 2026-09-26

### Entorno y flujo de trabajo
- [x] `gh` autenticado como `xjapn03` (SSH, scopes `repo`, `read:org`, `admin:public_key`, `gist`) y documentado (2026-09-23)
- [x] `git ls-remote origin` verificado (alias `github-personal` resuelto el 2026-09-23; sin push a `main`)
- [x] Ramas `main` y `develop` en el remoto; PR de `feature/f0-alineacion` fusionado en `develop` (2026-09-23)
- [x] Reglas de GitHub de la sección 9 incorporadas a `develop` (PR `docs/agents-github-workflow`, fusionado 2026-09-23)
- [x] `.github/pull_request_template.md` creada
- [ ] Protección de ramas configurada por la persona (PR obligatorio, check `verify`, sin push directo)
- [x] JDK 21 con `javac` instalado en el equipo (SDKMAN Temurin 21; documentado en el README — 2026-09-25)
- [x] `Dockerfile` + `.dockerignore` de la imagen `ia-bot` (patrón del de `saaspa-backend`, adaptado a
      Java 21/Spring Boot), job `image` en el CI y **credenciales del datasource en `application.yml`** (segundo
      agujero de H-02: sin esa traducción el contenedor no arrancaba ni con `Dockerfile`); validado con `docker
      build` + `docker run` contra el Postgres del `docker-compose.yml`, `/actuator/health` en `UP` y el esquema
      `ia` migrado por Flyway — rama `fix/h02-dockerfile-and-boot`, 2026-09-26
- [ ] H-02, lado de `kamerinos-infra` (**no** de este repo): escalera 10 s/20 s/25 s en su compose,
      `IA_BOT_TIMEOUT_MS` en el bloque `backend` y las variables del acople en su `.env.example` — pedido en §11.5

### Fase 0 — Alineación y contratos (completada 2026-09-23)
- [x] Ramas `main` y `develop` configuradas; trabajo en `feature/f0-alineacion`
- [x] `pom.xml` corregido: groupId/artifactId/name, sin bloques vacíos, starter de LLM, JDBC, Flyway, WireMock
- [x] Memoria: decisión D-MEM aplicada (`spring-ai-starter-model-chat-memory-repository-jdbc`)
- [x] Paquete `com.juanp.saaspa.ia`, clase `SaaspaIaApplication`, `HELP.md` eliminado
- [x] `application.yml`, `application-local.yml`, `.env.example`, `.gitignore` (ignora `.env`)
- [x] `docker-compose.yml` de desarrollo (`pgvector/pgvector:pg15`; el contenedor Redis se retiró en T1.9/A-18)
- [x] Migración Flyway `V1` con esquema `ia`
- [x] ADR 0006 (identidad y turn token)
- [x] ADR 0007 (memoria y persistencia)
- [x] ADR 0008 (herramientas de escritura)
- [x] Correcciones a ADR 0003 y 0005
- [x] `docs/contracts/chat-api.openapi.yaml` y `internal-api.openapi.yaml` (borradores)
- [x] README y roadmap alineados con este archivo
- [x] CI en GitHub Actions
- [x] `./mvnw -B verify` verde (JDK local; temurin 21 en CI) y `/actuator/health` en UP

### Fase 1 — Cerebro mínimo + chat web anónimo
- [x] T1.0 Contratos validados contra `saaspa-backend` (lectura con `gh`; `develop@ce41e487`, 2026-09-24)
- [x] Verificación de servicio y turn token (ES256, dos claves públicas por `kid`; `ProblemDetail` en 401 — T1.1, 2026-09-24)
- [x] `POST /api/v1/chat` con validación y `ProblemDetail` (contraste del cuerpo con el turn token → 400, agente no implementado → 501, backend caído → 502; contrato v0.3.0 — T1.4, 2026-09-24)
- [x] Agente CLIENTAS + prompt v1 (es-CO) + memoria con ventana (`customer-agent.v1.md` con fecha/zona del tenant, `ChatMemory` de ventana configurable, id `{tenantId}:{channel}:{conversationId}` — T1.5, 2026-09-24)
- [x] Cliente HTTP hacia NestJS con timeouts (`RestClient`, clave de servicio, turn token reenviado, mapeo de errores; probado con WireMock — T1.2, 2026-09-24)
- [x] Herramientas: `listarServicios`, `consultarServicio`, `consultarDisponibilidad` (`@Tool` en español, precios en COP preformateados, `ok=false` sin excepción — T1.3, 2026-09-24)
- [x] Handoff y política de temas sensibles (decisión en código: salud, reclamos y peticiones explícitas con motivo `HEALTH_TOPIC`/`COMPLAINT`/`EXPLICIT_REQUEST`; contrato chat-api v0.4.0 — T1.7, 2026-09-25)
- [x] Registro de mensajes, tool calls y tokens en `ia` (`ia.turn_log` con tenant/conversación/canal/agente/prompt/modelo/tokens/latencia, `ia.tool_call_log` con estado y JSON acotado, memoria JDBC para los mensajes; fallos de escritura no tumban el turno — T1.6, 2026-09-24)
- [x] Tests: unitarios, contrato (WireMock), Testcontainers Postgres y evaluador — 116 tests, 0 fallos (2026-09-26)
- [x] T1.8: dataset `eval/customer-agent.v1.jsonl` + runner (`CustomerAgentEvaluator`; en CI corre con un `ChatModel` guionizado, y la evaluación con LLM real corre aparte — R14) — 2026-09-25
- [x] T1.9: prueba de integración del turno con Testcontainers Postgres (turno íntegro persistido + ejecución real de `listarServicios` contra WireMock; `ChatModel` guionizado — R14) — 2026-09-26
- [x] **Criterio de aceptación E2E de la Fase 1 (cumplido 2026-09-26):** chat web anónimo que devuelve el
      precio real desde la herramienta, con el turn token, el tenant y el LLM reales, y con el turno y la
      tool call persistidos en `ia.turn_log` / `ia.tool_call_log`. Evidencia y reconciliación de contratos
      contra `saaspa-backend@develop@9fc8b12`: `docs/contracts/f1-e2e-validation.md` (T1.10, 2026-09-26).
- [x] T1.10 Cierre de la Fase 1 (docs): informe de E2E + reconciliación de los tres contratos (chat-api
      v0.5.0, internal-api v0.3.0, web-chat-api v0.2.0), AGENTS.md y README alineados — 2026-09-26

### Fase 2 — Agenda por chat + cliente logueado
- [ ] `crearCita`, `reprogramarCita`, `cancelarCita`, `misCitas`
- [ ] Idempotencia + confirmación explícita + feature flag
- [ ] Enlaces pre-diligenciados `/agendar` y `/shop`
- [ ] Tests de idempotencia y fechas relativas
- [ ] **Bloqueantes antes de la primera herramienta de escritura** (triaje conjunto del 2026-09-26): J-03
      abuso/coste (ADR 0010), B-01 expiro de `PENDIENTE_PAGO` y tope de reservas pendientes (ADR 0011),
      J-08+J-09 identidad desde el turn token e idempotencia (ADR 0012) y J-05 handoff con destino y
      reversible (ADR 0013, coordinado con el backend)
- [ ] **Misma pasada que los bloqueantes:** J-04 escalera de plazos y turno fallido registrado (ADR 0014,
      coordinado), J-06 acople comprobable y campos muertos (ADR 0016) y J-07 contrato de error (ADR 0017,
      coordinado)
- [x] **J-06 (ola 3, ADR 0016 — mitad de este repo):** el acople de zona horaria con NestJS se comprueba en el
      primer turno (avisa una vez, no corta: el campo es informativo), la propia zona se valida **al arrancar**
      (una errata ya no arranca el servicio en vez de dar 500 en el primer turno) y `/actuator/info` publica
      tenant, zona, prompt y modelo como punto de comparación; los campos `locale`/`timezone`/`now` siguen
      informativos y el contrato no cambia — rama `feature/actuator-info-and-tenant-coupling`, 2026-09-26
- [x] **J-07 (ola 3, ADR 0017 — mitad de este repo):** `api/ProblemCode` cierra el catálogo de errores: el
      `detail` es siempre apto para la clienta (el gateway lo reenvía tal cual), el motivo técnico va al log,
      el manejador de seguridad **registra** los rechazos (antes no dejaba rastro) y el `ProblemDetail` lleva un
      `code` estable; `chat-api` **v0.6.3** e `internal-api` **v0.3.1** dicen la verdad en cada dirección —
      rama `fix/j07-error-contract-and-client-facing-detail`, 2026-09-26
- [x] **J-07, resto (coordinado):** hecho el 2026-09-26. `web-chat-api` **v0.4.0** confirma `Problem` (RFC 9457)
      en 400/403/413/429/502/504, con las extensiones del tope y la aclaración de que `code` no viaja ahí
      (verificado contra el `ProblemDetailsFilter` ya fusionado del backend, no contra su PR). Queda como
      **mejora opcional de baja prioridad** (§11.5, sin enviar) que el backend reenvíe ese `code` al widget
- [x] **H-05 / A-08 (segunda revisión conjunta):** todo desenlace que llama al modelo deja fila en
      `ia.turn_log` y el turno derivado deja `HANDOFF` con su motivo, así que `turn_log` es la fuente de
      verdad que promete ADR 0010; el guard cuenta por "¿llamó al modelo?" (ADR 0015, migración `V3`,
      `handoff_reason`/`error_code`) — rama `fix/h05-turn-log-outcomes`, 2026-09-26
- [x] **H-04, mitad de este repo (segunda revisión conjunta):** cuarto tope de coste **por origen del turno**
      (60 turnos/h, `scope = origin`), con la clave `user:{userId}` o `ip:{clientIp}` y el hash `origin_hash`
      en `ia.turn_log` (migración `V4`, ADR 0020); contratos al día (429 preservado por el backend, `origin`
      en los `scope`, timeout real de 25000 ms) — rama `fix/h04-origin-scoped-cost-caps`, 2026-09-26
- [x] **H-04, activación del canal anónimo:** el claim `clientIp` ya lo emite `saaspa-backend` (PR #84) y el
      tope por origen está activo en el canal anónimo, verificado con un turno real por HTTP (200 / 200 / **429
      con `scope = origin`**) y el volcado de `ia.turn_log` (`origin_hash` ya no nulo, el mismo hash por origen y
      la IP ausente de la tabla) — `docs/contracts/h04-origin-claim-validation.md`, 2026-09-26. Se decide **no**
      endurecer a fallo cerrado ni recalibrar el 60/h hasta ver tráfico real
- [x] **HN-01 (tercera revisión conjunta, 2026-09-28):** la sal del hash de origen es obligatoria de verdad:
      fuera del perfil `local` el arranque **falla** si la sal falta, está en blanco, mide menos de 16
      caracteres o es la constante legada del repo (vetada y ya ausente como clave del código); en `local`
      sin sal se genera una aleatoria por proceso (`SecureRandom`) — addendum de la ADR 0020, rama
      `fix/hn01-origin-salt-fail-closed`. La variable para el despliegue es `IA_COST_GUARD_ORIGIN_SALT`
      (generar: `openssl rand -hex 32`)

### Fase 3 — Agente ADMIN + reportes
- [ ] Agente ADMIN con permisos por rol
- [ ] 5 herramientas de reporte
- [ ] Auditoría y preguntas no soportadas

### Fase 4 — WhatsApp + RAG
- [ ] Identidad por `waId` (ADR 0005 + addendum)
- [ ] Ingesta RAG, pgvector, filtro por tenant, umbral, citas
- [ ] Test de aislamiento entre tenants

### Fase 5 — Evaluación, costos y piloto
- [ ] LLM-as-a-Judge con umbral
- [ ] Costos por conversación/tenant y límites
- [ ] Piloto con Kamerinos y documentación final
- [ ] Triaje conjunto: J-01 (`ia-bot` en `kamerinos-infra` + validación de config al arrancar en el backend) y
      J-02 (widget del chat en `saaspa-frontend`) — bloquean el piloto y van **en paralelo a la Fase 2**
- [ ] Triaje conjunto, ola 4: J-10 (id de conversación y retención, con A-17), J-11 (dos secretos, una
      cabecera), J-12 (`turnId` de vuelta) y J-13 (casos de conformidad entre repos)

### Triaje de la revisión conjunta (2026-09-26)

- El mapeo completo de **J-01 a J-13** y del expiro de `PENDIENTE_PAGO` (etiquetado **B-01**, porque el informe
  no le dio ID) está en `docs/reviews/2026-09-26-joint-review-triage.md`: ola de prioridad, ADR propuesta
  (0010 a 0019), tarea de checklist y rama propuesta o marca de coordinación con el backend.
- Olas (fijadas por la persona): **1** bloqueantes de la escritura (J-03, B-01, J-08+J-09, J-05); **2** piloto
  en paralelo (J-01, J-02); **3** misma pasada (J-04, J-06, J-07); **4** pueden esperar (J-10 a J-13).
- ADR de la ola 1 en `docs/adr/`: `0010-abuse-and-cost-controls.md`, `0011-pending-payment-expiry.md`,
  `0012-write-identity-and-idempotency.md` y `0013-handoff-destination-and-reversibility.md` están
  **Aceptadas** (2026-09-26; la 0013 con el destino decidido: correo al staff con el módulo de `saaspa-backend`,
  **no** WhatsApp). **Reajuste de numeración (2026-09-26):** la **0015** se usó para los desenlaces del turno y
  la base del cómputo de coste (H-05, `fix/h05-turn-log-outcomes`), así que las reservas del triaje corren un
  número: J-06 pasa a **0016**, J-07 a **0017**, J-02 a **0018** y J-10 a **0019** (las reservas se anotan en
  el propio triaje). Las ADR 0016 a 0019 siguen como reserva; H-04 (segunda revisión) se trió con la
**ADR 0020**, fuera de esa reserva, para no renumerar por segunda vez.
- **Segunda revisión conjunta** (`docs/reviews/2026-09-26-joint-integration-review-2.md`, hallazgos H-01 a
  H-06): **sin triaje formal como documento**; cada hallazgo se resuelve en su rama y aquí queda su estado.
  De este repo: **H-02** (mitad propia hecha, §7), **H-05** (ADR 0015) y **H-04** (ADR 0020: el tope por
  origen, **activo y verificado** desde el PR #84 del backend). Ya fusionados en `saaspa-backend`: **H-04** (el
  429 llega como 429, su PR #83; el claim `clientIp`, su PR #84) y **H-03** (entrega y reintento del aviso de
  handoff, su PR #82). Sigue abierto: **H-01** (carrera pago ↔ expiración, con el estado nuevo `PAGO_TARDE`
  pendiente de consumidor en el dashboard) y **H-06** (consumidor del estado `EXPIRADA`), ambos de
  frontend/backend.
- **B-01 ya no tiene nada pendiente de este lado:** el backend implementó la expiración (PR #77, estado
  `EXPIRADA`), el contrato interno expone el estado y el caso `B01-franja-liberada-por-expiracion` está en el
  dataset `eval/` (real, no brecha).
- **J-04 (ola 3, ADR 0014):** la mitad de este repo está en la rama `fix/timeout-ladder` (`read-timeout` 10 s,
  `turn-deadline` 20 s, `turnId` en el 504 y fila `DEADLINE` en `ia.turn_log`); **no se fusiona** hasta que el
  backend tenga su `IA_BOT_TIMEOUT_MS` en 25 s y los dos números se revisen juntos.
- **J-03 (ola 1, ADR 0010):** la mitad de este repo está implementada en `feature/f2-per-tenant-cost-guard`
  (`usage/TurnCostGuard`: ventana de 1 h, 240 turnos y 1 M tokens por tenant, 30 turnos y 150 k tokens por
  conversación, medidos sobre `ia.turn_log`, con **429**); el 429 lo mapea hoy el backend a 502 (J-07). Sigue
  pendiente del backend el tope **por cuenta** al crear citas (Fase 2).
- **Ninguna rama de implementación está abierta**: el triaje es solo el mapeo.
- Los solapes con los hallazgos ya diferidos de §13 están cruzados en el §6 del triaje (J-03 ↔ A-06,
  J-04 ↔ A-08/A-15, J-05 ↔ C-13, J-06 ↔ C-02/A-13, J-10 ↔ A-17/A-04/A-05).

### Decisiones abiertas

| ID | Tema | Propuesta actual | Estado |
|---|---|---|---|
| D-MEM | Memoria de conversación | **JDBC en esquema `ia`** (Postgres), `initialize-schema: never`, tabla por Flyway V1, solo turnos finales, ventana 10, `conversationId={tenantId}:{channel}:{conversationId}`. Redis estándar para idempotencia/rate limiting/caché. | **Confirmada** (2026-09-23) — ver ADR 0007 |
| D-LLM | Proveedor de chat | **DeepSeek** (`deepseek-flash`), detrás de `ChatClient` | **Confirmada** (2026-09-23) — soporta tool calling |
| D-EMB | Proveedor de embeddings (Fase 4) | Por decidir (verificar si el proveedor de chat ofrece embeddings) | Abierta |
| D-PG | Versión de PostgreSQL del backend | **PostgreSQL 15** (backend usa `pgvector/pgvector:pg15`) | **Confirmada** (2026-09-23) |
| D-ID | groupId / artifactId / paquete | `com.juanp` / `saaspa-ia` / `com.juanp.saaspa.ia` (producto multi-tenant de portfolio; Kamerinos solo como `IA_TENANT_DEFAULT`) | **Confirmada** (2026-09-23) |
| D-JAVA | Java 21 vs 25 | 21 (ADR 0001); 25 también está soportado por Boot 4.1 | Mantener 21 |
| D-STREAM | Streaming SSE a través de NestJS | Después de la Fase 1 | Abierta |

---

## 13. Hallazgos diferidos (revisión Hermes, 2026-09-25)

> Revisión externa de solo lectura: `docs/reviews/2026-09-25-hermes-architecture-review.md`. Cada
> hallazgo se evalúa contra el código real antes de aceptarlo (R17). Lo ya implementado figura como
> resuelto; el resto queda aquí como backlog con severidad y la fase en la que se resolverá.
> Los hallazgos **J-01 a J-13** de la revisión conjunta del 2026-09-26 tienen su propio mapeo
> (`docs/reviews/2026-09-26-joint-review-triage.md`), y su §6 cruza los solapes con esta tabla para no
> duplicar entradas.

**Resueltos en esta tanda (no diferidos):**

- A-01 (timeouts/retry/deadline del LLM) — PR #13, ADR 0009.
- A-02 (R10 en código: handoff antes del modelo, texto canónico) — PR #12.
- A-03 (validación de `tenantId` con fallo cerrado, 403) — PR #14.
- C-01 (= A-03), C-05/C-06 (README y contrato) — PR #11; C-07 (checklist) — PR #15; C-09 (= A-01) — PR #13; C-10 (dataset `eval/` y runner) — PR de T1.8; A-18 (Redis sin uso retirado) y D-01 (caso de precio del dataset) — PR de T1.9; A-20 (cancelación real del deadline), A-22 (408/429 reintentables) y C-12 (contrato de tenant único) — PR de la segunda pasada.
- **Cierre de la Fase 1 (2026-09-26, T1.10):** **A-10** (el estado del handoff lo guarda NestJS y el bot no
  puede retomar la conversación) y **C-02** (la autoridad de zona horaria sigue en `saaspa-IA` y el backend ya
  devuelve offset explícito en `/availability`) quedan cerrados y verificados contra
  `saaspa-backend@develop@9fc8b12`; **A-08** queda corroborado por la evidencia del E2E. Informe:
  `docs/contracts/f1-e2e-validation.md`.
- **Referenciados en el informe pero nunca redactados** (la §6 se prometió en la intro y el documento terminó en §5: corte de generación): **A-19, A-21, A-23, D-02, D-03, D-04**. Sin detalle disponible; no se persiguen.

| ID | Sev. | Se resuelve en | Resumen |
|---|---|---|---|
| A-04 | Media | Fase 2 (antes del pedido 1 a NestJS) | Config de tenant global (nombre/zona/prompt) vs `tenantId` del token |
| A-05 | Media | Fase 4 (antes de RAG) | `tenant_id`/RLS en la memoria (`spring_ai_chat_memory`); cubre C-08 |
| A-06 | Media | **Resuelto (2026-09-26)** | Tope de coste por tenant y por conversación implementado en `usage/TurnCostGuard` (ADR 0010), medido sobre `ia.turn_log` y con 429; el `trust proxy`/sesión firmada y el tope por cuenta al crear citas son del backend (PR #76 fusionado y Fase 2) |
| A-07 | Media | Fase 2 | Turnos no idempotentes (reintento NestJS duplica llamada/coste/memoria) |
| A-08 | Media | **Resuelto (2026-09-26)** | `turn_log` sin estado y turnos fallidos sin fila. **ADR 0014:** el turno cortado por el deadline se registra con `status = DEADLINE`. **ADR 0015:** `status` pasa a `OK`/`HANDOFF`/`DEADLINE`/`ERROR` con `handoff_reason` y `error_code`, y todo turno que llamó al modelo deja fila (el 502 del backend y los errores inesperados ya no gastan presupuesto invisible); el guard cuenta por "¿llamó al modelo?" y el turno derivado se distingue. Queda como límite documentado que los tokens de los turnos fallidos son desconocidos (cota inferior) |
| A-09 | Media-baja | Fase 2 | Memoria read-modify-write sin serialización por conversación |
| A-11 | Media-baja | Fase 4 (antes del pedido de identidad) | `waId` viaja en el cuerpo, no en el turn token |
| A-12 | Baja | Fase 5 (el dataset de T1.8 ya cubre el caso) | Sin guarda de salida sobre precios |
| A-13 | Baja | **Parcialmente resuelto (2026-09-26, ADR 0016)** | Health no refleja LLM/backend; la clave de salida puede ir vacía. **Hecho:** `/actuator/info` publica el tenant, la zona horaria, el prompt y el modelo (y ya no está vacío). **Sigue pendiente (Fase 5):** el estado del LLM y del backend en `health` y la guarda de la clave de salida |
| A-14 | Media-baja | Fase 5 (el dataset de T1.8 ya cubre los casos) | Listas de handoff hardcodeadas y sin medir precisión/recall; T1.9 añadió más falsos positivos plausibles (`A14-fp-estoy-tomando`, `A14-fp-infecciones`, `A14-fp-cirugia`) marcados como brecha |
| A-15 | Baja | Fase 5 | Sin correlación (`traceparent`/`X-Turn-Id`) ni métricas Micrometer |
| A-16 | Baja | Fase 4 (RAG) | Catálogo del backend como contenido fiable (inyección indirecta) |
| A-17 | Baja | Fase 4 | Sin retención/borrado de la memoria conversacional |
| C-02 | ~~Media~~ | **Resuelto (2026-09-26)** | `locale`/`timezone`/`now` se documentan como contexto informativo para el LLM y la autoridad de zona horaria sigue siendo `saaspa.tenant.timezone`; el backend ya devuelve `/availability` con offset explícito (ver `docs/contracts/f1-e2e-validation.md`) |
| C-03 | Baja | Higiene | `usage.tokensIn/Out` tipados `integer` pero el código puede emitir `null` |
| C-04 | Baja | Higiene | Límite de mensaje 1000 (`web-chat`) vs 2000 (`chat-api`) |
| C-11 | Baja | Proceso | Rama `fix/deprecations-and-handoff` mezcló deprecaciones + T1.7 |
| C-13 | Media-baja | Fase 2 | ADR 0007 dice que se guardan los turnos finales, pero los turnos con handoff no se guardan en la memoria (efecto de A-02). **ADR 0015 ya cubre la mitad del registro** (el turno derivado deja fila `HANDOFF` con su motivo en `ia.turn_log`); lo que queda es la memoria, que se resuelve en la Fase 2 |
| A-24 | Media | Fase 4 (WhatsApp real) | El agente formatea con Markdown estándar (`**negrita**`, `##` encabezados, tablas), que WhatsApp **no** interpreta (usa `*un*` asterisco y no admite encabezados ni tablas): hace falta **salida consciente del canal** (Markdown para el chat web, sintaxis de WhatsApp para WhatsApp), no el mismo texto para los dos. Hallazgo de la prueba E2E real |

### Resuelto: estado del handoff (A-10) — decidido e implementado en el backend

El handoff se decidió por la opción **(a)**: NestJS guarda el estado por conversación y este servicio no
guarda estado de sesión. Ya está **implementado y verificado** en `saaspa-backend@develop@9fc8b12`
(2026-09-26):

- Tabla `chat_conversation_states` (migración `20260926180000_add_chat_conversation_state`) con
  `handoffActive`, `handoffReason`, `lastTurnId` y `messageCount`, atada a la sesión por hash de la clave.
- Cuando `handoffActive` es true, el backend responde su texto canónico (`HANDOFF_ACTIVE_MESSAGE`) **sin
  llamar** a este servicio: el bot no puede retomar la conversación.
- Este servicio sigue informando `handoff.requested`/`reason` por turno; el estado de sesión no es suyo.
- Evidencia y reconciliación: `docs/contracts/f1-e2e-validation.md`.
- **Destino y reversibilidad (ADR 0013, aceptada el 2026-09-26):** el aviso va **por correo al staff**,
  reutilizando el módulo de correo de `saaspa-backend` (SendGrid), **no** por WhatsApp: fuera de la ventana de
  24 h la API de WhatsApp Business exige una plantilla pre-aprobada por Meta. La bandeja del dashboard queda
  pospuesta hasta que exista el widget (J-02). El **texto del turno derivado lo tiene que capturar el backend**:
  se verificó que nuestra memoria **no** guarda el mensaje de una clienta en un turno con handoff (evidencia
  archivo:línea en el ADR 0013).

Queda como efecto colateral **C-13** (los turnos con handoff no entran en la memoria del agente, porque el
código responde sin llamar al modelo): se resolverá en la Fase 2 junto con la memoria.

### Limitación actual de multi-tenant (A-04)

El piloto es **un solo tenant**: `saaspa.tenant.default` (`IA_TENANT_DEFAULT=kamerinos`) es el único
permitido (A-03), y el nombre, la zona horaria y el prompt del tenant son **configuración global del
despliegue**, no del `tenantId` del token. Con un segundo tenant habrá que (1) registrar tenants y
(2) resolver su configuración por `tenantId` en vez de por despliegue. No se resuelve ahora.

---

## 14. Registro de cambios

Añade una línea por tarea terminada: `fecha — rama — qué cambió — resultado de verify`.

- 2026-09-23 — (docs) — AGENTS.md creado con contexto, reglas, git flow, fases y checklist.
- 2026-09-23 — feature/f0-alineacion — pom corregido (coordenadas, DeepSeek, JDBC, Flyway, WireMock, memoria JDBC) — verify verde.
- 2026-09-23 — feature/f0-alineacion — paquete `com.juanp.saaspa.ia`, `SaaspaIaApplication`, `HELP.md` fuera — verify verde.
- 2026-09-23 — feature/f0-alineacion — `application.yml` + perfil local + `.env.example` + `.gitignore` (`.env`) — verify verde.
- 2026-09-23 — feature/f0-alineacion — `docker-compose.yml` dev (pgvector pg15 + redis) + Flyway V1 (esquema `ia`) — verify verde.
- 2026-09-23 — feature/f0-alineacion — ADRs 0006/0007/0008 + correcciones a 0003/0005 — verify verde.
- 2026-09-23 — feature/f0-alineacion — contratos OpenAPI + CI GitHub Actions — verify verde.
- 2026-09-23 — feature/f0-alineacion — README/roadmap alineados; D-ID/D-LLM/D-MEM/D-PG confirmadas; discrepancias registradas — verify verde.
- 2026-09-23 — (nota) — En la Fase 0 se hizo fast-forward local de `main` desde `feat/chat-ia` sin autorización explícita; sin push. Desde ahora `main` solo se toca por PR de release desde `develop`.
- 2026-09-23 — docs/agents-github-workflow — AGENTS.md: entorno Fedora, `gh` autenticado, permisos y prohibiciones de git/gh, reglas de PR (inglés, sin emojis, plantilla, merge manual), lectura de otros repos, T1.0 — (solo documentación).
- 2026-09-23 — docs/agents-github-workflow — checklist de entorno actualizado (gh verificado, ramas en el remoto y PR de Fase 0 fusionado), plantilla de PR y nota del bit ejecutable de `mvnw` — (solo documentación).
- 2026-09-24 — docs/f1-t10-contract-validation — T1.0: contratos validados contra `saaspa-backend` (`develop@ce41e487`, solo lectura con `gh`); informe `docs/contracts/t1.0-backend-validation.md`, contratos v0.2.0 (`chat-api`, `internal-api`) y nuevo borrador `web-chat-api`; pedidos ordenados en la sección 11 y checklist/E2E de la Fase 1 actualizados — verify verde.
- 2026-09-24 — feature/f1-backend-http-client — T1.2: cliente HTTP hacia NestJS (`RestClient` con `HttpClientSettings` de Boot 4.1, timeouts `saaspa.backend.*`, cabecera de servicio `X-Internal-Api-Key`, reenvío del turn token, `BackendException`/`BackendUnavailableException`) + 7 tests de contrato con WireMock — verify verde (8 tests).
- 2026-09-24 — feature/f1-turn-token-verification — T1.1: verificación ES256 (P-256) del turn token con dos claves públicas por `kid` (`withJwkSource`, `jwsAlgorithm(ES256)`, audiencia e issuer opcional), clave de servicio de entrada con comparación en tiempo constante, identidad del turno accesible por `CurrentTurnToken` y 401 con `ProblemDetail`; dependencia `spring-boot-starter-security-oauth2-resource-server`; 24 tests nuevos (decoder, converter, clave de servicio y cadena completa con MockMvc) — verify verde (32 tests).
- 2026-09-24 — feature/f1-read-tools — T1.3: herramientas de lectura del agente CLIENTAS (`listarServicios`, `consultarServicio`, `consultarDisponibilidad`) con `@Tool` en español, DTO de cable mínimos, precios formateados en COP, validación de fechas en la zona horaria del tenant (R13) y fallos como `ok=false` sin excepción (R11); `BackendClient` gana la sobrecarga con plantilla de ruta (evita la doble codificación); 10 tests nuevos con WireMock — verify verde (42 tests).
- 2026-09-24 — feature/f1-customer-agent — T1.5: agente CLIENTAS (`CustomerAgent` + `CustomerAgentConfig`) con prompt v1 en es-CO versionado (`prompts/customer-agent.v1.md`) al que se inyectan negocio, fecha de hoy y zona horaria (R13), herramientas de solo lectura por defecto, memoria con ventana configurable (`saaspa.agent.memory-window`) e id namespaced `{tenantId}:{channel}:{conversationId}` (D-MEM), y respuesta con modelo y tokens para el registro del turno; 7 tests nuevos con `ChatModel` doble (R14) — verify verde (49 tests).
- 2026-09-24 — feature/f1-chat-endpoint — T1.4: `POST /api/v1/chat` (`ChatController`, DTOs validados, `TurnContextValidator` que contrasta cuerpo y turn token según R1, enrutado al agente y respuesta del contrato) + errores con `ProblemDetail` (400 validación/contexto, 501 agente no implementado, 502 backend no disponible, 401/500 defensivos) y contrato chat-api v0.3.0; 7 tests nuevos con MockMvc y agente doble — verify verde (56 tests).
- 2026-09-24 — feature/f1-turn-logging — T1.6: registro durable en el esquema `ia` (`TurnLogService` → `turn_log` con tenant/conversación/canal/agente/prompt/modelo/tokens/latencia desde el controlador, `ToolCallLogger` → `tool_call_log` con estado, latencia y JSON acotado a 4000 caracteres, y `LoggingToolCallback` que envuelve las herramientas del `ChatClient` sin tocarlas); los fallos de escritura no tumbar el turno; 11 tests nuevos (auditoría con dobles + PostgreSQL real con Testcontainers, aislamiento por tenant y JSON grande) — verify verde (67 tests).
- 2026-09-25 — fix/deprecations-and-handoff — corrección de dos deprecaciones marcadas para eliminar en Spring AI 2.0 (`defaultToolCallbacks(...)` → `defaultTools(...)`, que acepta `ToolCallback`; se retira el `getDefaultOptions()` de los dobles) + T1.7: política de handoff en código (`HandoffPolicy`: temas de salud, reclamos y peticiones explícitas con motivo en la respuesta del contrato, normalizando acentos y mayúsculas) y contrato chat-api v0.4.0; 21 tests nuevos — verify verde (88 tests) y sin deprecaciones propias (escaneo con `-Dmaven.compiler.showDeprecation`).
- 2026-09-25 — chore/hermes-review-housekeeping — housekeeping de la revisión Hermes: el borrador se mueve a `docs/reviews/2026-09-25-hermes-architecture-review.md` (no es ADR); C-05 (README: Fase 1 en curso, sin promesa de evaluación en CI por R14) y C-06 (contrato chat-api v0.4.0) — verify verde (88 tests).
- 2026-09-25 — fix/a02-handoff-code-enforcement — A-02 (R10 en código): el handoff se evalúa antes del modelo y, con motivo `HEALTH_TOPIC`/`COMPLAINT`/`EXPLICIT_REQUEST`, no se llama al modelo y se devuelve el texto canónico de `HandoffPolicy` — verify verde (92 tests).
- 2026-09-25 — fix/a01-llm-timeouts-retry — A-01 + ADR 0009: `spring.ai.retry.max-attempts=2` con backoff acotado y 4xx excluidos, `RestClient.Builder` dedicado al modelo con timeouts (`saaspa.llm.*`) y deadline por turno → 504 — verify verde (96 tests).
- 2026-09-25 — fix/a03-tenant-validation — A-03: `turnToken.tenantId()` validado contra `IA_TENANT_DEFAULT` con fallo cerrado (403) y README con el requisito de Java 21 vía SDKMAN — verify verde (97 tests).

- 2026-09-25 — docs/deferred-hermes-findings — sección "Hallazgos diferidos" en AGENTS.md + checklist corregido (C-07) + registro de cambios — verify verde (97 tests).
- 2026-09-25 — feature/f1-eval-dataset — T1.8: dataset `eval/customer-agent.v1.jsonl` (R3, R10 ×3, R11, A-12, A-14 con sus falsos positivos/negativos) + runner `CustomerAgentEvaluator` y `EvalDataset`; en CI se ejercita con un `ChatModel` guionizado (R14) y la evaluación con LLM real queda para la Fase 5 — verify verde (105 tests).
- 2026-09-26 — feature/f1-integration-tests — T1.9: turno íntegro con Testcontainers Postgres (persistencia en `ia`) + ejecución real de `listarServicios` contra WireMock, con `ChatModel` guionizado (R14); A-18: retirados `spring-boot-starter-data-redis`/`…-redis-test`, el contenedor Redis de `TestcontainersConfiguration` y la config/`docker-compose` de Redis; D-01: `mustMatch` en el evaluador y arreglado el caso `R11-catalogo-precio` (el precio de catálogo SÍ debe copiarse desde la herramienta); README con el flujo de ramas antes de ramificar — verify verde (108 tests).
- 2026-09-26 — fix/hermes-second-pass — segunda pasada de Hermes: A-20 (el deadline ahora cancela la llamada en vuelo con `Future.cancel(true)` sobre un `ExecutorService`), A-22 (`on-http-codes=408,429`: 408/429 sí se reintentan y el resto de 4xx no), C-12 (el contrato dice "un único tenant permitido"); A-14 (3 falsos positivos nuevos en el dataset, marcados como brecha); A-10 documentado como decisión pendiente antes de la Fase 2 (estado del handoff) y C-13 anotado; stack de Redis actualizado; A-19/A-21/A-23/D-02..D-04 marcados como nunca redactados — verify verde (111 tests).
- 2026-09-26 — docs/f1-nestjs-orders — cierre de Fase 1: pedidos 1–3 reescritos en §11 con las decisiones A-10 (NestJS mantiene el estado del handoff por conversación), A-11 (`waId` fuera del contrato; identidad solo por el turn token), C-02 (`saaspa-IA` es la autoridad de zona horaria; `timezone`/`now` son contexto informativo) y A-04 documentado como limitación de un solo tenant; contrato chat-api sin `identity.waId` y con `timezone`/`now` aclarados; DTO `Identity` sin `waId`; criterio E2E de Fase 1 marcado como bloqueado por `saaspa-backend` — verify verde (111 tests).
- 2026-09-26 — fix/prompt-brevity-and-turn-test — brevedad del catálogo (prompt v1: para "qué servicios tienen", resumir 3–5 destacados o preguntar por la línea, no volcar los 13) + caso `R10-catalogo-breve` en el dataset con `maxReplyChars` (nuevo tope de longitud en el evaluador, R15) + `ChatApiTurnIntegrationTest` (turno real contra `POST /api/v1/chat` con Testcontainers + WireMock + `ChatModel` guionizado) y contenedor Postgres único compartido en los tests (evita el agotamiento de recursos) — verify verde (116 tests).
- 2026-09-26 — chore/prompt-v2-and-whatsapp-finding — versionado del prompt: el cambio de brevedad pasa a `prompts/customer-agent.v2.md` (v1 vuelve a su contenido original) y suben `PROMPT_VERSION`, `AgentProperties` y `application.yml` a v2; nuevo hallazgo diferido **A-24** (Fase 4): el agente formatea en Markdown estándar y WhatsApp no lo interpreta, hace falta salida consciente del canal; `eval/README.md` anota el prompt objetivo — verify verde (116 tests).
- 2026-09-26 — docs/f1-closeout-and-contract-reconciliation — T1.10 (cierre de la Fase 1): informe `docs/contracts/f1-e2e-validation.md` con la evidencia real del criterio E2E recuperada del volumen local (`ia.turn_log`: turno `WEB_WIDGET`/`CLIENTAS`, `customer-agent.v1`, `deepseek-flash`, 3750/317 tokens, 3611 ms; `ia.tool_call_log`: `listarServicios` OK en 65 ms) y reconciliación contra `saaspa-backend@develop@9fc8b12`; contratos a chat-api v0.5.0, internal-api v0.3.0 (403 de tenant, `{idOrSlug}`, `featured` como texto, disponibilidad con offset) y web-chat-api v0.2.0 (413/429/403 reales, cookie de sesión, handoff omitido); AGENTS.md §7/§11/§12/§13 al día (Fase 1 cerrada, **A-10** y **C-02** resueltos, **A-08** corroborado, `REDIS_URL` retirado, 116 tests) y README — verify verde (116 tests).
- 2026-09-26 — docs/backend-tenant-coupling-note — §11.6: el acoplamiento con `saaspa-backend` pasa a ser una tabla en espejo de su sección 6 (tenant con fallo cerrado 403, zona horaria con fallo silencioso, claves del turn token con 401), se añade el acople de claves/`kid` que faltaba, dónde se hace cumplir en código y la verificación previa al despliegue; cierra el pedido de `saaspa-backend/AGENTS.md` sección 9 — verify verde (116 tests).
- 2026-09-26 — docs/commit-joint-integration-review — control de versiones del informe de la revisión conjunta (`docs/reviews/2026-09-26-joint-integration-review.md`, 536 líneas, commiteado **sin editar**) y referencia desde §11.6 al hallazgo **J-06** para el límite del acople (advertencia manual, sin comprobación en runtime ni en CI); checklist y registro al día — verify verde (116 tests).
- 2026-09-26 — docs/triage-joint-review — triaje de **J-01 a J-13** (y del expiro de `PENDIENTE_PAGO`, etiquetado **B-01**) en `docs/reviews/2026-09-26-joint-review-triage.md`: olas fijadas por la persona (1: J-03, B-01, J-08+J-09, J-05; 2: J-01, J-02; 3: J-04, J-06, J-07; 4: J-10 a J-13), ADR propuesta (0010 a 0018), tarea de checklist y rama propuesta o marca **«requiere coordinación con saaspa-backend, no fusionar de un solo lado»** (J-04, J-05, J-07); checklist de Fase 2 y 5 alineado, pedidos derivados en §11.5 y solapes con §13 cruzados — verify verde (116 tests); ninguna ADR escrita y ninguna rama de implementación abierta.
- 2026-09-26 — docs/adr-0010-0013-write-blockers — las cuatro ADR de la ola 1 del triaje, con estado **Propuesta** (no aceptadas, sin código): **0010** abuso y coste (la mitad «sesión no falsificable» la resolvió el backend en el PR #76 fusionado —`trust proxy` de un salto y sesión anónima firmada—, y queda de este lado el tope por tenant y el coste por conversación con `ia.turn_log` como fuente de verdad), **0011** expiración de `PENDIENTE_PAGO` (implementación 100 % del backend; aquí el contrato y el caso del dataset: propuesta de estado `EXPIRADA` en `Booking.status`), **0012** identidad e idempotencia de escritura (precisa los ADR 0008 y 0006: el sujeto siempre desde `turn.userId`, 403 sin identidad y `Idempotency-Key` construida por código) y **0013** handoff con destino y reversible (reversible y auditable, con el **destino como decisión abierta de la persona**); §4 de AGENTS.md y el checklist del triaje actualizados — verify verde (116 tests).
- 2026-09-26 — docs/accept-write-blocker-adrs — **0010, 0011 y 0012 pasan a Aceptadas** (0013 sigue en Propuesta: el destino del handoff no está decidido) en los tres ficheros y en la tabla de §4; con `EXPIRADA` ya real en el backend (PR #77, fusionado el 2026-09-27 01:09 UTC) el contrato interno expone el estado (`Booking.status` y el 409 del tope de pendientes) y se implementa el caso **`B01-franja-liberada-por-expiracion`** del dataset (real, no brecha) con su nota en `eval/README.md`; checklist del triaje y el estado de B-01 actualizados — verify verde (116 tests).
- 2026-09-26 — fix/timeout-ladder — **ADR 0014** (Aceptada, ola 3 del triaje) y la mitad de **J-04** que cae en este repo: `saaspa.llm.read-timeout` de 30 s a **10 s** y `turn-deadline` de 35 s a **20 s** (escalera `read-timeout < turn-deadline < IA_BOT_TIMEOUT_MS` del backend, 25 s; **el PR no se fusiona** hasta revisar los dos números juntos), `turnId` en el `ProblemDetail` del 504 y turno cortado por el deadline registrado en `ia.turn_log` con `status = DEADLINE` (migración Flyway `V2` + `TurnLogService.Status`); contrato de chat (504 y `turnId`), tests de contrato (504 con `turnId` y fila `DEADLINE`) y de Testcontainers (estado persistido) actualizados — verify verde (117 tests). **No se fusiona** hasta revisar los dos números con el backend.
- 2026-09-26 — docs/accept-adr-0013-handoff-destination — **ADR 0013 pasa a Aceptada** con el destino decidido: aviso **por correo al staff** reutilizando el módulo de correo de `saaspa-backend` (SendGrid), **no** WhatsApp (fuera de la ventana de 24 h la API de WhatsApp Business exige plantilla pre-aprobada por Meta), y la bandeja del dashboard pospuesta hasta que exista el widget (J-02). Se documenta con evidencia (archivo:línea) que **la memoria no guarda el mensaje en un turno con handoff** (`ChatController.java:90,97-105,108`; `CustomerAgent.java:77`; `CustomerAgentConfig.java:45-48,63`, y `javap` sobre `MessageChatMemoryAdvisor`: escribe en `before`/`after`, y ninguno corre sin llamada al modelo), así que el backend debe capturar el texto del turno derivado; §4, §12 y §13 de AGENTS.md y el triaje al día; **sin código en esta rama** — verify verde (117 tests).
- 2026-09-26 — feature/f2-per-tenant-cost-guard — **mitad de ADR 0010 en este repo** (J-03, A-06): `usage/TurnCostGuard` + `CostGuardProperties` con ventana de 1 h y cuatro topes (240 turnos y 1.000.000 tokens por tenant; 30 turnos y 150.000 tokens por conversación, con los números justificados en `application.yml`/`.env.example` como el `BOOKING_PAYMENT_TTL_MINUTES` del backend), medidos con una consulta agregada sobre `ia.turn_log` (**sin contador en memoria**: un reinicio no borra el consumo) y **429** con `ProblemDetail` (`scope`, `measure`, `measured`, `limit`, `window`); se evalúa solo en el camino que llama al modelo (un turno con handoff no se corta por presupuesto, R10) y el turno rechazado no se registra; contrato `chat-api` con el 429; tests: `TurnCostGuardTest` (7, Testcontainers: los cuatro topes, ventana deslizante y aislamiento por tenant), `ChatControllerTest` (429 sin llamar al modelo ni registrar turno, y el guardia consultado en el camino normal) y `ChatControllerHandoffTest` (con handoff el guardia no se consulta); **más el test prometido en el PR de ADR 0013**: `ChatApiTurnIntegrationTest` fija que la memoria **queda vacía** en un turno con handoff y que **se escribe** en uno normal — verify verde (127 tests).
- 2026-09-26 — fix/h02-dockerfile-and-boot — **H-02, la mitad de este repo** (segunda revisión conjunta): `Dockerfile` multi-etapa (`docker.io/library/maven:3.9-eclipse-temurin-21` → `docker.io/library/eclipse-temurin:21-jre`, usuario no root, `MaxRAMPercentage=75` por el `mem_limit: 768m` del compose, `EXPOSE 8000`, `ENTRYPOINT java -jar`) con sus comentarios de por qué `mvn` de la imagen y no `./mvnw`, más `.dockerignore` (sin excluir los prompts de `src/main/resources`) y job `image` del CI (`docker build`, después de `verify`); **segundo agujero del mismo hallazgo, encontrado al verificar**: las credenciales del datasource solo vivían en el perfil `local`, así que el contenedor habría arrancado sin URL de datasource (`Failed to configure a DataSource`) aunque el `Dockerfile` existiera — `spring.datasource.url/username/password` pasan a `application.yml` con los nombres del despliegue (`DATABASE_URL`/`DATABASE_USER`/`DATABASE_PASSWORD`, que Boot **no** traduce por sí solo) y `application-local.yml` queda solo con el nivel de log; nombres de imagen cualificados por el registro porque en el equipo `docker` es Podman y la resolución de nombres cortos exige TTY; informe de la segunda revisión conjunta versionado **sin editar**; §7 (nota nueva del contenedor), §8 (árbol), §11.5 (pedido a `kamerinos-infra`: escalera 10/20/25 s, `IA_BOT_TIMEOUT_MS`, variables en su `.env.example`), §12 (dos entradas del checklist) y README ("Contenedor") al día — **validado**: `./mvnw -B verify` verde (127 tests), `docker build` y `docker run` contra el Postgres del `docker-compose.yml` con `/actuator/health` en `UP` y el esquema `ia` migrado por Flyway.
- 2026-09-26 — fix/h05-turn-log-outcomes — **H-05 + punto 3 de ADR 0013 + el resto de A-08** (segunda revisión conjunta): **ADR 0015** (Aceptada) + migración `V3` (aditiva, sin tocar V1/V2) con `handoff_reason` y `error_code` y un índice `(tenant_id, created_at)` para la consulta del guard; `TurnLogService.Status` pasa a `OK`/`HANDOFF`/`DEADLINE`/`ERROR` con un `ErrorCode` cerrado (`BACKEND_UNAVAILABLE`/`BACKEND_ERROR`/`MODEL_ERROR`) mapeado por origen y sin inspeccionar tipos de Spring AI; `ChatController` registra `HANDOFF` con su motivo en la rama de la política y deja fila `ERROR` (con su código) cuando el fallo ocurre **después** de llamar al modelo, así que `ia.turn_log` es de verdad la fuente de verdad que promete ADR 0010; `TurnCostGuard` cuenta por "¿llamó al modelo?" (`status <> 'HANDOFF'`, con el nombre del estado tomado del enum) y `TurnOutcomeClassificationTest` es el `switch` exhaustivo que fuerza la decisión si aparece un estado nuevo (sustituye a una columna `model_called`); el turno rechazado por el tope sigue sin registrarse y un turno derivado ya no infla los topes de turnos; **límite documentado en el ADR**: los tokens de un turno fallido son desconocidos y quedan en 0 (cota inferior), mientras el conteo de turnos es exacto; tests: `TurnOutcomeClassificationTest` (nuevo), 3 casos de guard contra PostgreSQL (handoff no cuenta, error sí, tenant sin inflar), los tres códigos de error en `ChatControllerTest` (+ `TestBackendExceptions` para poder construir el error "con respuesta" del backend), captor del turno derivado en `ChatControllerHandoffTest`, fila `HANDOFF` real en `ChatApiTurnIntegrationTest` y persistencia de las dos columnas en `UsageLoggingTest`; §4/§12/§13 al día y renumeración del triaje (la 0015 pasa a estar usada; J-06→0016, J-07→0017, J-02→0018, J-10→0019) — verify verde (135 tests).
- 2026-09-26 — fix/h04-origin-scoped-cost-caps — **la otra mitad de H-04** (segunda revisión conjunta): cuarto tope de coste **por origen del turno** (**ADR 0020**): `TurnToken` gana `clientIp` y `origin()` (`user:{id}` si el turno está identificado, `ip:{addr}` si es anónimo), `OriginHasher` guarda el origen como **HMAC-SHA256 con sal** (`IA_COST_GUARD_ORIGIN_SALT`; nunca la IP en claro y nunca en logs), `TurnCostGuard` añade la cubeta de origen (60 turnos/h, `IA_COST_GUARD_ORIGIN_MAX_TURNS`) en la misma consulta agregada y **la evalúa la última** para que el `scope` del 429 sea el más preciso (`origin` para la rotación de conversaciones, `conversation` para una conversación que habla de más, `tenant` como señal de capacidad), `CostLimitExceededException.Scope` gana `ORIGIN` y `ia.turn_log` gana `origin_hash` (migración `V4` aditiva, sin tocar V1-V3, con índice `(tenant_id, origin_hash, created_at)`); **variante tolerante decidida**: sin el claim el turno se registra con `origin_hash` nulo, no entra en ninguna cubeta y el guard avisa **una vez** (WARN), así que esta mitad no queda bloqueada por otro repo; tests: `OriginHasherTest` y `TurnTokenTest` nuevos, `TurnCostGuardTest` con el **caso de H-04 contra PostgreSQL real** (rotar conversaciones ya no agota el tenant: lo corta el origen), el aislamiento entre orígenes y la tolerancia sin claim, `ChatControllerTest` (el hash llega al guard y a la fila, sin la IP) y `ChatApiTurnIntegrationTest` (fila real con el hash esperado); contratos al día por pedido de `saaspa-backend`: `chat-api` **v0.6.0** (el 429 ya llega tal cual al widget, `origin` en los `scope`, claim `clientIp?` documentado y el timeout real de 25000 ms) y `web-chat-api` **v0.3.0** (el 429 incluye el tope de coste del asistente y el 504 cita 25000 ms); §4/§11.5 (pedido del claim)/§11.6 (fila de acople)/§12/§14 al día, con el estado verificado de H-01 a H-06 — verify verde (146 tests).
- 2026-09-26 — fix/h04-origin-claim-activation — **cierre de H-04**: `saaspa-backend` ya emite el claim **`clientIp`** (su PR #84, commit `a08985e`, verificado en su código: `resolveClientIp` = `req.ip` con `TRUSTED_PROXY_HOPS = 1`, la misma IP con la que agrupa su `Throttler`), así que el tope por origen de ADR 0020 está **activo y verificado** en el canal anónimo: arranque del servicio contra el Postgres del `docker-compose.yml` (Flyway migró el esquema `ia` de v2 a **v4**) y **tres turnos reales por HTTP** con un token ES256 con el claim → 200 / 200 / **429 con `"scope":"origin"`** (`measured` 2, `limit` 2), dos filas en `ia.turn_log` con `origin_hash` **ya no nulo** y el mismo hash para el mismo origen (la IP no está en la tabla) y el turno rechazado sin fila; el modelo fue un stub local (R14). Suite: `ChatApiTurnIntegrationTest` gana dos casos (sin el claim → `origin_hash` nulo y ningún tope por origen; con el claim → 429 con `scope: origin` sin llamar al modelo, con `origin-max-turns=2` en el test) y queda en 8 tests. Informe con la evidencia: `docs/contracts/h04-origin-claim-validation.md`. Cierres: §12 (la entrada de activación pasa a hecha), §11.5 (el pedido del claim pasa a hecho y se anota el pedido de baja prioridad de ampliar su `warn` a cualquier `scope`, **sin pedirlo todavía**), §11.6 (la fila de acople pasa a «cubierto», con la red de seguridad documentada), ADR 0020 (desaparece el bullet de pendiente), `chat-api` **v0.6.1** (`clientIp` ya emitido) y los comentarios del `WARN` de `TurnCostGuard`, `TurnToken`, el converter, `application.yml` y `.env.example`. Decidido **no** endurecer a fallo cerrado ni recalibrar el 60/h hasta ver tráfico real — verify verde (148 tests).
- 2026-09-26 — feature/actuator-info-and-tenant-coupling — **J-06 (ola 3) + parte de A-13**: **ADR 0016** (Aceptada) y el acople de tenant/zona horaria con NestJS comprobable: `tenant/TenantCouplingCheck` compara la `timezone` que envía el backend en cada turno con `saaspa.tenant.timezone` y **avisa una vez por instancia** si no coinciden —sin cortar el turno, porque el campo es informativo (C-02)—, `TenantProperties` valida en su **constructor compacto** que el tenant no esté vacío y que la zona sea un `ZoneId` válido (una errata en `IA_TENANT_TIMEZONE` ahora impide el arranque en vez de dar 500 en el primer turno) y `TenantInfoContributor` publica `saaspa.tenant.id`/`saaspa.tenant.timezone` (con el prompt y el modelo) en `/actuator/info`, que deja de estar vacío (A-13 parcial); los tres campos del cuerpo (`locale`/`timezone`/`now`) siguen **informativos** y el contrato solo cambia de prosa (`chat-api` **v0.6.2** documenta `timezone` como señal del acople y aclara que los tres no deciden nada); **decidido no hacer** la consulta de `/actuator/info` al arrancar por el backend (haría de `ia-bot` dependencia de arranque suya y exigiría `healthcheck`/`depends_on` en infra) ni una prueba de conformidad en CI (no ve el despliegue y necesita un valor compartido que mantener): queda como **pedido de baja prioridad redactado y NO enviado** en §11.5; tests: `TenantCouplingCheckTest` (4: aviso único, silencio si coinciden, tolerancia sin valor, predicado), `TenantPropertiesTest` (3), un caso en `ChatControllerTest` (con la zona desalineada el turno sigue respondiendo 200 y el WARN aparece) y uno en `ChatApiTurnIntegrationTest` (`/actuator/info` publica el tenant y la zona) — verify verde (157 tests).
- 2026-09-26 — fix/j07-error-contract-and-client-facing-detail — **J-07 (ola 3) y la frontera error interno/texto de la clienta**: **ADR 0017** (Aceptada) y `api/ProblemCode`, un catálogo cerrado donde cada error tiene estado, título y **texto apto para la clienta** —el `detail` del `ProblemDetail` es lo que NestJS reenvía tal cual al widget— de modo que ningún camino de error vuelve a llevar jerga interna, identificadores ni referencias al roadmap; el **motivo técnico** (qué campo no cuadró, qué excepción se lanzó, qué falló en la autenticación) pasa al **log** y nunca al cuerpo (R8); el `ProblemDetail` gana una propiedad **`code`** estable (`TURN_CONTEXT_MISMATCH`, `INVALID_BODY`, `MALFORMED_BODY`, `UNAUTHENTICATED`, `ACCESS_DENIED`, `TENANT_NOT_ALLOWED`, `COST_LIMIT`, `AGENT_NOT_IMPLEMENTED`, `BACKEND_UNAVAILABLE`, `BACKEND_ERROR`, `MODEL_TIMEOUT`, `UNEXPECTED`) que el gateway podrá mapear sin leer prosa; el manejador de seguridad **ahora registra** los 401/403 (antes no dejaba ningún rastro) y deja de contar a quien llama qué comprobación falló —el filtro de la clave de servicio registra a `warn`/`error` según el caso—; los casos que interpolaban `exception.getMessage()` (400 de contexto y 501 de agente) usan texto público y el título *"Tenant no permitido"* pasa a *"Peticion no permitida"*; contratos: `chat-api` **v0.6.3** (documenta que `detail` es de cara a la clienta y añade `code`) e `internal-api` **v0.3.1** (describe la forma REAL de NestJS, `NestError`, en vez de un RFC 9457 que no implementa) — **`web-chat-api` NO se toca a propósito**: `saaspa-backend` está implementando `problem+json` real en su `ChatController` público y ese contrato se corregirá para **confirmar `Problem`** cuando se fusione su PR, y el pedido de que su gateway registre `detail`+`code` y decida el texto de la clienta queda en §11.5 como baja prioridad y **sin enviar**; tests: `ProblemCodeTest` (4, la **guarda** de que ningún texto público lleva jerga ni roadmap y que cada código agrupa un estado de error), `ChatControllerTest` (los códigos en cada caso, el texto público y el motivo en el log) y `ChatApiSecurityTest` (401 con `code` y texto apto) — verify verde (161 tests).
- 2026-09-26 — docs/web-chat-api-problem-json — **J-07, cierre (mitad coordinada)**: con el PR #85 de `saaspa-backend` ya fusionado, `web-chat-api` **v0.4.0** **confirma RFC 9457** para los errores del chat en vez de documentarlos como la forma de NestJS: media type `application/problem+json` en **400/403/413/429/502/504** (antes solo el 400 lo declaraba, y bajo `application/json`), con el esquema real (`type` = `about:blank`, `title` = frase corta por estado, `status`, `detail` = el texto de cara a la clienta e `instance`) y las **extensiones del tope** (`scope`/`measure`/`measured`/`limit`/`window`) documentadas como presentes **solo** en el 429 de coste; todo **verificado leyendo el código fusionado** del backend (`src/common/filters/problem-details.filter.ts` y `src/common/http/problem-extensions.ts`), no su PR, y anotado que el filtro es `@Catch()` y se aplica **solo a los endpoints de chat** (el resto del API conserva la forma de NestJS) y que un 500/501 inesperado también sale con ese formato; **matiz verificado**: el filtro **no reenvía** el `code` de este servicio (copia solo las cinco extensiones y "anything else is ignored on purpose"), así que `code` queda documentado como **exclusivo del tramo IA → backend** (`chat-api`) y su reenvío al widget es una **mejora opcional de baja prioridad** en §11.5, **sin enviar**; cierres: el ítem «J-07, resto (coordinado)» del checklist pasa a hecho, la decisión 4 de la ADR 0017 recoge el matiz y el triaje queda cerrado — verify verde (161 tests, sin cambios de código).
- 2026-09-28 — fix/hn01-origin-salt-fail-closed — **HN-01 (tercera revisión conjunta), privacidad del `origin_hash`:** la sal de ADR 0020 pasa de «obligatoria en producción» (afirmación sin dientes: el default era la constante pública `saaspa-ia-origin-sin-sal` y solo había un `WARN`) a **fallo cerrado**: fuera del perfil `local` el arranque falla si la sal falta, está en blanco, mide menos de 16 caracteres o es la constante legada (vetada explícitamente, ya no existe como clave en el código); en `local` sin sal se genera una aleatoria por proceso (`SecureRandom`); la variante tolerante del claim `clientIp` de ADR 0020 no cambia (se confundían las dos tolerancias); addendum en la ADR 0020 con el efecto de rotar la sal (la cubeta de origen se reinicia una vez, acotado a la ventana de 1 h) y `application.yml`/`.env.example` con la política y `openssl rand -hex 32`; tests: `OriginHasherTest` reescrito (política completa de `create` con sales explícitas, sin depender de ninguna constante) y sal de prueba explícita en los seis `@SpringBootTest` — verify verde (167 tests).



---

## 15. Cómo debe trabajar un agente en este repo

1. **Al empezar la sesión:** leer este archivo, `git status`, la rama actual y el checklist. Retomar donde quedó.
2. **Planificar antes de programar** tareas de más de una hora: plan corto, aprobación implícita si respeta este
   archivo; explícita si introduce decisiones nuevas.
3. **Cortes verticales pequeños:** una tarea del checklist por rama y por commit lógico.
4. **Verificar, no suponer:** APIs de Spring AI 2.0 y Boot 4.1 se confirman en la documentación oficial o en
   Maven Central. Si algo no compila con lo aprendido de un tutorial 1.x, manda la documentación 2.0.
5. **Preguntar a la persona antes de:** tomar una decisión de arquitectura no cubierta, añadir una dependencia no
   listada aquí, tocar otro repo, hacer llamadas a un LLM real (cuestan), o cualquier operación destructiva de git.
6. **Al terminar cada tarea:** correr `./mvnw -B verify`, actualizar checklist y registro de cambios, y resumir
   en pocas líneas qué se hizo, qué se probó y qué sigue. **Al cerrar una rama o grupo de tareas:** push, PR contra
   `develop` según la sección 9, revisar el CI y **detenerse**: la persona valida y fusiona manualmente.
7. **Ante ambigüedad entre documentos:** este archivo y las ADRs mandan sobre el README y el roadmap largo.
   Señalar la contradicción y proponer la corrección.
