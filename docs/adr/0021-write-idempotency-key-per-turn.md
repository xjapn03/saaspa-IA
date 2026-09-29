# ADR 0021: Clave de idempotencia por turno para las herramientas de escritura

- **Estado:** Aceptada (2026-09-28; decisión de la persona en la sesión de diseño de la Fase 2)
- **Fecha:** 2026-09-28
- **Implementación:** pendiente (forma parte de la primera herramienta de escritura de la Fase 2)
- **Origen:** tercera revisión conjunta (`docs/reviews/2026-09-28-joint-integration-review-3.md`, §5.3 y §6)
  y la sesión de diseño de la Fase 2 del 2026-09-28.
- **Base:** **ADR 0008** (política de escritura) y **ADR 0012** (identidad e idempotencia). Esta ADR las
  precisa en la forma de la clave: **no** las reemplaza.

## Contexto

- La ADR 0012 fijó que la `Idempotency-Key` la construye **el código** (nunca el modelo) como función del
  turno, pero dejó abierta la pregunta de si la clave debía identificar el **turno** o la **intención** de
  la clienta.
- El turno puede reintentarse **entero** (la clienta vuelve a mandar el mensaje tras un 504 del gateway o
  un corte de red): el `jti` es nuevo y la intención es la misma. Ese era el argumento a favor de la clave
  por intención.
- **Evidencia (verificado por la sesión de backend):** el backend ya protege la secuencia de turnos
  equivalentes con su **409 por solape**: una vez creada la primera cita, su franja queda ocupada
  (`PENDIENTE_PAGO` ocupa) y el segundo intento con el mismo `startTime` recibe 409, no una segunda cita.
  Con eso, el caso que motivaba la clave por intención queda cubierto **sin semántica nueva**. Queda una
  **carrera concurrente** (dos peticiones en vuelo para la misma franja antes de que ninguna confirme),
  residual y acotada por el tope de reservas pendientes por cuenta (2, PR #77 del backend) y por la vida
  corta del turn token.

## Decisión

1. **Clave por turno:** la `Idempotency-Key` es `<operación>:<jti>` —`crearCita:<jti>`,
   `reprogramarCita:<jti>`, `cancelarCita:<jti>`— para las tres herramientas de escritura. La construye
   siempre el código de este servicio (ADR 0012), con el `jti` **firmado** del turn token; el modelo jamás
   la toca ni la ve.
2. **Qué protege:** un reintento **dentro** del mismo turno (la herramienta reintenta tras un fallo de red,
   o se repite la llamada interna dentro del TTL del token) devuelve **el mismo recurso** sin duplicar, por
   el índice único del backend (patrón ya real en el POST público desde su PR #78).
3. **Turnos equivalentes en secuencia:** quedan protegidos por el **409 de solape** del backend (evidencia
   arriba), no por la clave. El agente lo traduce a la clienta como «esa franja ya no está disponible».
4. **Residual aceptado:** la carrera concurrente del contexto. Si se materializa, el resultado es una cita
   rechazada o, en el peor caso, una cita doble frenada por el tope de pendientes por cuenta.

## Descartes documentados

- **Clave por intención** (hash de `userId + serviceId + startTime + día`): se descarta porque (a) el 409 de
  solape ya cubre el caso secuencial que la motivaba, así que solo compraría la carrera concurrente;
  (b) definir «la misma intención» es una decisión de producto con bordes ambiguos (¿misma ventana de
  minutos? ¿el mismo día?); y (c) rompe flujos legítimos: **volver a reservar tras cancelar** la misma
  franja debe crear una cita nueva, y una clave por intención devolvería la cancelada.
- **Clave derivada del recurso (`bookingId + operación`)**: no existe antes de crear (huevo y gallina) y
  hereda los mismos problemas: recrear tras cancelar devolvería la cita cancelada, y reprogramar dos veces
  repetiría la primera respuesta como si fuera el estado actual. La igualdad que importa (la franja) vive
  en el dominio del backend, no en la clave.

## Disparador para reabrir la decisión

- Evidencia de citas duplicadas por reintentos (en `ia.tool_call_log`, en el `AuditLog` del backend o por
  reportes del salón), que la carrera concurrente se materialice, o una herramienta de escritura **sin**
  protección de solape (p. ej. carrito o pedidos). Con cualquiera de los tres se reabre y se evalúa la clave
  por intención o el pre-check de un pendiente equivalente.

## Consecuencias

- **Positivas:** semántica mínima y sin decisiones de producto nuevas; la protección fuerte (el solape)
  queda en el dueño del dato; la clave por turno se prueba con dos turnos y una herramienta, sin indexar
  intención; el reintento dentro del turno queda cubierto por diseño.
- **Negativas / riesgos:** el 409 de solape y el 409 del tope de pendientes llegan hoy con la misma forma, así
  que el agente no puede distinguirlos hasta que el backend añada el `code` estable pedido en §11.2; la
  carrera concurrente queda documentada y aceptada.
- **Del lado backend (pedido, no implementación de este repo):** respetar la `Idempotency-Key` en los tres
  endpoints internos con el patrón del POST público (PR #78) y añadir el `code` estable en el 409.

## Referencias

- ADR 0008 (política de escritura), ADR 0012 (identidad e idempotencia, punto 4), ADR 0014 (plazos del turno).
- Tercera revisión conjunta, §5.3 (restricciones) y §6 (paso 3):
  `docs/reviews/2026-09-28-joint-integration-review-3.md`.
- `docs/contracts/internal-api.openapi.yaml` v0.5.0 (endpoints de escritura con la cabecera obligatoria y la
  validacion de offset).
