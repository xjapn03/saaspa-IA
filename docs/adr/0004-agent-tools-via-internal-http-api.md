# ADR 0004: Herramientas del agente vía API HTTP interno

- **Estado:** Aceptado
- **Fecha:** 2026-09-23

## Contexto

El cerebro (Java) necesita leer catálogo y disponibilidad, crear/reprogramar/cancelar citas y
consultar reportes. Podría (a) acceder directamente a PostgreSQL, o (b) llamar a endpoints internos
de NestJS.

## Decisión

Las herramientas del agente **llaman a endpoints internos de NestJS** (`/api/internal/*`), nunca a
la base de datos directamente. Autenticación servicio-a-servicio con `INTERNAL_API_KEY` (header
`Authorization`), rate-limit y exposición solo en la red Docker interna (no en Nginx).

## Consecuencias

- **Positivas:** la lógica de negocio (locks, pagos, calendario, CAPI, validaciones) vive en un solo
  lugar; imposible que el agente y el backend diverjan.
- **Negativas:** latencia por HTTP; hay que proteger y versionar el API interno; el agente depende
  de la disponibilidad de NestJS.
