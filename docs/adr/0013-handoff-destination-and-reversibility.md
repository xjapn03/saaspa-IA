# ADR 0013: Handoff con destino, reversible y auditable

- **Estado:** Aceptada (2026-09-26)
- **Fecha:** 2026-09-26
- **Origen:** hallazgo **J-05** del informe conjunto; ola 1 del triaje.
- **Implementación:** **la mitad del backend está hecha y verificada** (correo al staff con motivo,
  conversación, turno, si es anónima o registrada y el **texto del turno derivado** —capturado por el
  backend, como exige esta ADR—, cierre reversible por `PATCH /chat/conversations/:id/handoff` con roles y
  auditoría, y entrega con reintento del aviso: H-03, su PR #82; evidencia archivo:línea en el informe #3,
  `docs/reviews/2026-09-28-joint-integration-review-3.md`, §3, fila J-05). De este lado queda el registro
  del turno con handoff en la memoria (C-13), que se resuelve junto con la memoria en la Fase 2. **Nota de
  estado únicamente: la decisión de esta ADR no cambia.**
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
- La conversación no es legible por ninguna persona: el backend guarda contadores y el último `turnId`, y **no
  tiene ningún modelo que guarde el texto** de los mensajes (`prisma/schema.prisma`: `ChatConversationState` y
  `ConversationState`, ninguno con el cuerpo del mensaje); este servicio, con handoff, no llama al modelo y por
  tanto **no deja fila** en `turn_log` (A-08 y C-13). Y, como se verificó en el código (sección de evidencia),
  **tampoco escribe el mensaje de la clienta en su memoria**: el corte ocurre antes de esa escritura.
- WhatsApp tiene además su propio handoff (`ConversationState`), distinto de este.

## Decisión

1. **Propiedad:** el estado sigue siendo **por conversación y del backend** (A-10a). Este servicio no guarda
   estado de sesión; sigue informando `handoff.requested`/`reason` en cada turno.
2. **Reversible (requisito de esta ADR):** el estado debe poder desactivarse sin tocar la base de datos a mano
   —reapertura manual de una persona del salón o caducidad— con constancia de quién y cuándo. Mientras esté
   activo, el backend responde su texto canónico sin llamar a este servicio. Que la clienta vuelva a escribir
   no reabre el bot por sí solo: el estado es del servidor.
3. **Auditable y recuperable:** el turno derivado se registra en `ia.turn_log` con estado y motivo (hoy no deja
   fila: A-08/C-13) y **el texto de la conversación derivada lo tiene que capturar el backend**, porque es el
   único que lo recibe: ni su base de datos ni la memoria de este servicio guardan el cuerpo del mensaje
   (evidencia abajo). El aviso debe llevar lo necesario para atender el caso sin abrir la base de datos:
   conversación, motivo, `turnId` y el texto del mensaje (o un puntero a donde quedó guardado).
4. **Relación con WhatsApp:** se deja constancia de que existe otro mecanismo de handoff para WhatsApp y de que
   su relación o unificación se decide **antes de la Fase 4**. Esta ADR no la resuelve, pero el motivo técnico
   del punto 5 (plantilla de Meta) también aplica a cualquier aviso saliente al staff por ese canal.
5. **Destino decidido (2026-09-26):** el aviso va **por correo electrónico al staff del salón**, reutilizando el
   módulo de correo que ya existe en `saaspa-backend` (`src/common/email/email.service.ts`, `email.module.ts`,
   dependencia `@sendgrid/mail`), y **no por WhatsApp**:
   - **Motivo técnico:** la API de WhatsApp Business exige una **plantilla pre-aprobada por Meta** para los
     mensajes salientes que caen **fuera de la ventana de 24 h** de conversación activa. Un aviso al staff es,
     por definición, un mensaje iniciado por el negocio y sin conversación previa con ese número, así que no es
     «gratis» ni inmediato: hay que dar de alta la plantilla, mantenerla aprobada y asumir su coste. WhatsApp
     queda como canal de la clienta, no del staff.
   - **La bandeja visual en el dashboard (opción b) queda pospuesta** hasta que exista el widget de chat en
     `saaspa-frontend` (hallazgo **J-02** del triaje): no se construye ahora, y mientras no exista el cierre del
     handoff no puede depender de una UI.
6. **Quién cierra el handoff y cómo:** una persona del salón, a partir del aviso por correo. Como todavía no hay
   dashboard, la reapertura tiene que ser una **acción del backend**, no de una pantalla, y quedar registrada
   con quién y cuándo (punto 2). El detalle de esa acción administrativa es del backend.

## Evidencia verificada: la memoria del agente en un turno con handoff

Pregunta: cuando `HandoffPolicy` corta el turno y no se llama al modelo, ¿el mensaje de la clienta que disparó
el handoff queda guardado en `ia.spring_ai_chat_memory`?

