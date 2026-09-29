# ADR 0020: Tope de coste por origen del turno (la otra mitad de H-04)

- **Estado:** Aceptada (2026-09-26)
- **Fecha:** 2026-09-26
- **Origen:** hallazgo **H-04** de la segunda revisión conjunta
  (`docs/reviews/2026-09-26-joint-integration-review-2.md`): *"el tope de tenant (240 turnos/h) es alcanzable
  por una sola IP en ~12 min y deja a todas las clientas fuera"*. La otra mitad de H-04 (que el 429 llegue
  al widget como 429 y no como 502) la cerró `saaspa-backend` en su PR #83.
- **Coordinación:** **el claim `clientIp` lo emite `saaspa-backend`** (es quien resuelve la IP con su proxy
  de confianza y quien firma el turn token). Esta ADR y su implementación **no dependen** de que exista para
  fusionarse: mientras no llegue, el turno se registra sin origen y el tope por origen no se aplica (variante
  tolerante, punto 5). **No se toca ningún otro repo** desde aquí.
- **Base:** **ADR 0010** (el tope de coste es de este servicio y `ia.turn_log` es la fuente de verdad) y
  **ADR 0020 se apoya en ADR 0006** (la identidad del turno viaja firmada en el token, nunca en el cuerpo).

## Contexto

El guard de coste tenía tres topes: por tenant (240 turnos/h y 1 M tokens/h), por conversación (30 turnos/h
y 150 k tokens/h) y la base de cómputo de ADR 0015. El de **tenant** es el que protege el dinero, y es
alcanzable desde **un solo origen**:

- El `Throttler` del backend permite **20 peticiones/minuto por IP**: 240 turnos en ~12 minutos.
- El tope por conversación (30/h) **no** defiende el presupuesto del tenant: el `conversationId` es rotable
  (lo señaló J-10 y lo confirma el contrato del backend, que acepta cualquier cadena de hasta 64 caracteres
  dentro de la sesión). Ocho conversaciones de 30 turnos agotan el tenant.
- La sesión anónima firmada del backend **tampoco** sirve como clave: se puede descartar la cookie y el
  backend emite una sesión nueva (`sessionIssued = true`), así que es tan rotable como el `conversationId`.

