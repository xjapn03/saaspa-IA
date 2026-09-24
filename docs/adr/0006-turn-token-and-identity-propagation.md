# ADR 0006: Propagación de identidad y autorización mediante turn token

- **Estado:** Aceptado
- **Fecha:** 2026-09-23

## Contexto

NestJS es el único punto de entrada: resuelve tenant, identidad y rol. Este servicio debe poder
invocar herramientas del backend sin posibilidad de suplantar identidades (reglas R1 y R2).

## Decisión

NestJS emite un **turn token**: un JWT firmado con **criptografía asimétrica (ES256 o EdDSA)**.
NestJS firma con la **clave privada**; este servicio **solo verifica con la clave pública**
(`TURN_TOKEN_PUBLIC_KEY`) y **nunca firma**.

- Claims mínimos: `iss`, `aud` (por ejemplo `saaspa-ia`), `iat`, `exp` **corta** (minutos),
  `jti` = `turnId`, `tenantId`, `conversationId`, `channel`, `agent`, `userId?`, `role?`.
- Este servicio **reenvía el turn token tal cual** en cada llamada a `/api/internal/v1/*`.
- **NestJS autoriza con la identidad del token**, nunca con campos que envíe Java ni con
  argumentos generados por el modelo.
- El turn token se propaga al contexto de las herramientas con `ToolContext` de Spring AI.
- `INTERNAL_API_KEY` se conserva **solo** como autenticación de transporte servicio-a-servicio,
  no como identidad.

**Alternativa descartada:** `INTERNAL_API_KEY` + headers de identidad. Permitiría suplantación si
este servicio se compromete.

## Consecuencias

- **Positivas:** identidad verificable e infalsificable por el modelo; expiración corta limita el
  impacto de una fuga; el backend sigue siendo el único que autoriza.
- **Negativas:** hay que gestionar el par de claves (rotación y distribución de la pública) y
  NestJS debe implementar la emisión/firma (ver “Pedidos a otros repos”).
