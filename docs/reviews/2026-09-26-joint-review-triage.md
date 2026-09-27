# Triaje de la revisión conjunta de integración (J-01 a J-13)

- **Estado:** **mapeo, no implementación.** Este documento asigna cada hallazgo del informe conjunto a una
  **ADR** (R16) o a una **tarea de checklist**, con la rama propuesta. No se ha escrito ninguna ADR, no se ha
  tocado código y **no se abre ninguna rama de implementación** hasta que la persona lo pida.
- **Fecha:** 2026-09-26 (rama `docs/triage-joint-review`).
- **Informe triado:** `docs/reviews/2026-09-26-joint-integration-review.md` (revisión externa de
  `saaspa-backend@9fc8b12` ↔ `saaspa-IA@e4fe8a2`).
- **Orden de prioridad:** **fijado por la persona** (no se recalcula aquí): primero los cuatro que bloquean
  cualquier herramienta de escritura de Fase 2, en paralelo J-01 y J-02 (bloquean el piloto), en la misma
  pasada J-04, J-06 y J-07 (baratos), y al final J-10 a J-13.
- **Regla de R16:** una decisión nueva se registra como **ADR antes** de implementarla; por eso varias filas
  proponen una rama `docs/adr-00NN-…` (solo el ADR) y la rama de implementación viene **después** de que el
  ADR esté aceptado. Los números 0010 en adelante están **reservados por este triaje**; el ADR se redacta al
  abrir cada trabajo.

## 1. Cómo leer este triaje

- **`B-01`** es un ID de este triaje: el informe no numeró el expiro de `PENDIENTE_PAGO` (§5 punto 1 y §9
  bloqueante 2). Se etiqueta para poder referenciarlo en el checklist.
- **Cruce de repos:** cuando el trabajo no cae solo aquí se marca
  **`requiere coordinación con saaspa-backend, no fusionar de un solo lado`** (J-04, J-05 y J-07, por
  indicación expresa) o se indica el repo destino (J-01 → `kamerinos-infra` + backend; J-02 →
  `saaspa-frontend`).
- **Regla del repo (§2):** en este repo **nunca** se implementa nada de los otros repos; su parte es el ADR,
  el contrato (`docs/contracts/`) y el pedido en §11, y la persona lo implementa en su repo.
- **Ninguna rama listada aquí se ha abierto.** El "tipo" propuesto usa la nomenclatura de §9
  (`fix/…`, `feature/…`, `docs/…`).

## 2. Olas de ejecución (fijadas)

| Ola | Qué | Hallazgos | Por qué |
|---|---|---|---|
| **1** | Bloqueantes de **cualquier** herramienta de escritura de Fase 2 | J-03, B-01, J-08 + J-09, J-05 | Sin esto, una escritura es automatizable, atribuible a nadie, duplicable o deja a la clienta fuera del canal |
| **2** | Bloquean el **piloto**, en paralelo a la ola 1 | J-01, J-02 | Sin `ia-bot` desplegado y sin widget no hay piloto, aunque el código esté listo |
| **3** | **Misma pasada** que las anteriores (deuda barata) | J-04, J-06, J-07 | Los tres se vuelven caros justo cuando hay dinero de por medio (reintentos, hora de la cita, diagnóstico de un fallo) |
| **4** | **Pueden esperar** | J-10, J-11, J-12, J-13 | Higiene/proceso; J-10 conviene meterlo con el despliegue |

## 3. Tabla maestra

