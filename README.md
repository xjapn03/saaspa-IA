# saaspa-IA

Motor de IA conversacional de **Kamerinos SPA Bogotá** (centro de estética y bienestar).
Es un servicio **separado** en **Java 21 + Spring Boot 4 + Spring AI 2.0** que actúa como el
"cerebro" de los agentes conversacionales, mientras el backend **NestJS** (`saaspa-backend`)
sigue siendo el sistema de registro que ejecuta la lógica de negocio.

> **Estado:** Fase 0 (alineación y contratos). Nada implementado aún.
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
- **Single-tenant en el piloto** (Kamerinos). Multi-tenancy queda para después.
- **Evaluación automática en CI y control de costos**.

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
| Runtime | Java 21+ · Spring Boot 4 |
| IA | Spring AI 2.0 (`ChatClient`, Advisors, Chat Memory, RAG, Tool Calling) |
| LLM | Proveedor externo intercambiable detrás de Spring AI |
| Memoria | Redis |
| RAG | PostgreSQL + pgvector (mismo motor que el backend) |
| Evaluación | Dataset en `eval/` + LLM-as-a-Judge en CI |

## Estructura del repositorio (planeada)

```text
saaspa-IA/
├── backend/          # Spring Boot + Spring AI
├── docs/adr/         # decisiones de arquitectura
├── eval/             # datasets de evaluación de los agentes
├── docker-compose.yml
└── AI_WhatsApp_SaaS_Roadmap_2026.md
```

## Roadmap resumido

| Fase | Contenido |
|---|---|
| 0 | Alinear docs y contratos (este estado) |
| 1 | Cerebro mínimo + chat web anónimo |
| 2 | Agenda por chat + cliente logueado |
| 3 | Agente ADMIN + reportes |
| 4 | WhatsApp + RAG |
| 5 | Evaluación, costos y piloto |

## Decisiones clave

Cada decisión relevante está documentada en `docs/adr/`. Resumen:

- **Java/Spring AI como servicio separado** (cerebro), no dentro de NestJS.
- **NestJS como gateway de canales y ejecutor de herramientas** (dueño de auth, roles y lógica).
- **Single-tenant en el piloto.**
- **Herramientas vía API HTTP interno** autenticado, no consultas directas a la BD desde Java.
- **Resolución de identidad del cliente por teléfono** (`waId`/teléfono → `User`).

## Referencias

- [Spring AI Reference](https://docs.spring.io/spring-ai/reference/index.html)
- [Spring AI Examples](https://github.com/spring-projects/spring-ai-examples)
- Documentación del sistema: `docs-general/` (backend/frontend) y `docs/adr/` (este repo).

## Licencia

Por definir.
