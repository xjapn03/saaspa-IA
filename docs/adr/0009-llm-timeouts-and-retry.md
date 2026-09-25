# ADR 0009: Timeouts y reintentos explícitos para la llamada al modelo

- **Estado:** Aceptado
- **Fecha:** 2026-09-25

## Contexto

La llamada al LLM (DeepSeek) no tenía timeout ni tope de reintentos explícito: usaba los valores por
defecto de Spring AI (`spring.ai.retry.max-attempts=10` con backoff exponencial que llega a minutos) y
el cliente del modelo no tenía timeouts HTTP propios. Un modelo colgado o con errores 5xx podía
reintentar durante minutos, con coste y con el turno bloqueado.

## Decisión

- Fijar `spring.ai.retry.max-attempts=2` con backoff acotado (`initial-interval=500ms`,
  `multiplier=2`, `max-interval=2s`) y `exclude-on-http-codes` con los 4xx no reintentables.
- Timeouts HTTP explícitos para el cliente del modelo (`saaspa.llm.connect-timeout=3s`,
  `saaspa.llm.read-timeout=30s`) mediante un `RestClient.Builder` dedicado; el cliente del backend
  sigue con los suyos y no se toca.
- Deadline por turno (`saaspa.llm.turn-deadline=35s`) que devuelve 504 si el turno se pasa del tope.

## Consecuencias

- **Positivas:** coste y latencia acotados; un fallo del proveedor termina rápido y con 504 en lugar
  de reintentar sin límite.
- **Negativas:** reintentos casi nulos ante 5xx transitorios del proveedor.

**Nota (no bloqueo):** tras A-02 los turnos de handoff ya no llaman al modelo, así que el deadline de
35s aplica sobre todo a catálogo/disponibilidad. Queda como valor a revisar con los datos reales de
latencia de T1.8/T1.9.
