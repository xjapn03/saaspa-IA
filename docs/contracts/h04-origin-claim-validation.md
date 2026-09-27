# H-04: verificación del claim `clientIp` y activación del tope por origen (ADR 0020)

- **Fecha:** 2026-09-26 (madrugada del 27 UTC).
- **Qué cierra:** la mitad de **H-04** de la segunda revisión conjunta que quedó pendiente en
  `docs/reviews/2026-09-26-joint-integration-review-2.md`: el tope de coste **por origen del turno** ya está
  activo para el canal anónimo, porque `saaspa-backend` ya emite el claim que ADR 0020 necesitaba.
- **Refs verificadas:**
  - `saaspa-backend` → `origin/develop@a08985e` (PR #84, `feat(chat): stamp the turn token with the trusted
    client IP`, fusionado el 2026-09-27 06:04 UTC).
  - `saaspa-IA` → `origin/develop@aed4203` (PR #33, ADR 0020 y el tope por origen en modo tolerante).
- **Método:** lectura del código del backend (qué claim emite y de dónde sale el valor) + **turno real por
  HTTP** contra PostgreSQL (arranque del servicio con el Postgres del `docker-compose.yml` del repo) y
  volcado de las filas de `ia.turn_log`; más los dos casos nuevos de la suite de integración.

## 1. Qué emite el backend (verificado en su código, no en su changelog)

| Punto | Evidencia |
|---|---|
| Claim **`clientIp`**, opcional, en el turn token | `src/modules/internal/interfaces/turn-token-payload.ts`: `clientIp?: string` ("a token without it stays valid") |
| Se firma solo si hay valor | `src/modules/internal/turn-token.service.ts`: `if (input.clientIp) payload.clientIp = input.clientIp` |
| Valor = la IP del proxy de confianza | `src/modules/chat/chat.service.ts`: `clientIp: resolveClientIp(request)` |
| Y es **la misma** IP con la que su `Throttler` agrupa | `src/common/http/client-ip.ts`: es `req.ip` (Express con `TRUSTED_PROXY_HOPS = 1`), "the same value the Throttler buckets on", sin normalizar IPv6/IPv4-mapeadas |

El nombre del claim, su forma y su semántica coinciden **exactamente** con lo que ADR 0020 pidió en
`AGENTS.md` §11.5: no hizo falta ningún ajuste de contrato.

## 2. Evidencia: turno real por HTTP contra PostgreSQL

Servicio arrancado contra el Postgres del `docker-compose.yml` del repo (`jdbc:postgresql://localhost:5433/saaspa_ia`,
Flyway migró el esquema `ia` de **v2 a v4** al arrancar) con un turn token ES256 firmado con el mismo camino
que el backend (utilidad de test del repo) y el claim `clientIp = 203.0.113.7`; el modelo se sustituyó por un
**stub local** OpenAI-compatible (R14: ningún LLM real) y el tope del test se bajó a **2 turnos/hora por
origen** para poder verlo saltar.

Tres turnos del mismo origen, por HTTP:

```text
--- turno 1 (11e1a812-…) --- HTTP=200
{"turnId":"11e1a812-…","reply":{"text":"El masaje relajante cuesta $ 120.000.","links":[]},
 "handoff":{"requested":false,"reason":null},"usage":{"model":"deepseek-flash","tokensIn":100,"tokensOut":20},"sources":[]}
--- turno 2 (59ffda26-…) --- HTTP=200
--- turno 3 (8ea9e01e-…) --- HTTP=429
{"detail":"Se alcanzo el limite de uso de este asistente; intenta de nuevo mas tarde","instance":"/api/v1/chat",
 "status":429,"title":"Limite de uso alcanzado","scope":"origin","measure":"turns","measured":2,"limit":2,"window":"PT1H"}
```

Y las filas que quedaron en `ia.turn_log` (volcado real):

```text
 conversation_id | status | tokens_in | tokens_out | origen_nulo | origin_hash_corto | handoff_reason | error_code
-----------------+--------+-----------+------------+-------------+-------------------+----------------+------------
 conv-manual-h04 | OK     |       100 |         20 | f           | 3126a281d5d6d25e  |                |
 conv-manual-h04 | OK     |       100 |         20 | f           | 3126a281d5d6d25e  |                |
(2 rows)
```

Lo que demuestra, punto por punto:

