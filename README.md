# saaspa-IA

Plataforma SaaS multi-tenant que conecta el **WhatsApp** de un negocio con un **agente de IA**.
La primera vertical es un **salón de estética / spa**: las clientas consultan servicios y productos, agendan citas y
hacen pedidos; la administración consulta ventas y reportes conversando con un segundo agente dentro del dashboard.

> **Estado:** en planificación / Fase 0. Este README describe el diseño objetivo; lo marcado como *(planeado)*
> aún no está implementado.

## Dos agentes, una plataforma *(planeado)*

**Agente de clientas** (WhatsApp y chat web)
- Aclara dudas de servicios y productos (RAG + catálogo).
- Consulta disponibilidad y **agenda, reprograma y cancela citas**.
- **Registra pedidos** de productos.
- Deriva a una persona ante temas sensibles (dudas médicas, reclamos) o cuando no está seguro.

**Agente de administración** (chat en el dashboard, con login y roles)
- Responde "¿cuánto vendimos hoy?", "¿qué servicio se pidió más este mes?", "¿qué citas hay mañana?",
  "¿qué productos tienen poco stock?".
- **Solo lectura**, con reportes predefinidos: las cifras las calcula el código, no el modelo.

## Principios de diseño

- **La IA conversa; el código decide.** Disponibilidad, precios y cifras salen de la base de datos, no del modelo.
- **Precios, horarios y stock no van en RAG**, van en tablas y se consultan con herramientas. El RAG es para
  texto descriptivo y políticas.
- **Agenda sin dobles reservas** mediante restricciones en PostgreSQL y transacciones.
- **Cada agente tiene solo sus herramientas**: el agente de clientas no tiene acceso a reportes.
- **Multi-tenant** con aislamiento de datos verificado por tests.
- **Evaluación automática** del agente en CI y **control de costos** por tenant.

## Qué NO hace (a propósito)

- No entrena ni fine-tunea modelos ni requiere GPU: usa un proveedor de LLM externo.
- No ofrece pagos en línea en el piloto (los pedidos se cierran en el local o por confirmación manual).
- No da consejos médicos: deriva a una profesional.
- No genera SQL libre con el modelo para los reportes.

## Stack

| Capa | Tecnología |
|---|---|
| Backend | Java 21+ · Spring Boot 4 · **Spring AI 2.0** · Spring Security · Spring Data · Spring Modulith |
| IA | `ChatClient`, Advisors, Chat Memory, RAG/ETL, Tool Calling, Observability (Spring AI) |
| LLM | Proveedor externo intercambiable detrás de Spring AI |
| Datos | PostgreSQL + **pgvector**, Redis, almacenamiento S3-compatible (MinIO en dev) |
| Frontend | Next.js · TypeScript · Tailwind CSS · shadcn/ui |
| Canales | Chat web · WhatsApp Business Platform (Cloud API) |
| Observabilidad | Micrometer / OpenTelemetry · Prometheus · Grafana |
| Infra | Docker · Docker Compose · GitHub Actions · VPS Linux |
| Testing | JUnit · Testcontainers · dataset de evaluación del agente |

## Arquitectura

```text
   Clienta                                 Administración
  /       \                                       |
Chat web  WhatsApp                          Dashboard (Next.js)
  \       /                                       |
   v     v                                        v
 Channel adapters                          Chat admin (autenticado)
        |                                         |
        v                                         v
  Agente CLIENTAS                            Agente ADMIN
  (RAG, Catálogo, Agenda,                    (reportes predefinidos,
   Pedidos, Handoff)                          solo lectura)
        \                                        /
         v                                      v
            LLM externo vía Spring AI
                    |
        PostgreSQL + pgvector · Redis
```

Módulos: `tenant`, `identity`, `channel`, `conversation`, `agent` (customer/admin), `knowledge`, `catalog`,
`booking`, `sales`, `reporting`, `usage`, `evaluation`. Detalle en el
[roadmap](./AI_WhatsApp_SaaS_Roadmap_2026.md).

## Estructura del repositorio *(planeada)*

```text
saaspa-IA/
├── backend/          # Spring Boot + Spring AI
├── frontend/         # Next.js: dashboard, chat web y chat admin
├── docs/
│   ├── adr/          # decisiones de arquitectura
│   └── images/       # diagramas y capturas
├── eval/             # datasets de evaluación de los agentes
├── docker-compose.yml
└── AI_WhatsApp_SaaS_Roadmap_2026.md
```

## Cómo correrlo *(planeado)*

Los comandos exactos se confirmarán al terminar la Fase 0. La idea:

```bash
cp .env.example .env        # configurar la API key del proveedor de LLM
docker compose up -d        # Postgres + pgvector, Redis, MinIO
# backend y frontend según las instrucciones de cada carpeta
```

Variables de entorno previstas:

| Variable | Descripción |
|---|---|
| `LLM_PROVIDER` | Proveedor de LLM activo |
| `LLM_API_KEY` | API key del proveedor (nunca se sube al repo) |
| `DATABASE_URL` | Conexión a PostgreSQL |
| `REDIS_URL` | Conexión a Redis |
| `WHATSAPP_VERIFY_TOKEN` / `WHATSAPP_APP_SECRET` | Verificación y firma del webhook de WhatsApp |

## Roadmap resumido

| Fase | Contenido |
|---|---|
| 0 | Repo, Docker Compose, CI y entrevista de descubrimiento con el salón |
| 1 | Chat web + `ChatClient` + memoria |
| 2 | RAG multi-tenant (servicios, políticas, FAQs) |
| 3 | Catálogo, agenda y herramientas de reserva |
| 4 | Dashboard operativo y registro de ventas |
| 5 | Agente de administración y reportes |
| 6 | Integración WhatsApp y despliegue del piloto |
| 7 | Pedidos, evaluación en CI, costos y pulido |

Plan completo, métricas del piloto, riesgos y checklist en
[`AI_WhatsApp_SaaS_Roadmap_2026.md`](./AI_WhatsApp_SaaS_Roadmap_2026.md).

## Decisiones clave

- **Spring AI en lugar de una capa propia de proveedores:** abstracciones portables para chat, embeddings,
  vector stores, tool calling y evaluación.
- **Proveedor externo + RAG en lugar de modelo local:** sin GPU y con costo variable controlable.
- **Reportes con herramientas predefinidas:** exactitud y seguridad frente a SQL generado por el modelo.
- **PostgreSQL + pgvector:** un solo motor para datos, agenda y vectores.
- **Chat web además de WhatsApp:** permite probar el proyecto sin configurar una cuenta de Meta.

Cada decisión relevante se documentará en `docs/adr/`.

## Referencias

- [Spring AI Reference](https://docs.spring.io/spring-ai/reference/index.html)
- [Awesome Spring AI](https://github.com/spring-ai-community/awesome-spring-ai)
- [Spring AI Examples](https://github.com/spring-projects/spring-ai-examples)

## Licencia

Por definir.
