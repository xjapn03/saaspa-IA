# ADR 0017: Frontera entre el error interno y el texto de la clienta

- **Estado:** Aceptada (2026-09-26)
- **Fecha:** 2026-09-26
- **Origen:** hallazgo **J-07** del informe conjunto (`docs/reviews/2026-09-26-joint-integration-review.md`) y
  ola 3 de su triaje.
- **Alcance:** la mitad de este repo es **autónoma** (catálogo de textos, `code` y log de diagnóstico). La otra
  mitad (la copia que ve la clienta y la forma del error del gateway) es de `saaspa-backend` y queda como
  **pedido de baja prioridad** (§11.5).
- **Base:** **R8** (no filtrar PII ni detalles internos) y el contrato `chat-api` de este repo.

## Contexto

NestJS llama a este servicio y, si el turno falla, **reenvía tal cual el `detail` de nuestro `ProblemDetail`
como texto para la clienta**: su `ia-bot.client.ts` hace `mapError(status, detail)` → `BadRequestException(detail)`,
`HttpException(detail || …, 429)`, `NotImplementedException(detail)` y `BadGatewayException(detail || texto_fijo)`,
y su `readProblemDetail` solo extrae `detail` (y `scope`) de la respuesta. Es decir: **lo que escribamos en
`detail` acaba en el chat**, aunque el nombre del campo invite a pensar lo contrario.

Lo que este servicio devolvía hasta esta ADR (revisado caso por caso):

| Caso | `detail` de entonces | Problema |
|---|---|---|
| 400 contexto del turno | `exception.getMessage()`: *"El campo conversationId del cuerpo no coincide con el turn token"* | ❌ jerga interna (campos, turn token) y **alcanzable** |
| 501 agente no implementado | `exception.getMessage()`: *"El agente ADMIN estara disponible en la Fase 3"* | ❌ referencia al roadmap |
| 401 (filtro y advice) | *"Falta el turn token"*, *"Turn token invalido, expirado o de otra audiencia"* | ⚠️ jerga; además distinguía el caso exacto |
| 403 (filtro y tenant) | *"El turn token no permite esta operacion"*, *"El tenant del turno no esta permitido en este servicio"* | ⚠️ jerga y mención al servicio |
| 400 cuerpo / JSON, 429, 502, 504, 500 | textos genéricos ya aptos para la clienta | ✅ |

R8 en sentido estricto **no se incumplía**: ningún `detail` llevaba nombre de excepción, detalle del proveedor
del LLM ni PII (el 500 es genérico y los logs solo registran el tipo de excepción). Lo que fallaba es la
**frontera interna/pública**: mensajes de diagnóstico viajando a una clienta. Y un segundo hueco: el filtro de
la clave de servicio **no registraba nada**, así que los rechazos repetidos no dejaban rastro ni del lado del
servidor.

Sobre la **forma** de los errores: los tres contratos declaraban RFC 9457, pero el backend responde el formato
por defecto de NestJS (`{statusCode, message, error}`; no tiene filtro global de excepciones). `chat-api` sí
cumple RFC 9457 porque **lo sirve este servicio**; `internal-api` y `web-chat-api` describían una forma que su
servidor no produce.

## Decisión

1. **Catálogo cerrado de textos (`ProblemCode`).** Cada error tiene un estado, un título y un **texto público**,
   y el `detail` sale siempre de ahí: **nunca** de `exception.getMessage()`. Los títulos también son textos
   públicos (se cambió, por ejemplo, *"Tenant no permitido"* por *"Peticion no permitida"*). Los textos
   técnicos que sí hacían falta para el diagnóstico (qué campo no cuadró, qué excepción se lanzó) se siguen
   usando… en el log.

2. **`code` estable en el `ProblemDetail`.** Propiedad nueva (`TURN_CONTEXT_MISMATCH`, `COST_LIMIT`,
   `AGENT_NOT_IMPLEMENTED`, `BACKEND_UNAVAILABLE`…): no es PII, es estable y es lo que permite que el gateway
   mapee por código en vez de leer prosa, además de ser el ancla de los casos de conformidad entre repos
   (J-13). El texto puede cambiar sin romper a nadie; el código no.

