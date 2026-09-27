# ADR 0012: Identidad de las herramientas de escritura e idempotencia

- **Estado:** Aceptada (2026-09-26)
- **Fecha:** 2026-09-26
- **Implementación:** pendiente (es bloqueante de cualquier herramienta de escritura de la Fase 2). No hay
  objeciones abiertas: solo falta implementarla.
- **Origen:** hallazgos **J-08** y **J-09** del informe conjunto; ola 1 del triaje.
- **Base:** **ADR 0008** (ya fija la `Idempotency-Key` derivada de `turnId` + acción) y **ADR 0006** (turn
  token con `userId`/`role`). Esta ADR no reemplaza a ninguna de las dos: las precisa para la escritura.

## Contexto

- El guard del backend deja la identidad verificada en `request.turn` y existe el decorador `@TurnContext`,
  pero **ningún** endpoint interno la usa: los dos de Fase 1 solo leen ruta y query. La primera vez que haga
  falta será la escritura, y ahí `GET /me/bookings` o `crearCita` podrían tomar el `userId` del cuerpo «porque
  es más fácil» sin que nada en el repo lo impida.
- El `jti` del turn token no se consume: el mismo token sirve para N llamadas dentro de su TTL (300 s por
  defecto) y el guard no distingue lectura de escritura.
- ADR 0008 ya decidió la idempotencia como política; lo que falta es formalizar **cuál es el sujeto** y qué
  significa exactamente «idempotente» para una escritura.

## Decisión

1. **El sujeto sale del token, siempre.** Toda operación de Fase 2 resuelve el sujeto con `turn.userId` y el rol
   con `turn.role`; **nunca** del cuerpo, la query ni argumentos generados por el modelo (R1). Normativo:
   `misCitas` devuelve las citas de `turn.userId`; `crearCita` crea para `turn.userId`; `reprogramarCita` y
   `cancelarCita` comprueban titularidad contra `turn.userId` (o rol `ADMIN`/`EMPLEADO` donde corresponda).
2. **Sin identidad no hay escritura:** si la operación necesita `userId` y el turn token no lo trae (canal
   anónimo), el backend responde **403**. No se auto-crea usuario y no se acepta un identificador de contacto
   que venga del cuerpo (ADR 0005 y su addendum).
3. **El contrato lo declara** (parte de este repo, `docs/contracts/internal-api.openapi.yaml`): todos los
   endpoints internos de Fase 2 llevan la nota normativa «el sujeto se resuelve desde el turn token
   (`turn.userId`); este endpoint no acepta identidad en el cuerpo ni en la query», y los esquemas de petición
   de escritura **no** exponen campos `userId`/`clientId`/`phone`.
4. **Idempotencia (según ADR 0008, precisada aquí):** la `Idempotency-Key` la construye **el código** —no el
   modelo— como función de `jti` (turnId) + operación + recurso; el backend garantiza que un reintento con la
   misma clave devuelve **el mismo recurso** sin duplicar. Un turno reintentado por timeout (J-04) no puede
   crear dos citas ni cobrar dos abonos.
5. **Ámbitos:** esta ADR **no** introduce un segundo token ni un ámbito nuevo en el turn token (el coste no se
   justifica hoy); documenta el riesgo de que un token filtrado dé capacidad de escritura durante su TTL y lo
   deja como decisión futura (acortar el TTL para escritura o token de un solo uso). Se descarta explícitamente
   un «token de escritura» separado en esta fase.
6. **Del lado backend (pedido; aquí no se implementa):** usar `@TurnContext()` en el primer endpoint que
   necesite identidad (el decorador deja de ser código muerto), añadir una prueba que falle si un handler
   interno lee identidad de `body`/`query`, y respetar la `Idempotency-Key` en `POST /bookings`.

## Consecuencias

- **Positivas:** la regla central del diseño queda ejercitada y comprobada en el primer endpoint que la
  necesita; las escrituras son atribuibles y no duplicables; el contrato impide que alguien «arregle» la
  identidad pasándola en el cuerpo.
- **Negativas:** con esta regla, un canal **anónimo** (widget sin sesión de cliente) no puede escribir, lo que
  limita la Fase 2 al cliente logueado. Es intencionado —sin identidad verificada no hay cita a nombre de
  nadie— pero deja abierto el modo invitada, que necesitaría una decisión propia (por ejemplo, cita con
  verificación por otro canal) y no se resuelve aquí.
- **Pendiente de la persona:** aceptar el ADR. La implementación del lado backend va **coordinada** en la misma
  pasada que el contrato.

## Referencias

- J-08 y J-09 (`docs/reviews/2026-09-26-joint-integration-review.md`); triaje §3 y §4.
- ADR 0008 (base: `Idempotency-Key`), ADR 0006 (turn token), ADR 0005 y su addendum (identidad).
- Reglas R1, R2 y R9 (`AGENTS.md` §5); `docs/contracts/internal-api.openapi.yaml` y `chat-api.openapi.yaml`.
