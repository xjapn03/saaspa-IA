# Dataset de evaluación del agente CLIENTAS

Documenta la evaluación del agente (tarea T1.8) y crece con cada cambio de comportamiento (R15).

Los casos corresponden al prompt **`customer-agent.v2`** (2026-09-26). Al subir la versión del prompt hay
que revisar si el dataset sigue cubriendo el comportamiento nuevo (R15).

## Formato

`customer-agent.v1.jsonl`: un caso por línea (JSON). Las líneas vacías y las que empiezan por `#` se
ignoran.

| Campo | Tipo | Descripción |
|---|---|---|
| `id` | string | identificador estable del caso |
| `rule` | string | regla que cubre (`R3`, `R10`, `R11`, `A-12`, `A-14`, `B-01`, ...) |
| `message` | string | mensaje de la clienta |
| `handoff` | string | handoff esperado: `NONE` o `HEALTH_TOPIC` / `COMPLAINT` / `EXPLICIT_REQUEST` |
| `mustMatch` | string[] | expresiones regulares que la respuesta **debe** contener (por ejemplo el precio que devuelve la herramienta) |
| `forbid` | string[] | expresiones regulares que la respuesta **no** debe contener |
| `maxReplyChars` | integer? | longitud máxima de la respuesta en caracteres (`null`/ausente = sin tope); mide la brevedad |
| `gap` | boolean | `true` si es una brecha conocida de `HandoffPolicy` (A-14), pendiente de arreglo |

## Qué cubre

- **R10**: los tres motivos de handoff (`HEALTH_TOPIC`, `COMPLAINT`, `EXPLICIT_REQUEST`) y turnos
  normales que **no** deben derivar.
- **R11 / A-12**: precios que no deben inventarse (servicios fuera del catálogo).
- **R3**: intentos de ver datos de otra clienta, reportes o saltarse las instrucciones.
- **A-14**: falsos negativos (temas de salud que la lista deja pasar: celiaquía, marcapasos,
  quimioterapia) y falsos positivos (mensajes que la lista marca sin ser temas sensibles), marcados
  con `gap: true` mientras el arreglo siga diferido.
- **B-01 (ADR 0011)**: `B01-franja-liberada-por-expiracion` — la clienta dice que no alcanzó a pagar y
  pregunta si la hora sigue libre. El caso exige que la respuesta **(i)** traiga una hora concreta (formato
  `HH:mm`, el que devuelve la herramienta de disponibilidad) y **(ii)** no afirme que la cita o el pago
  anteriores siguen vigentes. Es un caso **real, no una brecha**: `saaspa-backend` ya expira las citas en
  `PENDIENTE_PAGO` (estado `EXPIRADA`, PR #77 fusionado) y libera la franja, y el enum de `Booking.status`
  del contrato interno ya la expone. El caso se apoya en la evaluación con LLM real (el modelo guionizado
  del build no produce la hora); si el backend dejara de exponer el estado, habría que marcarlo como
  brecha otra vez (patrón de A-14).

## Cómo se ejecuta

- **En el build normal (sin LLM, R14)**: `EvalDatasetTest` valida el formato y los casos deterministas
  de handoff; `CustomerAgentEvaluatorTest` ejercita el runner con un `ChatModel` guionizado. Ambos
  corren en `./mvnw -B verify`.
- **Evaluación con un LLM real (aparte, R14)**: el runner es `CustomerAgentEvaluator`. Se le pasa el
  `CustomerAgent` real y el dataset; el LLM-as-a-Judge y el informe reproducible llegan en la Fase 5.
  No se ejecuta en CI (cuesta tokens).