- **La fila ya no tiene `origin_hash IS NULL`** (`origen_nulo = f`) en un turno anónimo real: el claim llega,
  el servicio lo hashea y lo persiste. Antes del claim, esa columna era nula (la única fila con
  `origin_hash IS NULL` que queda en la base de desarrollo es la del smoke test del 2026-09-26 21:35, anterior
  a V4).
- **El mismo origen produce el mismo hash** (`3126a281d5d6d25e` en los dos turnos), que es lo que hace
  contables los turnos por origen.
- **La IP no está en la tabla**: lo que se guarda es el HMAC; el volcado no contiene `203.0.113.7` en ningún
  campo.
- **El tope por origen corta de verdad**: el tercer turno responde **429 con `"scope":"origin"`**, `measured: 2`,
  `limit: 2` y la ventana `PT1H`.
- **El turno rechazado no deja fila** (2 filas, no 3): se mantiene la decisión de ADR 0010.

## 3. Evidencia en la suite (para que no pueda volver atrás)

`ChatApiTurnIntegrationTest` cubre ya los tres desenlaces por HTTP contra PostgreSQL de Testcontainers:

| Caso | Qué fija |
|---|---|
| `runsTurnOverHttp` (existente, ampliado) | Con el claim, la fila guarda el HMAC esperado del origen y **no** la IP |
| `turnWithoutTheClaimIsRecordedWithoutOrigin` (nuevo) | Sin el claim (el patrón anterior al PR #84) el turno se registra con `origin_hash` **nulo** y sigue contando: la variante tolerante |
| `originCapRejectsTurnsOverTheLimit` (nuevo) | Con el claim, el tope por origen corta el tercer turno con **429 y `scope: origin`**, el modelo **no** se llega a llamar y el rechazado no deja fila |

En CI el tope del test es `saaspa.cost-guard.origin-max-turns=2` (el de producción es 60).

## 4. Límites de esta verificación (lo que NO está probado)

- **No es un E2E desplegado:** no se ha ejecutado contra el backend real y el contenedor `ia-bot`, porque el
  despliegue sigue pendiente (J-01 y la mitad de H-02 en `kamerinos-infra`). Lo verificado es la cadena
  completa de **este** servicio (seguridad, guard, persistencia) con un token firmado con el mismo formato que
  el backend emite, no con la firma del backend en vivo.
- **La IP de producción depende del proxy:** el valor solo es fiable si Nginx sigue siendo el único salto
  (`TRUSTED_PROXY_HOPS = 1`). El backend tiene una prueba de que un `X-Forwarded-For` forjado no llega al
  claim; mantener esa configuración es una comprobación de despliegue.
- **El modelo fue un stub local** (R14): no se llamó a ningún LLM real, así que la latencia y los tokens del
  volcado son los del stub (100/20), no los del proveedor.
- **Los números no se recalibran aquí:** 60 turnos/hora por origen sigue siendo el valor de ADR 0020; su
  calibración con tráfico real es de la Fase 5.

## 5. Estado de los pendientes que esto cierra

- `AGENTS.md` §12: la entrada «H-04, activación del canal anónimo» pasa a hecha.
- `AGENTS.md` §11.5: el pedido del claim a `saaspa-backend` pasa a **hecho** (su PR #84).
- `AGENTS.md` §11.6: la fila de acople del claim ya no describe un fallo abierto, sino uno **cerrado** (con la
  nota de que un despliegue que deje de emitirlo vuelve al modo tolerante y lo delata `origin_hash IS NULL`).
- `docs/adr/0020-origin-scoped-cost-cap.md`: desaparece el bullet «Pendiente de otro repo».
- `chat-api.openapi.yaml` (v0.6.1): `clientIp` deja de documentarse como «opcional mientras el backend no lo
  emita».
- Se decidió **no** endurecer a fallo cerrado (rechazar turnos sin el claim) y **no** recalibrar el 60/h
  todavía: se quiere ver tráfico real antes. Tampoco se le pide aún al backend que amplíe su `warn` de
  `scope === 'tenant'` a cualquier `scope`: queda anotado como pedido de baja prioridad en §11.5.

## Referencias

- `docs/reviews/2026-09-26-joint-integration-review-2.md` (H-04); `docs/adr/0020-origin-scoped-cost-cap.md`.
- `docs/contracts/chat-api.openapi.yaml` (claim `clientIp`, 429 con `scope`); `docs/contracts/f1-e2e-validation.md`
  (patrón de informe seguido aquí).
- `saaspa-backend` PR #84 (`src/common/http/client-ip.ts`, `src/modules/internal/turn-token.service.ts`) y
  PR #83 (el 429 llega como 429: la otra mitad de H-04).

