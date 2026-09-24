# AGENTS.md — saaspa-IA

> **Lee este archivo completo antes de tocar nada.** Es la fuente de verdad para cualquier agente de IA
> (o persona) que trabaje en este repositorio. Si algo aquí contradice `README.md` o el roadmap largo,
> **gana este archivo y las ADRs en `docs/adr/`**. Al terminar cada tarea, actualiza la sección
> [12. Checklist de progreso](#12-checklist-de-progreso) y el [registro de cambios](#13-registro-de-cambios).

Última actualización: 2026-09-24

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
- El remoto `origin` usa el alias `git@github-personal:xjapn03/saaspa-IA.git` y en la Fase 0 no había un
  `Host github-personal` en `~/.ssh/config`. Desde entonces la persona autenticó `gh` con protocolo SSH
  (ver sección 9). **Verificar** con `git ls-remote origin` antes de la primera operación remota.

### Discrepancias verificadas durante T1.0 (2026-09-24)

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
- Agente CLIENTAS con `ChatClient`, prompt v1 (es-CO), memoria (ver D-MEM) y ventana corta.
- Herramientas de lectura: `listarServicios`, `consultarServicio`, `consultarDisponibilidad` (cliente HTTP hacia el
  backend, probado con WireMock).
- Reglas de handoff y de temas sensibles en el prompt y en pruebas.
- Registro durable de mensajes, tool calls y uso de tokens en el esquema `ia`.
- Dataset `eval/customer-agent.v1.jsonl` (10–15 casos) y runner.
- **Criterio de aceptación:** una consulta por chat web anónimo devuelve el precio correcto obtenido de la herramienta;
  nunca inventa precios; un tema sensible deriva a handoff; los tokens quedan registrados; tests de contrato en verde.
  Este criterio **E2E** está condicionado a los pedidos 1 a 3 de la sección 11 (turn token, `/api/internal/v1/*` y
  `POST /api/chat`); el resto de la Fase 1 avanza con WireMock (ver checklist de la sección 12).

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

Contratos **borrador** validados contra el código real de `saaspa-backend` (rama `develop`, commit `ce41e487`,
2026-09-24; informe en `docs/contracts/t1.0-backend-validation.md`). Los pedidos van **en orden de dependencia**
y **nada de esto existe todavía** en el backend.

### 11.1 Turn token ES256 + guard (bloquea lo demás)

- Par de claves asimétrico **ES256 (P-256, PEM)** con cabecera `kid`; la privada vive en NestJS y la pública se
  entrega a este servicio (`TURN_TOKEN_PUBLIC_KEY`; en T1.1 se implementa como mapa `kid` → PEM para aceptar dos
  claves públicas y rotar sin cortar el servicio).
- Claims mínimos: `iss`, `aud` (`saaspa-ia`), `iat`, `exp` corta (minutos), `jti` = `turnId`, `tenantId`,
  `conversationId`, `channel`, `agent`, `userId?`, `role?`.
- Guard dedicado en las rutas internas: autoriza con la identidad **del token**, nunca con parámetros de la
  petición ni con argumentos generados por el modelo.
- Este servicio reenvía el turn token tal cual en cada llamada interna.

### 11.2 `/api/internal/v1/*` con `@SkipThrottle`

Contrato: `docs/contracts/internal-api.openapi.yaml`. Autenticación: `X-Internal-Api-Key` con el valor de
`INTERNAL_API_KEY` + `Authorization: Bearer <turn token>`.

- Fase 1: `GET /services` (paginado, espejo de `/api/services/public`), `GET /services/{id|slug}` y
  `GET /availability?serviceId&date`.
- `/availability` debe devolver **offset explícito** y el campo `timezone`: hoy el backend calcula las franjas
  con `Date.setHours` en la TZ del contenedor (`TZ: America/Bogota` está en `kamerinos-infra`, pero la imagen
  `node:20-alpine` no instala `tzdata`: hay que confirmar el efecto real).
- `@SkipThrottle()` o límite propio (el `ThrottlerGuard` global es 100 req/60 s y hoy solo se salta en el
  webhook de WhatsApp) y criterio de auditoría para las llamadas internas (hoy quedarían con actor nulo).
- Fase 2: `POST /bookings` con `Idempotency-Key`, `PATCH`/`DELETE /bookings/{id}` y `GET /me/bookings`.
  Fase 3: reportes. Fase 4: `GET /identity/resolve?waId=` (ADR 0005).

### 11.3 `POST /api/chat` público (chat web)

Contrato: `docs/contracts/web-chat-api.openapi.yaml`.

- Único punto de entrada del canal web (anónimo y logueado): resuelve `tenantId`, `channel`, `agent`, rol e
  identidad en el servidor, emite el turn token y llama a `POST {IA_BOT_URL}/api/v1/chat`.
- El cuerpo del frontend solo lleva `message` y `conversationId`; el `conversationId` anónimo es **aleatorio de
  128 bits** (impredecible) y está atado a la sesión.
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

Autenticación: `X-Internal-Api-Key` con el valor de `IA_BOT_API_KEY` (NestJS → IA) + **turn token** (ADR 0006) en
`Authorization: Bearer`. Streaming (SSE) queda para después de la Fase 1.

### 11.4 Variables de entorno y claves de servicio

- Son **dos secretos distintos**, uno por dirección, y **no se unifican**: `IA_BOT_API_KEY` (NestJS → IA) e
  `INTERNAL_API_KEY` (IA → NestJS). Documentar ambos en los `.env.example` de los dos repos.
- `saaspa-backend` debe añadir `INTERNAL_API_KEY` y la clave privada del turn token
  (`TURN_TOKEN_PRIVATE_KEY` + `TURN_TOKEN_KID`); ya tiene `IA_BOT_URL` e `IA_BOT_API_KEY` (hoy sin uso).
- `saaspa-IA` usa `TURN_TOKEN_PUBLIC_KEY`, `INTERNAL_API_KEY` e `IA_BOT_API_KEY`; `LLM_API_KEY`, `BACKEND_URL`,
  `DATABASE_URL`, `REDIS_URL` e `IA_TENANT_DEFAULT=kamerinos` ya están previstas.

### 11.5 Otros repos y contratos

- **kamerinos-infra:** contenedor `ia-bot` en la red interna; PostgreSQL con pgvector y usuario con permisos solo
  sobre el esquema `ia`; variables de entorno de 11.4; confirmar la TZ efectiva del contenedor del backend
  (`TZ: America/Bogota` está definido, pero la imagen es `node:20-alpine` sin `tzdata`).
- **saaspa-frontend:** el chat web habla con `POST /api/chat` de NestJS, **nunca** directamente con este servicio.
- **Contratos:** `chat-api.openapi.yaml` (NestJS → IA, v0.2.0), `internal-api.openapi.yaml` (IA → NestJS, v0.2.0),
  `web-chat-api.openapi.yaml` (frontend → NestJS, v0.1.0) y `t1.0-backend-validation.md` (informe de T1.0).

---

## 12. Checklist de progreso

Marca con `[x]` al terminar y anota la fecha. No marques nada que no esté verificado con `./mvnw -B verify` o revisión.

### Hecho
- [x] ADR 0001–0005 redactadas y aceptadas (2026-09-23)
- [x] README reescrito para la arquitectura NestJS + Java (2026-09-23)
- [x] Proyecto generado con Spring Initializr: Boot 4.1.1, Java 21, BOM de Spring AI 2.0.1 (pom por corregir)
- [x] Auditoría inicial del `pom.xml` y del repo (hallazgos en la sección 7)

### Entorno y flujo de trabajo
- [x] `gh` autenticado como `xjapn03` (SSH, scopes `repo`, `read:org`, `admin:public_key`, `gist`) y documentado (2026-09-23)
- [x] `git ls-remote origin` verificado (alias `github-personal` resuelto el 2026-09-23; sin push a `main`)
- [x] Ramas `main` y `develop` en el remoto; PR de `feature/f0-alineacion` fusionado en `develop` (2026-09-23)
- [x] Reglas de GitHub de la sección 9 incorporadas a `develop` (PR `docs/agents-github-workflow`, fusionado 2026-09-23)
- [x] `.github/pull_request_template.md` creada
- [ ] Protección de ramas configurada por la persona (PR obligatorio, check `verify`, sin push directo)
- [ ] JDK 21 con `javac` instalado en el equipo (documentado en el README)

### Fase 0 — Alineación y contratos (completada 2026-09-23)
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
- [x] T1.0 Contratos validados contra `saaspa-backend` (lectura con `gh`; `develop@ce41e487`, 2026-09-24)
- [ ] Verificación de servicio y turn token (ES256, dos claves públicas por `kid`)
- [ ] `POST /api/v1/chat` con validación y `ProblemDetail`
- [ ] Agente CLIENTAS + prompt v1 (es-CO) + memoria con ventana
- [ ] Cliente HTTP hacia NestJS con timeouts
- [ ] Herramientas: `listarServicios`, `consultarServicio`, `consultarDisponibilidad` (probadas con WireMock)
- [ ] Handoff y política de temas sensibles
- [ ] Registro de mensajes, tool calls y tokens en `ia`
- [ ] Dataset `eval/customer-agent.v1.jsonl` y runner
- [ ] Tests: unitarios, contrato (WireMock), Testcontainers Postgres
- [ ] **(bloqueado) Criterio de aceptación E2E de la Fase 1:** chat web anónimo que devuelve el precio real desde
      la herramienta. Depende de los pedidos 1 a 3 de la sección 11 (turn token + `/api/internal/v1/*` +
      `POST /api/chat`); el resto de la Fase 1 avanza con WireMock.

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
- 2026-09-23 — (nota) — En la Fase 0 se hizo fast-forward local de `main` desde `feat/chat-ia` sin autorización explícita; sin push. Desde ahora `main` solo se toca por PR de release desde `develop`.
- 2026-09-23 — docs/agents-github-workflow — AGENTS.md: entorno Fedora, `gh` autenticado, permisos y prohibiciones de git/gh, reglas de PR (inglés, sin emojis, plantilla, merge manual), lectura de otros repos, T1.0 — (solo documentación).
- 2026-09-23 — docs/agents-github-workflow — checklist de entorno actualizado (gh verificado, ramas en el remoto y PR de Fase 0 fusionado), plantilla de PR y nota del bit ejecutable de `mvnw` — (solo documentación).
- 2026-09-24 — docs/f1-t10-contract-validation — T1.0: contratos validados contra `saaspa-backend` (`develop@ce41e487`, solo lectura con `gh`); informe `docs/contracts/t1.0-backend-validation.md`, contratos v0.2.0 (`chat-api`, `internal-api`) y nuevo borrador `web-chat-api`; pedidos ordenados en la sección 11 y checklist/E2E de la Fase 1 actualizados — verify verde.

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
   en pocas líneas qué se hizo, qué se probó y qué sigue. **Al cerrar una rama o grupo de tareas:** push, PR contra
   `develop` según la sección 9, revisar el CI y **detenerse**: la persona valida y fusiona manualmente.
7. **Ante ambigüedad entre documentos:** este archivo y las ADRs mandan sobre el README y el roadmap largo.
   Señalar la contradicción y proponer la corrección.