| ID | Sev. | Ola | Decisión (ADR propuesta) | Rama aquí / coordinación |
|---|---|---|---|---|
| J-03 | Alta | 1 | **ADR 0010** — abuso y coste: límites por tenant/sesión/cuenta | `docs/adr-0010-abuse-and-cost-controls` (docs) y luego `feature/f2-per-tenant-cost-guard` (feature); el `trust proxy`/nginx y el tope por cuenta son de backend e infra |
| B-01 | Alta | 1 | **ADR 0011** — expiro de `PENDIENTE_PAGO` y tope de reservas pendientes | Trabajo en `saaspa-backend` (pedido); aquí solo contrato + dataset |
| J-08 | Media | 1 | **ADR 0012** — el sujeto de una escritura sale del turn token | `docs/adr-0012-write-identity-and-idempotency` (docs) + contrato; la prueba de que ningún handler lee identidad del cuerpo es de backend |
| J-09 | Baja-media | 1 | **ADR 0012** (misma decisión: ámbitos, TTL de escritura e `Idempotency-Key` atada a `jti` + operación) | Igual que J-08 |
| J-05 | Media-alta | 1 | **ADR 0013** — handoff con destino, reversible y auditable | **requiere coordinación con saaspa-backend, no fusionar de un solo lado** |
| J-01 | Alta | 2 | Sin ADR (operativo) | `kamerinos-infra` (`ia-bot`, variables, health check obligatorio) + backend (validar la config al arrancar) |
| J-02 | Alta | 2 | **ADR 0017** — dónde vive el widget y qué significa "Fase 1 validada" | `saaspa-frontend` (tercer repo): aquí solo el ADR y el estado en README/AGENTS.md |
| J-04 | Alta | 3 | **ADR 0014** — escalera de plazos, correlación y estado de los turnos fallidos | **requiere coordinación con saaspa-backend, no fusionar de un solo lado** (la mitad de los puntos sí es nuestra) |
| J-06 | Media | 3 | **ADR 0015** — verificación del acople y campos muertos (`locale`/`timezone`/`now`) | `feature/actuator-info-tenant-and-timezone` (feature, nuestro lado); la comprobación en el otro lado y la decisión sobre los campos son coordinadas |
| J-07 | Media | 3 | **ADR 0016** — contrato de error end-to-end y texto de cara a la clienta | **requiere coordinación con saaspa-backend, no fusionar de un solo lado** |
| J-10 | Baja-media | 4 | **ADR 0018** — identificador de conversación/sesión emitido por el servidor y retención | `docs/adr-0018-conversation-id-and-retention` (docs) + `fix/conversation-id-format-validation` (fix) y `feature/memory-retention-and-purge` (feature, Fase 5); el vínculo sesión↔conversación es de backend |
| J-11 | Baja | 4 | Sin ADR (higiene de contrato) | Pedido a backend (nombre de cabecera por dirección) + ajuste de `BackendClient` cuando se toque |
| J-12 | Baja | 4 | Sin ADR (correlación) | Pedido a backend (comparar `iaResponse.turnId`); aquí solo la nota de contrato |
| J-13 | Baja | 4 | Sin ADR (herramienta de proceso) | `feature/contract-conformance-cases` (feature) aquí; el backend ejecuta los mismos casos |

## 4. Detalle por hallazgo

### Ola 1 — bloqueantes de la escritura de Fase 2

**J-03 — El control de abuso se evade con una cabecera (Alta).**
- *Aquí:* no hay nada implementado de abuso/coste (0 coincidencias de `Throttl/RateLimit` en `src/main`).
  La parte nuestra es la decisión (ADR 0010) y, después, el tope global por tenant y el coste por conversación
  (ya propuesto como **A-06** en §13; el triaje lo confirma y lo eleva a bloqueante).
- *Otros repos:* `kamerinos-infra` (nginx: `X-Forwarded-For $remote_addr`, o `trust proxy` numérico en el
  backend + prueba de que un XFF inyectado no cambia el bucket) y `saaspa-backend` (tope de sesión sobre un
  identificador emitido por el servidor, no la cookie cruda — enlaza con J-10).
- *ADR:* **0010** (abuso y coste: por IP, por sesión, por tenant y por cuenta al crear citas).
- *Checklist:* Fase 2, «antes de la primera herramienta de escritura»; más el pedido a infra/backend en §11.
- *Rama:* `docs/adr-0010-abuse-and-cost-controls`; después `feature/f2-per-tenant-cost-guard` (feature, en
  Fase 2). Lo demás es pedido, no rama.

**B-01 — Expiro de las citas en `PENDIENTE_PAGO` + tope de reservas pendientes (Alta; ID de este triaje).**
- *Aquí:* nada de runtime. Nuestra parte es el contrato (`internal-api.openapi.yaml`: qué devuelve una cita
  expirada y qué código/estado se espera) y un caso en `eval/` para el reintento tras expirar.
