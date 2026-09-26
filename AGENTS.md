# AGENTS.md — saaspa-IA

> **Lee este archivo completo antes de tocar nada.** Es la fuente de verdad para cualquier agente de IA
> (o persona) que trabaje en este repositorio. Si algo aquí contradice `README.md` o el roadmap largo,
> **gana este archivo y las ADRs en `docs/adr/`**. Al terminar cada tarea, actualiza la sección
> [12. Checklist de progreso](#12-checklist-de-progreso) y el [registro de cambios](#14-registro-de-cambios).

Última actualización: 2026-09-25

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
  Fase 3: reportes. Fase 4: NestJS resuelve el `waId` de WhatsApp por su lado (ADR 0005) y firma el
  `userId` en el turn token; este servicio no pide resolverlo (A-11).

### 11.3 `POST /api/chat` público (chat web)

Contrato: `docs/contracts/web-chat-api.openapi.yaml`.

- Único punto de entrada del canal web (anónimo y logueado): resuelve `tenantId`, `channel`, `agent`, rol e
  identidad en el servidor, emite el turn token y llama a `POST {IA_BOT_URL}/api/v1/chat`.
- El cuerpo del frontend solo lleva `message` y `conversationId`; el `conversationId` anónimo es **aleatorio de
  128 bits** (impredecible) y está atado a la sesión.
- **Estado del handoff (A-10):** NestJS **mantiene el estado del handoff por conversación** (coherente con que
  ya es el dueño del `ConversationState` de WhatsApp): recuerda si una conversación quedó en handoff y **no
  deja que el bot la retome** sin intervención humana. Este servicio solo informa de
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
- `saaspa-IA` usa `TURN_TOKEN_KEY_CURRENT_PUBLIC_KEY`, `TURN_TOKEN_KEY_PREVIOUS_PUBLIC_KEY`,
  `TURN_TOKEN_AUDIENCE` (por defecto `saaspa-ia`), `TURN_TOKEN_ISSUER` (opcional), `INTERNAL_API_KEY` e
  `IA_BOT_API_KEY`; `LLM_API_KEY`, `BACKEND_URL`, `DATABASE_URL`, `REDIS_URL` e `IA_TENANT_DEFAULT=kamerinos`
  ya están previstas.

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
- [x] JDK 21 con `javac` instalado en el equipo (SDKMAN Temurin 21; documentado en el README — 2026-09-25)

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
- [x] Verificación de servicio y turn token (ES256, dos claves públicas por `kid`; `ProblemDetail` en 401 — T1.1, 2026-09-24)
- [x] `POST /api/v1/chat` con validación y `ProblemDetail` (contraste del cuerpo con el turn token → 400, agente no implementado → 501, backend caído → 502; contrato v0.3.0 — T1.4, 2026-09-24)
- [x] Agente CLIENTAS + prompt v1 (es-CO) + memoria con ventana (`customer-agent.v1.md` con fecha/zona del tenant, `ChatMemory` de ventana configurable, id `{tenantId}:{channel}:{conversationId}` — T1.5, 2026-09-24)
- [x] Cliente HTTP hacia NestJS con timeouts (`RestClient`, clave de servicio, turn token reenviado, mapeo de errores; probado con WireMock — T1.2, 2026-09-24)
- [x] Herramientas: `listarServicios`, `consultarServicio`, `consultarDisponibilidad` (`@Tool` en español, precios en COP preformateados, `ok=false` sin excepción — T1.3, 2026-09-24)
- [x] Handoff y política de temas sensibles (decisión en código: salud, reclamos y peticiones explícitas con motivo `HEALTH_TOPIC`/`COMPLAINT`/`EXPLICIT_REQUEST`; contrato chat-api v0.4.0 — T1.7, 2026-09-25)
- [x] Registro de mensajes, tool calls y tokens en `ia` (`ia.turn_log` con tenant/conversación/canal/agente/prompt/modelo/tokens/latencia, `ia.tool_call_log` con estado y JSON acotado, memoria JDBC para los mensajes; fallos de escritura no tumban el turno — T1.6, 2026-09-24)
- [x] Tests: unitarios, contrato (WireMock) y Testcontainers Postgres — 97 tests, 0 fallos (2026-09-25)
- [x] T1.8: dataset `eval/customer-agent.v1.jsonl` + runner (`CustomerAgentEvaluator`; en CI corre con un `ChatModel` guionizado, y la evaluación con LLM real corre aparte — R14) — 2026-09-25
- [x] T1.9: prueba de integración del turno con Testcontainers Postgres (turno íntegro persistido + ejecución real de `listarServicios` contra WireMock; `ChatModel` guionizado — R14) — 2026-09-26
- [ ] **[BLOQUEADO, depende de `saaspa-backend`]** Criterio de aceptación E2E de la Fase 1: chat web anónimo
      que devuelve el precio real desde la herramienta. Es lo único que impide cerrar la Fase 1 al 100 %;
      depende de los pedidos 1–3 de la sección 11 (turn token + guard, `/api/internal/v1/*` y `POST /api/chat`),
      que se implementan en el repo del backend. El resto (T1.0–T1.9) está hecho y probado con WireMock y
      Testcontainers.

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

## 13. Hallazgos diferidos (revisión Hermes, 2026-09-25)

> Revisión externa de solo lectura: `docs/reviews/2026-09-25-hermes-architecture-review.md`. Cada
> hallazgo se evalúa contra el código real antes de aceptarlo (R17). Lo ya implementado figura como
> resuelto; el resto queda aquí como backlog con severidad y la fase en la que se resolverá.

**Resueltos en esta tanda (no diferidos):**

- A-01 (timeouts/retry/deadline del LLM) — PR #13, ADR 0009.
- A-02 (R10 en código: handoff antes del modelo, texto canónico) — PR #12.
- A-03 (validación de `tenantId` con fallo cerrado, 403) — PR #14.
- C-01 (= A-03), C-05/C-06 (README y contrato) — PR #11; C-07 (checklist) — PR #15; C-09 (= A-01) — PR #13; C-10 (dataset `eval/` y runner) — PR de T1.8; A-18 (Redis sin uso retirado) y D-01 (caso de precio del dataset) — PR de T1.9; A-20 (cancelación real del deadline), A-22 (408/429 reintentables) y C-12 (contrato de tenant único) — PR de la segunda pasada.
- **Referenciados en el informe pero nunca redactados** (la §6 se prometió en la intro y el documento terminó en §5: corte de generación): **A-19, A-21, A-23, D-02, D-03, D-04**. Sin detalle disponible; no se persiguen.

| ID | Sev. | Se resuelve en | Resumen |
|---|---|---|---|
| A-04 | Media | Fase 2 (antes del pedido 1 a NestJS) | Config de tenant global (nombre/zona/prompt) vs `tenantId` del token |
| A-05 | Media | Fase 4 (antes de RAG) | `tenant_id`/RLS en la memoria (`spring_ai_chat_memory`); cubre C-08 |
| A-06 | Media | Fase 2 | Sin tope de coste/turnos ni rate limiting por tenant |
| A-07 | Media | Fase 2 | Turnos no idempotentes (reintento NestJS duplica llamada/coste/memoria) |
| A-08 | Media | Fase 2 | `turn_log` sin estado; turnos fallidos no se registran |
| A-09 | Media-baja | Fase 2 | Memoria read-modify-write sin serialización por conversación |
| A-10 | **Media** | **antes de abrir la Fase 2** (ver decisión abajo) | Handoff sin estado: quién "engancha" con una persona |
| A-11 | Media-baja | Fase 4 (antes del pedido de identidad) | `waId` viaja en el cuerpo, no en el turn token |
| A-12 | Baja | Fase 5 (el dataset de T1.8 ya cubre el caso) | Sin guarda de salida sobre precios |
| A-13 | Baja | Fase 5 / despliegue | Health no refleja LLM/backend; la clave de salida puede ir vacía |
| A-14 | Media-baja | Fase 5 (el dataset de T1.8 ya cubre los casos) | Listas de handoff hardcodeadas y sin medir precisión/recall; T1.9 añadió más falsos positivos plausibles (`A14-fp-estoy-tomando`, `A14-fp-infecciones`, `A14-fp-cirugia`) marcados como brecha |
| A-15 | Baja | Fase 5 | Sin correlación (`traceparent`/`X-Turn-Id`) ni métricas Micrometer |
| A-16 | Baja | Fase 4 (RAG) | Catálogo del backend como contenido fiable (inyección indirecta) |
| A-17 | Baja | Fase 4 | Sin retención/borrado de la memoria conversacional |
| C-02 | Media | Fase 2 (antes del pedido 1) | `locale`/`timezone`/`now` obligatorios pero ignorados por el código |
| C-03 | Baja | Higiene | `usage.tokensIn/Out` tipados `integer` pero el código puede emitir `null` |
| C-04 | Baja | Higiene | Límite de mensaje 1000 (`web-chat`) vs 2000 (`chat-api`) |
| C-11 | Baja | Proceso | Rama `fix/deprecations-and-handoff` mezcló deprecaciones + T1.7 |
| C-13 | Media-baja | Fase 2 | ADR 0007 dice que se guardan los turnos finales, pero los turnos con handoff no se guardan en la memoria (efecto de A-02) |

### Decisión pendiente antes de la Fase 2: estado del handoff (A-10)

Con A-02, un tema sensible se responde en código y **no** llama al modelo, así que ese turno no deja
rastro en la memoria (C-13). El handoff es hoy un flag por turno y el turno siguiente vuelve a
empezar: nadie sabe que la clienta fue derivada y puede volver a ofrecer agendar. Antes de abrir la
Fase 2 hay que decidir **quién guarda el estado del handoff**:

- **(a) NestJS por conversación (recomendado):** el gateway ya tiene `ConversationState` por
  conversación; allí vive el flag (motivo + `turnId`) y se consulta antes de llamar a este servicio.
  Sobrevive a reinicios, es la autoridad de canales y no duplica estado. Coste: un pedido de contrato
  más a NestJS (junto al turn token).
- **(b) Campo de sesión que devuelva este servicio:** el contrato de chat devuelve
  `handoff.requested` (y un flag de sesión) y **NestJS lo persiste y lo reenvía**; este servicio solo
  lo propaga. Un solo sitio decide (el código de `HandoffPolicy`); coste: NestJS lo guarda/reenvía y
  hay que versionar el contrato.

No se implementa todavía: es una decisión de contrato que se revisa antes de la Fase 2.

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