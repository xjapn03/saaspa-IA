# ADR 0003: Multi-tenancy híbrida (aislamiento de RAG y base de datos)

- **Estado:** Aceptado
- **Fecha:** 2026-09-23
- **Sustituye a:** ADR 0003 (single-tenant) — descartado antes de su publicación.

## Contexto

El piloto es un único salón (Kamerinos SPA), pero el producto debe poder alojar a otras
empresas (otros salones, clínicas, consultorios) en el futuro. Se necesita una estrategia de
tenancy que no bloquee el piloto y que, a la vez, garantice aislamiento de datos y de
conocimiento (RAG).

## Decisión

Adoptar **multi-tenancy híbrida** con aislamiento por `tenant_id` en todos los datos del agente
(conversaciones, mensajes, tool_calls, documentos/vectores, uso, evaluación) y, progresivamente,
en la base de datos de negocio, con **filtro obligatorio por tenant** en cada consulta y en cada
recuperación RAG.

- **Modelo base:** *shared-schema, row-level* (`tenant_id` FK not-null en toda tabla scoped).
- **RAG aislado:** cada chunk/vector lleva `tenant_id`; la recuperación siempre filtra por el
  tenant resuelto (nunca cruza tenants).
- **Base de datos aislada:** capa central de resolución de tenant; repositorios scoped por tenant
  con tests de aislamiento.
- **Escalado (híbrido):** permite promover un tenant a *schema-per-tenant* o *database-per-tenant*
  (routing de datasource) para clientes grandes o con requisitos de cumplimiento, sin tocar la lógica.

### Resolución de tenant

| Canal | Cómo se resuelve |
|---|---|
| WhatsApp | `phone_number_id` (cada negocio tiene su propio número) |
| Chat web (anónimo/logueado) | subdominio (o `site`) del frontend |
| Chat dashboard | `tenant_id` del usuario autenticado (claim JWT) |

### Alcance por capa

- **Capa IA (`saaspa-IA`):** multi-tenant desde el día 1 (tablas propias + RAG + uso + eval).
- **Backend de negocio (`saaspa-backend`):** migración incremental (añadir `Tenant` y `tenant_id`,
  scoping de repositorios, seed del tenant `kamerinos`). Se hace en un track de fases, sin bloquear
  el piloto; hasta entonces el contrato ya transporta `tenant_id`.

## Consecuencias

- **Positivas:** escalable a nuevas verticales/empresas; aislamiento de conocimiento y datos
  verificado por tests; sin rediseño posterior.
- **Negativas:** mayor complejidad (filtros por tenant en cada consulta, tests de aislamiento,
  migración del backend existente); riesgo de fuga si un filtro se omite — mitigado con una capa
  central de scoping y tests de aislamiento.