Respuesta: **no**. El corte ocurre **antes** de cualquier escritura, así que el backend **no puede reconstruir el
texto desde nuestra memoria** y tiene que capturarlo por su cuenta (punto 3 de la decisión).

Verificación (lectura del código y del bytecode de la dependencia, sin asumir):

1. El handoff se decide en `ChatController.java:90` y, cuando `handoff.requested()` es cierto, el controlador
   responde el texto canónico y **termina sin llamar al agente** (`ChatController.java:97-105`). La llamada al
   agente solo está en la rama `else` (`ChatController.java:108` → `replyWithDeadline`, `ChatController.java:155-157`).
2. La memoria solo se toca dentro de esa llamada: `CustomerAgent.reply()` es el único sitio de `src/main` que usa
   el `ChatClient` y el único que pasa `ChatMemory.CONVERSATION_ID` (`CustomerAgent.java:73-81`, en particular
   `:77`). Quien escribe es `MessageChatMemoryAdvisor`, declarado en `CustomerAgentConfig.java:45-48` y
   registrado como advisor por defecto del cliente en `CustomerAgentConfig.java:63`; no hay ningún otro escritor
   de `ia.spring_ai_chat_memory` en `src/main`.
3. En Spring AI **2.0.1** (`spring-ai-client-chat-2.0.1.jar`) ese advisor lee y escribe en dos momentos que solo
   existen **dentro de una llamada al modelo**: `before()` lee la memoria con `chatMemory.get(...)` y **escribe
   el mensaje nuevo de la clienta** (una `ChatMemory.add`), y `after()` **escribe la respuesta del modelo** (otra
   `ChatMemory.add`). Comprobado con `javap -c` sobre `MessageChatMemoryAdvisor`: `ChatMemory.add` aparece una
   vez en `before` y una vez en `after`. En un turno con handoff **no corre ninguno de los dos**: cero
   escrituras.
4. Corolario para leer el registro del E2E: en un turno donde la llamada al modelo **sí empieza**, el mensaje de
   la clienta ya quedó escrito por `before()`, así que un turno que falla después puede dejar una fila `USER` sin
   `ASSISTANT`. Es exactamente lo que muestra la evidencia de la Fase 1
   (`docs/contracts/f1-e2e-validation.md`, sección 2: una fila `USER` de memoria sin fila en `turn_log`).
5. Del otro lado, `saaspa-backend` **tampoco** guarda el texto: su esquema (`prisma/schema.prisma`) no tiene
   ningún modelo de mensajes y `ChatConversationState` solo guarda `handoffActive`, `handoffReason`, `lastTurnId`,
   `messageCount` y `lastMessageAt`. Conclusión: **hoy el texto del turno derivado no existe en ninguna parte**, y
   el único que lo tiene en la mano al vuelo es el backend (es el cuerpo de la petición que ya recibió).

## Consecuencias

- **Positivas:** la promesa del texto canónico pasa a ser cumplible; un falso positivo del detector deja de ser
  permanente; la conversación derivada queda auditable y recuperable. El destino elegido **no necesita plantillas
  de Meta ni coste por mensaje** y reutiliza el módulo de correo que el backend ya tiene en producción.
- **Negativas / riesgos:** el correo no garantiza lectura inmediata, así que el texto canónico que promete
  respuesta «muy pronto» conviene revisarlo (o medir el tiempo real de respuesta) con el destino elegido; el
  backend pasa a tener que **guardar el texto del turno derivado** —hoy no guarda ninguno— con su política de
  retención y sin PII más allá de lo necesario (R8); si se elige la caducidad del latch, añade un job más.
- **Pendiente:** implementar en el backend el aviso por correo, la captura del texto, la reversibilidad y la
  acción de reapertura. La relación con el handoff de WhatsApp se decide antes de la Fase 4.

## Referencias

- J-05 (`docs/reviews/2026-09-26-joint-integration-review.md`); triaje §3 y §4; J-02 (widget del chat, que
  bloquea la bandeja del dashboard); A-08, A-10a y C-13 (`AGENTS.md` §13).
- `docs/contracts/chat-api.openapi.yaml` (`handoff`) y `docs/contracts/web-chat-api.openapi.yaml`.
- `saaspa-backend`: `src/common/email/email.service.ts` y `email.module.ts` con la dependencia `@sendgrid/mail`
  (módulo de correo a reutilizar) y `prisma/schema.prisma` (sin modelo de mensajes: `ChatConversationState` solo
  guarda contadores y `lastTurnId`).
- ADR 0005 (identidad de WhatsApp) y reglas R8, R10 y R11 (`AGENTS.md` §5). La exigencia de plantilla aprobada
  por Meta fuera de la ventana de 24 h es la razón de descartar WhatsApp para el aviso al staff.
