# AGENTS.md — saaspa-IA

> **Lee este archivo completo antes de tocar nada.** Es la fuente de verdad para cualquier agente de IA
> (o persona) que trabaje en este repositorio. Si algo aquí contradice `README.md` o el roadmap largo,
> **gana este archivo y las ADRs en `docs/adr/`**. Al terminar cada tarea, actualiza la sección
> [12. Checklist de progreso](#12-checklist-de-progreso) y el [registro de cambios](#13-registro-de-cambios).

Última actualización: 2026-09-23

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

**Regla:** desde este repo **nunca se modifican los otros repos**. Si se necesita un endpoint nuevo en NestJS,
se documenta en `docs/contracts/` y en la sección [11. Pedidos a otros repos](#11-pedidos-a-otros-repos).

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

| ADR | Tema | Propuesta |
|---|---|---|
| 0006 | Propagación de identidad y autorización de herramientas | NestJS emite un **turn token** (JWT firmado, vida de minutos) con `tenantId`, `userId?`, `role?`, `conversationId`, `turnId`, `agent`. Java lo reenvía tal cual en cada llamada interna. NestJS **autoriza usando el token**, no campos que envíe Java ni argumentos del modelo. Se puede pasar al contexto de las herramientas con `ToolContext` de Spring AI (verificar en la doc). Alternativa más simple: headers + `INTERNAL_API_KEY`; se descarta por permitir suplantación si Java falla. |
| 0007 | Memoria y persistencia del agente | Ver [decisión D-MEM](#decisiones-abiertas). Esquema propio `ia` en PostgreSQL. Log durable de mensajes, tool calls y uso. |
| 0008 | Política de herramientas de escritura | Solo lectura primero; escritura tras feature flag; confirmación explícita de la clienta; **idempotencia** (`Idempotency-Key`); auditoría de cada tool call. |

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
| Redis | Redis estándar para idempotencia, rate limiting y caché | **No** es Redis Stack (ver trampas) |
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
- `docker-compose.yml` de desarrollo (`pgvector/pgvector:pg15` + `redis:7-alpine`).
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
- El remoto `git@github-personal:xjapn03/saaspa-IA.git` **no** tiene un `Host github-personal`
  definido en `~/.ssh/config` → las operaciones remotas fallarían hasta definir ese alias.

---

## 8. Estructura objetivo y convenciones de código

```text
saaspa-IA/
├── AGENTS.md
├── README.md
├── pom.xml  mvnw  mvnw.cmd
├── docker-compose.yml                 # solo desarrollo local (Postgres+pgvector, Redis)
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

## 9. Git flow

Ramas:

| Rama | Uso |
|---|---|
| `main` | Siempre desplegable. Protegida. Solo recibe PRs desde `develop` (release) o `hotfix/*`. Cada fase terminada se etiqueta `vX.Y.0`. |
| `develop` | Integración. Solo recibe PRs desde ramas de trabajo. |
| `feature/<fase>-<slug>` | Trabajo nuevo, p. ej. `feature/f1-chat-endpoint`. Sale de `develop`. |
| `fix/<slug>` | Corrección de bugs. Sale de `develop`. |
| `docs/<slug>` · `chore/<slug>` | Documentación / mantenimiento. |
| `hotfix/<slug>` | Urgente sobre `main`; luego se fusiona también a `develop`. |

Reglas:
- **Commits:** [Conventional Commits](https://www.conventionalcommits.org/): `feat:`, `fix:`, `docs:`, `test:`,
  `refactor:`, `chore:`, `build:`, `ci:`. Pequeños y enfocados. Ejemplo: `feat(tools): add listServices tool`.
- **PRs:** de rama de trabajo a `develop`, con squash merge. Descripción: qué, por qué, cómo se probó, checklist tocado.
  Un PR = una tarea del checklist.
- **Agentes de IA:** pueden crear ramas y hacer commits locales. **No** hacen `push`, no abren ni fusionan PRs,
  **no** reescriben historia (`--force`, `rebase` de ramas compartidas) y **no** commitean directo a `main` o
  `develop`, salvo instrucción explícita de la persona.
- Nunca commitear secretos, `.env`, dumps de datos reales ni conversaciones reales sin anonimizar.
- Antes de cada commit: `./mvnw -B verify` en verde.

### Definición de "hecho" (para cada tarea)
- [ ] Compila y `./mvnw -B verify` en verde
- [ ] Tests nuevos/actualizados (unitarios, y contrato o Testcontainers cuando aplique)
- [ ] Reglas R1–R17 respetadas
- [ ] Dataset `eval/` actualizado si cambió el comportamiento del agente
- [ ] Documentación/ADR/contratos actualizados
- [ ] Checklist y registro de cambios de este archivo actualizados

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
- `POST /api/v1/chat`: autenticación de servicio + verificación del turn token, validación, `ProblemDetail`.
- Agente CLIENTAS con `ChatClient`, prompt v1 (es-CO), memoria (ver D-MEM) y ventana corta.
- Herramientas de lectura: `listarServicios`, `consultarServicio`, `consultarDisponibilidad` (cliente HTTP hacia el
  backend, probado con WireMock).
- Reglas de handoff y de temas sensibles en el prompt y en pruebas.
- Registro durable de mensajes, tool calls y uso de tokens en el esquema `ia`.
- Dataset `eval/customer-agent.v1.jsonl` (10–15 casos) y runner.
- **Criterio de aceptación:** una consulta por chat web anónimo devuelve el precio correcto obtenido de la herramienta;
  nunca inventa precios; un tema sensible deriva a handoff; los tokens quedan registrados; tests de contrato en verde.

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

Contratos **borrador** (validar contra el código real de NestJS; este repo no puede verlo).
Se cierran en `docs/contracts/` durante la Fase 0.

**`POST /api/v1/chat`** (NestJS → IA)

```json
{
  "turnId": "uuid",
  "tenantId": "kamerinos",
  "conversationId": "string",
  "channel": "WHATSAPP | WEB_WIDGET | WEB_LOGGED | DASHBOARD",
  "agent": "CLIENTAS | ADMIN",
  "identity": { "kind": "ANONYMOUS | USER", "userId": "string?", "role": "CLIENTE | EMPLEADO | ADMIN | null", "waId": "string?" },
  "message": { "text": "string" },
  "locale": "es-CO",
  "timezone": "America/Bogota",
  "now": "2026-09-23T10:00:00-05:00"
}
```

Respuesta (borrador):

```json
{
  "turnId": "uuid",
  "reply": { "text": "string", "links": [ { "label": "string", "url": "string" } ] },
  "handoff": { "requested": false, "reason": null },
  "usage": { "model": "string", "tokensIn": 0, "tokensOut": 0 },
  "sources": []
}
```

Autenticación: cabecera de servicio + **turn token** (ADR 0006). Streaming (SSE) queda para después de la Fase 1.

**Endpoints internos que NestJS debe exponer** (`/api/internal/v1/*`, solo red Docker interna, con `INTERNAL_API_KEY`
y validación del turn token):

| Fase | Endpoint (borrador) |
|---|---|
| 1 | `GET /services`, `GET /services/{id}`, `GET /availability?serviceId&from&to&staffId` |
| 2 | `POST /bookings` (con `Idempotency-Key`), `PATCH /bookings/{id}`, `DELETE /bookings/{id}`, `GET /me/bookings` |
| 3 | `GET /reports/sales`, `/reports/top-services`, `/reports/top-products`, `/reports/appointments`, `/reports/low-stock` |
| 4 | `GET /identity/resolve?waId=` (según ADR 0005) |

Otros pedidos:
- **kamerinos-infra:** contenedor `ia-bot`; PostgreSQL con pgvector y usuario con permisos solo sobre el esquema `ia`;
  variables de entorno (ver abajo).
- **saaspa-frontend:** el chat web debe hablar con NestJS, no directamente con este servicio.

Variables de entorno previstas: `LLM_API_KEY`, `INTERNAL_API_KEY`, `BACKEND_URL`, `DATABASE_URL`, `REDIS_URL`,
`TURN_TOKEN_SECRET` (o clave pública, según ADR 0006), `IA_TENANT_DEFAULT=kamerinos`.

---

## 12. Checklist de progreso

Marca con `[x]` al terminar y anota la fecha. No marques nada que no esté verificado con `./mvnw -B verify` o revisión.

### Hecho
- [x] ADR 0001–0005 redactadas y aceptadas (2026-09-23)
- [x] README reescrito para la arquitectura NestJS + Java (2026-09-23)
- [x] Proyecto generado con Spring Initializr: Boot 4.1.1, Java 21, BOM de Spring AI 2.0.1 (pom por corregir)
- [x] Auditoría inicial del `pom.xml` y del repo (hallazgos en la sección 7)

### Fase 0 — Alineación y contratos ✅ (2026-09-23)
- [x] Ramas `main` y `develop` configuradas; trabajo en `feature/f0-alineacion`
- [x] `pom.xml` corregido: groupId/artifactId/name, sin bloques vacíos, starter de LLM, JDBC, Flyway, WireMock
- [x] Memoria: decisión D-MEM aplicada (`spring-ai-starter-model-chat-memory-repository-jdbc`)
- [x] Paquete `com.juanp.saaspa.ia`, clase `SaaspaIaApplication`, `HELP.md` eliminado
- [x] `application.yml`, `application-local.yml`, `.env.example`, `.gitignore` (ignora `.env`)
- [x] `docker-compose.yml` de desarrollo (`pgvector/pgvector:pg15` + `redis:7-alpine`)
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
- [ ] Verificación de servicio y turn token
- [ ] `POST /api/v1/chat` con validación y `ProblemDetail`
- [ ] Agente CLIENTAS + prompt v1 (es-CO) + memoria con ventana
- [ ] Cliente HTTP hacia NestJS con timeouts
- [ ] Herramientas: `listarServicios`, `consultarServicio`, `consultarDisponibilidad`
- [ ] Handoff y política de temas sensibles
- [ ] Registro de mensajes, tool calls y tokens en `ia`
- [ ] Dataset `eval/customer-agent.v1.jsonl` y runner
- [ ] Tests: unitarios, contrato (WireMock), Testcontainers Postgres

### Fase 2 — Agenda por chat + cliente logueado
- [ ] `crearCita`, `reprogramarCita`, `cancelarCita`, `misCitas`
- [ ] Idempotencia + confirmación explícita + feature flag
- [ ] Enlaces pre-diligenciados `/agendar` y `/shop`
- [ ] Tests de idempotencia y fechas relativas

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

## 13. Registro de cambios

Añade una línea por tarea terminada: `fecha — rama — qué cambió — resultado de verify`.

- 2026-09-23 — (docs) — AGENTS.md creado con contexto, reglas, git flow, fases y checklist.
- 2026-09-23 — feature/f0-alineacion — pom corregido (coordenadas, DeepSeek, JDBC, Flyway, WireMock, memoria JDBC) — verify verde.
- 2026-09-23 — feature/f0-alineacion — paquete `com.juanp.saaspa.ia`, `SaaspaIaApplication`, `HELP.md` fuera — verify verde.
- 2026-09-23 — feature/f0-alineacion — `application.yml` + perfil local + `.env.example` + `.gitignore` (`.env`) — verify verde.
- 2026-09-23 — feature/f0-alineacion — `docker-compose.yml` dev (pgvector pg15 + redis) + Flyway V1 (esquema `ia`) — verify verde.
- 2026-09-23 — feature/f0-alineacion — ADRs 0006/0007/0008 + correcciones a 0003/0005 — verify verde.
- 2026-09-23 — feature/f0-alineacion — contratos OpenAPI + CI GitHub Actions — verify verde.
- 2026-09-23 — feature/f0-alineacion — README/roadmap alineados; D-ID/D-LLM/D-MEM/D-PG confirmadas; discrepancias registradas — verify verde.

---

## 14. Cómo debe trabajar un agente en este repo

1. **Al empezar la sesión:** leer este archivo, `git status`, la rama actual y el checklist. Retomar donde quedó.
2. **Planificar antes de programar** tareas de más de una hora: plan corto, aprobación implícita si respeta este
   archivo; explícita si introduce decisiones nuevas.
3. **Cortes verticales pequeños:** una tarea del checklist por rama y por commit lógico.
4. **Verificar, no suponer:** APIs de Spring AI 2.0 y Boot 4.1 se confirman en la documentación oficial o en
   Maven Central. Si algo no compila con lo aprendido de un tutorial 1.x, manda la documentación 2.0.
5. **Preguntar a la persona antes de:** tomar una decisión de arquitectura no cubierta, añadir una dependencia no
   listada aquí, tocar otro repo, hacer llamadas a un LLM real (cuestan), o cualquier operación destructiva de git.
6. **Al terminar cada tarea:** correr `./mvnw -B verify`, actualizar checklist y registro de cambios, y resumir
   en pocas líneas qué se hizo, qué se probó y qué sigue.
7. **Ante ambigüedad entre documentos:** este archivo y las ADRs mandan sobre el README y el roadmap largo.
   Señalar la contradicción y proponer la corrección.
