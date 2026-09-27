# ADR 0013: Handoff con destino, reversible y auditable

- **Estado:** Propuesta (pendiente de aceptación; no implementada)
- **Fecha:** 2026-09-26
- **Origen:** hallazgo **J-05** del informe conjunto; ola 1 del triaje.
- **Coordinación:** **requiere coordinación con saaspa-backend, no fusionar de un solo lado** (el estado, la
  notificación y la vista son suyos; el registro y el contrato son parcialmente nuestros).
- **Base:** A-10a, ya resuelto (el estado del handoff vive en NestJS y el bot no retoma la conversación).

## Contexto

- El handoff lo decide **el código** por subcadenas sobre el mensaje (`HandoffPolicy`: temas de salud, reclamos
  y petición explícita de hablar con una persona) con falsos positivos conocidos; el turno **no** llama al
  modelo y devuelve un texto canónico que **promete** que un asesor responderá.
- El backend guarda `handoffActive`/`handoffReason` por conversación (A-10a) y, desde que está activo,
  responde siempre ese texto sin llamar a este servicio. No hay notificación, correo, webhook, bandeja ni
  endpoint de administración, y **ningún campo permite desactivarlo**: hoy el latch es permanente.
- La conversación no es legible por ninguna persona: el backend guarda contadores y el último `turnId`; este
  servicio, con handoff, no llama al modelo y por tanto **no deja fila** en `turn_log` (A-08 y C-13), y su
  memoria no tiene API de lectura.
- WhatsApp tiene además su propio handoff (`ConversationState`), distinto de este.

## Decisión

1. **Propiedad:** el estado sigue siendo **por conversación y del backend** (A-10a). Este servicio no guarda
   estado de sesión; sigue informando `handoff.requested`/`reason` en cada turno.
2. **Reversible (requisito de esta ADR):** el estado debe poder desactivarse sin tocar la base de datos a mano
   —reapertura manual de una persona del salón o caducidad— con constancia de quién y cuándo. Mientras esté
   activo, el backend responde su texto canónico sin llamar a este servicio. Que la clienta vuelva a escribir
   no reabre el bot por sí solo: el estado es del servidor.
3. **Auditable desde este lado:** el turno derivado se registra en `ia.turn_log` con estado y motivo (hoy no
   deja fila: A-08/C-13) y el texto derivado debe poder recuperarse para que una persona pueda retomar la
   conversación (hoy no lo guarda nadie en ninguno de los dos lados).
4. **Relación con WhatsApp:** se deja constancia de que existe otro mecanismo de handoff para WhatsApp y de que
   su relación o unificación se decide **antes de la Fase 4**. Esta ADR no la resuelve.
5. **Decisión ABIERTA, de la persona (no resuelta ni dada por hecha en esta ADR):** el **destino** del aviso al
   salón.
   - **(a)** aviso por el canal que el salón ya usa (WhatsApp del local y/o correo del panel).
   - **(b)** bandeja de handoffs en el chat del dashboard.
   Las dos implican trabajo en `saaspa-backend`; la (b), además, en `saaspa-frontend`. Esta ADR no elige entre
   ellas: la pregunta queda registrada como pendiente y bloquea el diseño del destino, no el resto.
6. **Quién cierra el handoff:** una persona del salón. La interfaz concreta depende de la decisión anterior.

## Consecuencias

- **Positivas:** la promesa del texto canónico pasa a ser cumplible; un falso positivo del detector deja de ser
  permanente; la conversación derivada queda auditable y recuperable.
- **Negativas:** hay trabajo en los dos repos y una decisión de producto pendiente (el destino); si se elige la
  caducidad, añade un job más al backend; registrar el turno derivado obliga a decidir qué se guarda (texto y
  motivo, sin PII más allá de lo necesario — R8).
- **Pendiente de la persona:** aceptar la ADR y decidir el destino; la implementación va coordinada con el
  backend en la misma pasada.

## Referencias

- J-05 (`docs/reviews/2026-09-26-joint-integration-review.md`); triaje §3 y §4; A-08, A-10a y C-13
  (`AGENTS.md` §13).
- `docs/contracts/chat-api.openapi.yaml` (`handoff`) y `docs/contracts/web-chat-api.openapi.yaml`.
- ADR 0005 (identidad de WhatsApp) y reglas R8, R10 y R11 (`AGENTS.md` §5).
