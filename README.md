# saaspa-IA

Motor de IA conversacional de **Kamerinos SPA Bogotá** (centro de estética y bienestar).
Es un servicio **separado** en **Java 21 + Spring Boot 4 + Spring AI 2.0** que actúa como el
"cerebro" de los agentes conversacionales, mientras el backend **NestJS** (`saaspa-backend`)
sigue siendo el sistema de registro que ejecuta la lógica de negocio.

> **Estado:** Fase 1 en curso (cerebro mínimo + chat web anónimo). Completadas T1.1–T1.7
> (turn token, cliente HTTP, herramientas de lectura, endpoint de chat, agente CLIENTAS,
> registro de turnos y handoff); pendientes T1.8 (dataset de evaluación) y el criterio E2E de la Fase 1.
> El plan completo está en [`AI_WhatsApp_SaaS_Roadmap_2026.md`](./AI_WhatsApp_SaaS_Roadmap_2026.md).

## Qué es (y qué no es)

- **Sí es:** la capa de conversación, RAG, tool-calling, memoria y evaluación.
- **No es:** el dueño de los datos ni de la lógica de negocio. Agenda, precios, stock, pagos
  y reportes los calcula `saaspa-backend`; este servicio los consume por HTTP interno.

## Relación con los otros repositorios

| Repo | Rol |
|---|---|
| `saaspa-backend` | NestJS 11 + Prisma + PostgreSQL/pgvector + Redis. **Sistema de registro** y ejecutor de herramientas. Expone un API interno para el agente. |
| `saaspa-frontend` | Next.js 16. Host del chat web (anónimo + logueado) y del chat del dashboard. |
| `saaspa-IA` (este) | Java + Spring AI. Cerebro de los agentes (CLIENTAS y ADMIN). |
| `kamerinos-infra` | Docker Compose + Nginx. Añade el contenedor `ia-bot` a la red interna. |

## Dos agentes

**Agente CLIENTAS** (WhatsApp y chat web)
- Resuelve dudas de servicios y productos (RAG + catálogo).
- Consulta disponibilidad y **agenda, reprograma y cancela citas**.
- Deriva a una persona ante temas sensibles (médicos, reclamos) o cuando no está seguro.
- Genera **enlaces pre-diligenciados** a `/agendar` y `/shop`; no cobra ni despacha.

**Agente ADMIN** (chat del dashboard, con login y roles)
- Responde reportes predefinidos: ventas, servicios más pedidos, productos, citas, stock.
- **Solo lectura**; las cifras las calcula el código (NestJS), no el modelo.

## Principios de diseño

- **La IA conversa; el código decide.** Este servicio orquesta; `saaspa-backend` calcula y valida.
- **Precios, horarios y stock no van en RAG**: van en tablas y se consultan con herramientas.
- **Cada agente tiene solo sus herramientas** (el de clientas no accede a reportes).
- **Multi-tenant híbrido** con aislamiento de RAG y base de datos (`tenant_id` en todo dato del agente).
- **Evaluación con LLM-as-a-Judge fuera del `verify` de CI (regla R14) y control de costos**.

## Canales e identidad

| Canal | Identidad | Rol | Agente |
|---|---|---|---|
| WhatsApp | anónimo (identificado por `waId`/teléfono) | — | CLIENTAS |
| Chat web (widget) | anónimo | — | CLIENTAS |
| Chat web | logueado | `CLIENTE` | CLIENTAS (ve sus citas/pedidos) |
| Chat dashboard | logueado | `EMPLEADO` | ADMIN (solo agenda) |
| Chat dashboard | logueado | `ADMIN` | ADMIN (ventas y reportes) |

## Stack

| Capa | Tecnología |
|---|---|
| Runtime | Java 21 · Spring Boot 4.1.1 · Spring AI 2.0.1 |
| IA | Spring AI 2.0 (`ChatClient`, Advisors, Chat Memory, RAG, Tool Calling) |
| LLM | DeepSeek (`deepseek-flash`, soporta tool calling) detrás de `ChatClient` |
| Memoria | PostgreSQL, esquema `ia`, vía JDBC (`initialize-schema: never`); ventana de 10 |
| RAG | PostgreSQL + pgvector (mismo motor que el backend) |
| Evaluación | Dataset en `eval/` + LLM-as-a-Judge (fuera del `verify` de CI, regla R14) |

## Estructura del repositorio

```text
saaspa-IA/
├── AGENTS.md                    # fuente de verdad
├── pom.xml  mvnw  mvnw.cmd
├── docker-compose.yml           # infra de desarrollo (pgvector pg15 + redis)
├── .env.example
├── docs/
│   ├── adr/                     # 0001..000N
│   └── contracts/               # chat-api / internal-api (OpenAPI)
└── src/
    ├── main/java/com/juanp/saaspa/ia/   # código (raíz del proyecto Maven)
    ├── main/resources/                  # application.yml + db/migration (Flyway)
    └── test/java/com/juanp/saaspa/ia/
```

## Roadmap resumido

| Fase | Contenido |
|---|---|
| 0 | Alinear docs y contratos |
| 1 | Cerebro mínimo + chat web anónimo (en curso) |
| 2 | Agenda por chat + cliente logueado |
| 3 | Agente ADMIN + reportes |
| 4 | WhatsApp + RAG |
| 5 | Evaluación, costos y piloto |

## Decisiones clave

Cada decisión relevante está documentada en `docs/adr/`. Resumen:

- **Java/Spring AI como servicio separado** (cerebro), no dentro de NestJS.
- **NestJS como gateway de canales y ejecutor de herramientas** (dueño de auth, roles y lógica).
- **Multi-tenant híbrido** (aislamiento de RAG y base de datos).
- **Herramientas vía API HTTP interno** autenticado, no consultas directas a la BD desde Java.
- **Resolución de identidad del cliente por teléfono** (`waId`/teléfono → `User`), solo WhatsApp.
- **Turn token firmado (ES256/EdDSA)**: NestJS firma; este servicio solo verifica con la clave
  pública y lo reenvía en cada llamada al backend (ADR 0006).
- **Memoria JDBC en el esquema `ia`** con Flyway, sin Redis Stack (ADR 0007).
- **Escritura detrás de feature flag + confirmación explícita + idempotencia** (ADR 0008).

## Referencias

- [Spring AI Reference](https://docs.spring.io/spring-ai/reference/index.html)
- [Spring AI Examples](https://github.com/spring-projects/spring-ai-examples)
- Documentación del sistema: `docs-general/` (backend/frontend) y `docs/adr/` (este repo).

## Licencia

Por definir.