Consecuencia: un atacante podía **apagar el chat de todas las clientas del salón** sin gastar apenas dinero,
porque el 429 del tope de tenant es una denegación para todos. La única clave que **no se puede rotar** es el
origen de red (la IP que el backend resuelve con `TRUSTED_PROXY_HOPS = 1`, PR #76). Este servicio no la ve ni
puede deducirla: NestJS llama de servidor a servidor, así que un claim en el turn token es la única vía
coherente con R1 (la identidad no se toma del cuerpo ni de cabeceras que el servicio reciba).

## Decisión

1. **Una sola dimensión de origen, con dos fuentes y ninguna rotable:**

   | Turno | Clave de origen | Por qué |
   |---|---|---|
   | Logueado (`userId` presente) | `user:{userId}` | El sujeto firmado por el backend; existe **hoy**, sin coordinación. |
   | Anónimo (widget/WhatsApp) | `ip:{clientIp}` | La IP que el backend resuelve con su proxy de confianza (claim nuevo). |

   El usuario manda cuando lo hay, de modo que no hay doble cómputo y cinco clientas logueadas tras la misma
   IP de oficina (NAT) no comparten cubeta; un anónimo detrás de esa IP sí se cuenta por IP, que es lo
   correcto. Cada fuente lleva prefijo para que un id de usuario no pueda colisionar con una IP. El tope
   por usuario **ya funciona hoy**; el anónimo se activa solo cuando el claim exista.

2. **Cuarto tope: 60 turnos/h por origen** (configurable con `IA_COST_GUARD_ORIGIN_MAX_TURNS`), sin medida de
   tokens (sin línea base, no significaría nada). Con él, una sola IP consume como máximo **un cuarto** del
   presupuesto del tenant; los topes de tenant (240/h, 1 M) y de conversación (30/h, 150 k) **no cambian** y
   quedan como backstop y señal de capacidad. La relación con los otros tres es de **diagnóstico**: una
   conversación sola que habla de más se etiqueta `conversation`, la rotación de conversaciones desde un
   mismo origen se etiqueta `origin` (el caso de H-04: cada cubeta de conversación queda por debajo de su
   tope), y un consumo alto repartido entre varios orígenes se etiqueta `tenant`. Por eso el tope por origen
   se evalúa **el último** de los cuatro (la consulta agregada es la misma: una `FILTER` más).

3. **El origen se guarda hasheado, nunca la IP** (columna `origin_hash`, migración `V4`): `HMAC-SHA256` con
   sal de entorno (`IA_COST_GUARD_ORIGIN_SALT`), determinista dentro de la ventana del tope y no reversible
   sin el secreto. **R8:** el origen no se registra en logs. Es **pseudonimización, no anonimización** (un
   hash de IP sigue siendo dato personal): la sal es obligatoria en producción y, si falta, el tope sigue
   funcionando pero se avisa al arrancar que el hash se debilita.

4. **Fuente de verdad y sin contador en memoria:** el conteo sale de la misma consulta agregada sobre
   `ia.turn_log` que los otros topes (ADR 0010), con índice `(tenant_id, origin_hash, created_at)`. Un
   contador en memoria o en Redis se descarta: un reinicio lo borra y varias instancias no lo comparten
   (Redis, además, es obra de infraestructura que hoy no existe).

5. **Variante tolerante (decidida):** el claim `clientIp` es **opcional**. Mientras el backend no lo emita
   (desde su PR #84 ya lo emite, así que esto describe la **red de seguridad**, no la situación actual),
   el turno se registra con `origin_hash` nulo, **no entra en ninguna cubeta de origen** y el guard avisa
   **una vez por instancia** (WARN). La ausencia es además **visible y contable** en la tabla
   (`origin_hash IS NULL`), así que no es un silencio: se decide no bloquear la fusión de esta mitad en otro
   repo, como sí exige J-04. Orden de despliegue previsto: el backend emite el claim (inocuo para el
   verificador actual, que ignora claims desconocidos) y el tope del canal anónimo se activa solo.

6. **Un turno rechazado por el tope sigue sin registrarse** (ADR 0010) y el 429 conserva su forma
   (`ProblemDetail` con `scope`, `measure`, `measured`, `limit` y `window`), con el valor nuevo
   `scope = origin`.

## Consecuencias

- **Positivas:** una sola IP deja de poder agotar el presupuesto del tenant (60 en vez de 240 turnos/h) y el
  canal logueado gana su primer tope por sujeto; el `scope` del 429 distingue abuso de capacidad; el tope de
  tenant recupera su papel de señal (el backend ya lo registra con un `warn` para revisar el número); el
  mecanismo es el mismo para cualquier dimensión futura, sin infraestructura nueva.
- **Negativas / riesgos:** el tope por origen es **coarse** (una IP compartida —wifi, CGNAT, el propio salón—
  suma turnos de varias personas: 60/h puede cortar a una oficina muy activa), así que **se calibra en la
  Fase 5** con `ia.turn_log` y el `measured` del 429; un atacante con un pool de IPs sigue pudiendo repartir
  su consumo (a un coste mucho mayor por su parte, y el tope de tenant lo sigue acotando); el claim es un
  dato personal más en el token (no en los logs ni en claro en la tabla).
- **Hecho en otro repo (2026-09-26):** el claim `clientIp` ya lo emite `saaspa-backend` (su PR #84, commit
  `a08985e`), así que el tope del canal anónimo está **activo y verificado** con un turno real por HTTP: 200 /
  200 / **429 con `scope = origin`** y la fila de `ia.turn_log` con `origin_hash` ya no nulo (la IP no está en
  la tabla). Evidencia: `docs/contracts/h04-origin-claim-validation.md`. La variante tolerante del punto 5 se
  mantiene como red de seguridad: si un despliegue dejara de emitir el claim, el tope por origen del canal
  anónimo se apagaría solo, pero el `WARN` del guard y `origin_hash IS NULL` lo delatarían.

## Addendum (2026-09-28, HN-01): la sal pasa a ser obligatoria de verdad

El punto 3 de arriba decía «no reversible sin el secreto» y «la sal es obligatoria en producción», pero nada
lo exigía: era una afirmación sin dientes. El fallback cuando faltaba la variable era la **constante pública
`saaspa-ia-origin-sin-sal`**, visible en el propio repo, con lo que el hash de una IP era reversible por
fuerza bruta desde la tabla en segundos; el aviso al arrancar solo informaba, no cortaba; y ningún despliegue
la inyectaba (verificado: 0 aciertos en el `.env`/`.env.example` de `kamerinos-infra`). Hallazgo **HN-01** de
la tercera revisión conjunta (`docs/reviews/2026-09-28-joint-integration-review-3.md`).

Política nueva (implementada en `fix/hn01-origin-salt-fail-closed`):

1. **Fallo cerrado al arrancar** fuera del perfil `local` si la sal falta, está en blanco, mide menos de 16
   caracteres o es el valor legado `saaspa-ia-origin-sin-sal`, que se veta explícitamente. La constante ya no
   existe como clave en el código: solo queda como **valor rechazado**, privado.
2. **En `local` sin sal**: sal aleatoria por proceso (`SecureRandom`, 32 bytes en hex). El desarrollo no
   necesita secretos y nadie más queda expuesto a una constante pública.
3. La «variante tolerante» del punto 5 **no cambia**: siempre fue sobre el claim `clientIp` ausente (turno con
   `origin_hash` nulo, sin cubeta de origen y `WARN` del guard), nunca sobre la sal. La confusión entre las
   dos tolerancias era parte del hueco; el `WARN` del guard sigue siendo del claim.
4. **Rotar la sal** cambia el hash de los mismos orígenes: las filas previas de la ventana dejan de sumar en la
   cubeta de origen, es decir, el conteo de la ventana se reinicia una vez. Aceptable: la rotación es
   excepcional y puntual, el tope de tenant (que no depende de la sal) sigue protegiendo y el efecto queda
   acotado a la ventana de 1 h.

## Referencias

- H-04 en `docs/reviews/2026-09-26-joint-integration-review-2.md`; PR #83 de `saaspa-backend`
  (`fix(chat): map the assistant 429 to a real 429`).
- ADR 0010 (tope de coste y fuente de verdad), ADR 0015 (base del cómputo), ADR 0006 (el turn token lleva la
  identidad), J-10 (el `conversationId` es rotable).
- Código: `usage/TurnCostGuard.java` (cuarta `FILTER` y aviso), `usage/OriginHasher.java`, `security/TurnToken.java`
  (`origin()`), `api/ChatController.java`, `src/main/resources/db/migration/V4__turn_log_origin_hash.sql`.
- Tests: `OriginHasherTest`, `TurnTokenTest`, `TurnCostGuardTest` (el ataque de H-04 contra PostgreSQL: rota
  conversaciones y aun así lo corta el origen; aislamiento entre orígenes; tolerancia sin claim),
  `ChatControllerTest` (hash en el guard y en la fila, sin la IP) y `ChatApiTurnIntegrationTest` (fila real).
