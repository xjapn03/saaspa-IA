# ADR 0003: Single-tenant en el piloto

- **Estado:** Aceptado
- **Fecha:** 2026-09-23

## Contexto

El roadmap v3 asumía una plataforma SaaS multi-tenant con `tenant_id` en todo el modelo y tests de
aislamiento. El producto real (Kamerinos SPA) es un único salón y el piloto es con ese único negocio.

## Decisión

Construir **single-tenant** para el piloto. No se introduce `tenant_id` ni aislamiento multi-tenant.
Se deja la puerta abierta (columna o tabla `tenant` futura) sin implementarla ahora.

## Consecuencias

- **Positivas:** elimina una gran fuente de complejidad (filtros, tests de aislamiento, migraciones)
  y acelera el piloto.
- **Negativas:** si luego se quiere SaaS, habrá que refactorizar el modelo de datos y los repositorios;
  se asume ese costo futuro conscientemente.
