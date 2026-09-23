# ADR 0002: NestJS como gateway de canales y ejecutor de herramientas

- **Estado:** Aceptado
- **Fecha:** 2026-09-23

## Contexto

El producto ya tiene auth (JWT + roles `CLIENTE`/`EMPLEADO`/`ADMIN`), webhook de WhatsApp,
`ConversationState`, y toda la lógica de negocio (agenda con Redis slot-locking, pagos Wompi,
e-commerce, Google Calendar, Meta CAPI).

## Decisión

NestJS es el **único punto de entrada** de todos los canales (WhatsApp, chat web, chat dashboard)
y el **único ejecutor** de las herramientas del agente. Resuelve identidad + rol y normaliza un
"turno" que envía al cerebro Java (`POST {IA_BOT_URL}/api/v1/chat`). El cerebro responde y, cuando
necesita una herramienta, llama al API interno de NestJS.

## Consecuencias

- **Positivas:** una sola fuente de verdad para auth, roles y lógica; reutiliza 324 tests backend;
  el agente no puede saltarse las validaciones existentes.
- **Negativas:** NestJS participa en dos saltos por turno (gateway y ejecutor); requiere un API
  interno con autenticación servicio-a-servicio.