3. **El motivo técnico, al log.** Cada manejador registra su diagnóstico (el mensaje técnico o el tipo de
   excepción), sin token ni datos personales (R8). En particular, el manejador de seguridad **ahora registra**
   —antes un 401 o un 403 repetidos no dejaban rastro alguno— y deja de contar a quien llama **qué**
   comprobación falló (clave de servicio, firma del turno, caducidad, audiencia).

4. **Cada contrato dice la verdad en su dirección.** `chat-api` mantiene RFC 9457 (lo sirve este servicio) y
   documenta el `code`; `internal-api` **v0.3.1** describe el `NestError` real del backend
   (`{statusCode, message, error}`) y explica que el cliente de este servicio lo mapea a sus excepciones.
   `web-chat-api` queda **pendiente a propósito**: `saaspa-backend` está implementando `problem+json` real
   para su `ChatController` público (con las extensiones `scope`/`measure`/`measured`/`limit`/`window`); cuando
   fusione, ese contrato se corregirá para **confirmar `Problem`** —con el catálogo de `code` integrado— y no
   para revertirlo a la forma de NestJS. Hasta entonces **no se toca**.

5. **Reparto de responsabilidades (la frontera).** Nosotros garantizamos que `detail` es apto para una clienta
   y damos un `code` estable; el gateway decide si reenvía nuestro texto o pone el suyo, y registra nuestro
   `detail` con el `turnId`. Con lo primero hecho, lo segundo deja de ser urgente: se documenta como **pedido
   de baja prioridad** en §11.5 y **no se envía**. Si algún día el salón quiere otro tono, ese cambio es suyo.

6. **Lo que se decide NO hacer:** forzar RFC 9457 en todo el backend ahora. No tiene filtro global de errores
   y su cambio arrastraría al frontend; con el widget aún sin construir (J-02), la vía barata y suficiente es
   que cada contrato describa lo que su servidor produce de verdad y que `web-chat-api` espere a su PR.

## Consecuencias

- **Positivas:** ningún camino de error de este servicio puede llevar jerga interna, identificadores ni
  referencias al roadmap a una clienta (hay un test-guarda que lo fija para el catálogo entero); el
  diagnóstico no se pierde, se mueve al log; los rechazos de seguridad dejan rastro; el contrato de error dice
  la verdad en las dos direcciones; y el `code` da al gateway (y a J-13) algo estable sobre lo que decidir.
- **Negativas / riesgos:** el catálogo hay que mantenerlo (un texto nuevo pasa por el test-guarda, que es
  justamente lo que se busca); el texto que ve la clienta sigue siendo el nuestro mientras el gateway lo
  reenvíe, así que su tono es de producto y conviene revisarlo si el salón cambia de voz; y la corrección de
  `web-chat-api` queda **dependiendo del PR del backend**, anotada para no perderla.
- **Sin cambio de comportamiento para el agente:** no se toca el prompt, las herramientas ni `eval/`.

## Referencias

- J-07 (`docs/reviews/2026-09-26-joint-integration-review.md`); triaje §3 y §4; R8 (`AGENTS.md` §5).
- Código: `api/ProblemCode.java`, `api/ApiExceptionHandler.java`,
  `security/ProblemDetailSecurityHandler.java`, `security/ServiceKeyAuthenticationFilter.java`.
- Tests: `ProblemCodeTest` (guarda de textos: solo letras, espacios y puntuación, sin jerga ni roadmap; y
  cada código con estado de error), `ChatControllerTest` (los siete códigos, el texto público y el motivo en
  el log) y `ChatApiSecurityTest` (401 con código y texto apto).
- Contratos: `docs/contracts/chat-api.openapi.yaml` (RFC 9457 y `code`) y
  `docs/contracts/internal-api.openapi.yaml` (v0.3.1, `NestError`).