- *Otros repos:* `saaspa-backend` (trabajo de expiración; hoy `findOccupied` cuenta como ocupada toda cita que
  no sea `CANCELADA`/`NO_ASISTIO` y no hay ningún job que expire, `crontab` solo hace backups).
- *ADR:* **0011** (política de expiración y tope de reservas pendientes por usuario).
- *Checklist:* Fase 2, bloqueante; pedido al backend en §11.
- *Rama:* ninguna aquí salvo el ADR; la implementación es del backend.

**J-08 + J-09 — La identidad del turn token no la lee nadie; el token es reutilizable y sin ámbitos.**
- *Aquí:* declarar en `internal-api.openapi.yaml` que **todo** endpoint de Fase 2 resuelve el sujeto desde
  `turn.userId`/`turn.role` (nunca del cuerpo ni de argumentos del modelo, R1) y fijar los ámbitos
  lectura/escritura y la `Idempotency-Key` derivada de `jti` + operación (extiende **ADR 0006** y **ADR 0008**).
- *Otros repos:* `saaspa-backend` es quien lee el token: usar `@TurnContext()` en el primer endpoint que lo
  necesite, una prueba que falle si un handler interno toma identidad de `body`/`query`, y `Idempotency-Key`
  en `POST /bookings`.
- *ADR:* **0012** (autorización de las herramientas de escritura: sujeto desde el token, ámbitos e
  idempotencia).
- *Checklist:* Fase 2, bloqueante; `misCitas` (solo lectura) puede avanzar ya, porque también se resuelve
  desde `turn.userId` y sirve de primera prueba de la regla.
- *Rama:* `docs/adr-0012-write-identity-and-idempotency` (aquí: ADR + contrato); la implementación
  va al backend en la misma pasada, así que **la pasada se coordina** aunque nuestra parte sea aislable.

**J-05 — El handoff no lo recibe nadie y es irreversible (Media-alta).**
- *Aquí:* registrar el turno derivado en `turn_log` con estado y motivo (hoy, con A-02, el turno con handoff no
  llama al modelo y **no deja fila**: enlaza **A-08** y **C-13**); caso en `eval/` para el falso positivo de
  catálogo. Nada de estado de sesión: sigue siendo de NestJS (A-10a, ya resuelto).
- *Otros repos:* `saaspa-backend` (destino real del handoff —aviso al salón o bandeja del dashboard—, quién lo
  cierra, reversibilidad con caducidad o reapertura, y vista legible de la conversación). §11.3 y §13 de este
  repo documentan el contrato actual.
- *ADR:* **0013** (propiedad, destino y reversibilidad del handoff; relación con el handoff de WhatsApp de la
  Fase 4).
- *Checklist:* Fase 2, bloqueante.
- *Rama:* **requiere coordinación con saaspa-backend, no fusionar de un solo lado.**

### Ola 2 — bloquean el piloto, en paralelo

**J-01 — El despliegue no puede servir un turno (Alta).**
- *Aquí:* nada de código; la lista de variables ya está en §11.4 y el pedido en §11.5.
- *Otros repos:* `kamerinos-infra` (servicio `ia-bot` en la red interna, sin `ports:`, `IA_BOT_URL:
  http://ia-bot:8000`, variables **sin valores por defecto** y health check obligatorio en `deploy.sh`) y
  `saaspa-backend` (validar la configuración al arrancar para que un despliegue incompleto falle al arrancar y
  no en el primer turno de la primera clienta).
- *ADR:* no procede (es operativo). Si el backend quiere formalizar la validación de configuración, su ADR es
  suyo.
- *Checklist:* §11.5 (infra) y Fase 5 (piloto). Se puede pagar en paralelo: **no bloquea escribir código**.
- *Rama:* ninguna en este repo.

**J-02 — El «chat web» no tiene interfaz (Alta).**
- *Aquí:* decidir y dejar escrito qué significa «Fase 1 validada» (hoy: por API) y cuándo entra el widget.
- *Otros repos:* `saaspa-frontend` (widget mínimo: mensaje, `conversationId` persistido, textos de error
  propios, 413/429/502/504 diferenciados y superficie para la confirmación explícita que exige R9/ADR 0008).
- *ADR:* **0017** (dónde vive el widget y qué se declara validado en la Fase 1).
- *Checklist:* Fase 2/5 (piloto), en paralelo a la ola 1.
- *Rama:* el trabajo es del tercer repo; aquí solo el ADR y el estado en README/AGENTS.md.

### Ola 3 — baratos, en la misma pasada

**J-04 — Escalera de timeouts invertida y turno fantasma (Alta).**
- *Aquí (nuestra mitad):* bajar `saaspa.llm.read-timeout` a 8-10 s y `turn-deadline` a 20 s (hoy 30 s/35 s y
  hasta ~61 s de peor caso con el reintento de ADR 0009), probar que el deadline **cancela** la lectura en
  vuelo y que un turno abandonado no deja respuesta en la memoria, devolver el `turnId` en el `ProblemDetail`
  y registrar el turno fallido en `turn_log` con estado (hoy no deja fila: **A-08**).
- *Otros repos:* `saaspa-backend` fija su `IA_BOT_TIMEOUT_MS` por encima del deadline (25 s) y es quien ve el
  504 real; la nota compartida de plazos vive en `docs/contracts/` (nuestra).
- *ADR:* **0014** (plazos end-to-end, correlación y estado de los turnos fallidos; extiende ADR 0009).
- *Checklist:* Fase 2, «misma pasada» (el reintento del widget no puede duplicar una cita).
- *Rama:* **requiere coordinación con saaspa-backend, no fusionar de un solo lado.** Los números solo tienen
  sentido si los dos lados cambian a la vez y se documentan juntos.

**J-06 — Zona horaria y tenant acoplados a mano (Media).**
- *Aquí:* publicar el acople para que sea comprobable (por ejemplo `saaspa.tenant.*` en `/actuator/info`, que
  hoy no refleja ni LLM ni backend: **A-13**), y decidir qué pasa con `locale`/`timezone`/`now`: usarlos o
  quitarlos del contrato (hoy son obligatorios y **muertos**: no llegan al prompt).
- *Otros repos:* `saaspa-backend` (avisar o fallar en el arranque si su `TENANT_ID`/`TENANT_TIMEZONE` no
  coinciden con lo que publica este servicio) y el contrato `chat-api` si se retiran campos.
- *ADR:* **0015** (verificación del acople y campos muertos del chat). El lado documental del acople ya está en
  **§11.6** y el límite («advertencia manual, sin comprobación en runtime ni en CI») en el hallazgo J-06.
- *Checklist:* Fase 2, «misma pasada».
- *Rama:* `feature/actuator-info-tenant-and-timezone` (feature, solo nuestro lado, aislable); la comprobación en
  el otro lado y la retirada de campos del contrato **se coordinan**.

**J-07 — El contrato de error no es el implementado (Media).**
- *Aquí:* los tres contratos viven en `docs/contracts/`, así que la corrección documental es nuestra (o se
  documenta la forma real de Nest, o se acuerda RFC 9457 en los dos lados), y el texto de cara a la clienta no
  puede ser el `detail` interno de IA.
- *Otros repos:* `saaspa-backend` (filtro global de errores si se elige RFC 9457, mapeo de los errores de IA a
  texto fijo para la clienta y registro del `detail` con el `turnId`) y `saaspa-frontend` (su tipo de error).
- *ADR:* **0016** (contrato de error end-to-end y frontera entre error interno y mensaje a la clienta).
- *Checklist:* Fase 2, «misma pasada».
- *Rama:* **requiere coordinación con saaspa-backend, no fusionar de un solo lado.**

### Ola 4 — pueden esperar

**J-10 — Vínculo entre conversación y sesión perezoso (Baja-media).**
- *Aquí:* validar el formato del `conversationId` (`^[a-f0-9]{32}$`) en vez de aceptar una cadena libre, y
  poda de la memoria con política de retención (**A-17**).
- *Otros repos:* `saaspa-backend` (emitir siempre el id en el servidor, atarlo a una sesión firmada y podar
  `chat_conversation_states`).
- *ADR:* **0018** (identificador de conversación/sesión emitido por el servidor y retención).
- *Checklist:* Fase 5 (la poda conviene meterla con el trabajo de despliegue, no después).
- *Rama:* `docs/adr-0018-conversation-id-and-retention`, luego `fix/conversation-id-format-validation` (fix) y
  `feature/memory-retention-and-purge` (feature); el vínculo sesión↔conversación es pedido.

**J-11 — Una cabecera, dos secretos (Baja).**
- *Aquí:* `BackendClient` (nombre de cabecera propio para la dirección IA → NestJS) cuando se toque el cliente.
- *Otros repos:* `saaspa-backend` (`ia-bot.client` con su cabecera) y registrar la dirección en los fallos de
  clave.
- *ADR:* no procede (higiene). *Checklist:* Fase 5 o la próxima vez que se toque `/api/internal/v1`.
- *Rama:* `fix/split-service-key-headers` (fix) cuando se aborde; **se coordina** con el backend, porque cambiar
  una cabecera de un solo lado rompe la integración.

**J-12 — El `turnId` de vuelta no se comprueba (Baja).**
- *Aquí:* nada de runtime (la comparación es de quien recibe la respuesta). *Otros repos:* `saaspa-backend`
  (comparar `iaResponse.turnId` con el emitido; si no coincide, 502 con registro).
- *ADR:* no procede. *Checklist:* Fase 5, como pedido. *Rama:* ninguna aquí.

**J-13 — Sin pruebas de conformidad entre repos (Baja).**
- *Aquí:* el archivo de casos compartido (petición → respuesta/código/forma de error) versionado en
  `docs/contracts/`, y su ejecución contra nuestro cliente con WireMock.
- *Otros repos:* `saaspa-backend` ejecuta los mismos casos contra sus controladores (supertest). Es la causa
  raíz de J-07, J-11 y de los campos muertos de J-06.
- *ADR:* no procede (herramienta de proceso). *Checklist:* Fase 5 (empezando por errores y los dos endpoints
  internos que ya existen). *Rama:* `feature/contract-conformance-cases` (feature); la ejecución cruzada es
  pedido.


## 5. Pedidos derivados (consolidado, para §11)

| Destino | Pedido | Hallazgo |
|---|---|---|
| `kamerinos-infra` | `ia-bot` en la red interna sin `ports:`; variables sin valores por defecto; `IA_BOT_URL: http://ia-bot:8000`; health check obligatorio; nginx con `X-Forwarded-For` sobrescrito o `trust proxy` acotado | J-01, J-03 |
| `saaspa-backend` | Validar la configuración al arrancar; expiro de `PENDIENTE_PAGO` y tope de reservas; identidad desde el token con prueba; `Idempotency-Key`; handoff con destino y reversible; escalera de plazos; filtro de error y texto a la clienta; comprobación del acople tenant/zona; poda y sesión emitida por el servidor; `turnId` de vuelta; casos de conformidad | J-01, B-01, J-08/J-09, J-05, J-04, J-07, J-06, J-10, J-12, J-13 |
| `saaspa-frontend` | Widget mínimo con errores diferenciados y superficie de confirmación (R9) | J-02, J-07 |

## 6. Solapes con §13 (no duplicar)

- J-03 ↔ **A-06** (sin tope de coste/turnos por tenant): el triaje lo eleva a **bloqueante de la escritura**.
- J-04 ↔ **A-08** (turnos fallidos sin registro) y **A-15** (sin correlación ni métricas).
- J-05 ↔ **C-13** (los turnos con handoff no entran en la memoria) y **A-10a** (ya resuelto en el backend: el
  estado y quién no retoma; lo que falta es destino, reversibilidad y visibilidad).
- J-06 ↔ **C-02** (resuelto en su parte de offset) y **A-13** (health/info no reflejan el estado real).
- J-10 ↔ **A-17** (sin retención ni borrado de la memoria) y **A-04/A-05** (config y RLS por tenant, Fase 4).
- J-01 y J-02 no tienen equivalente en §13: son de despliegue y de producto, no de código del bot.

## 7. Qué NO hace este PR

- No escribe ninguna ADR, no reserva decisiones y no implementa nada (ni en este repo ni en los otros).
- No abre ramas de implementación: las de §3 y §4 son **propuestas** para cuando la persona las pida.
- No cambia contratos, código, configuración ni el dataset `eval/`.
- No re-prioriza: el orden de las olas es el que fijó la persona.

