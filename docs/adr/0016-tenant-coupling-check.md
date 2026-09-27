# ADR 0016: Comprobación del acople de tenant y zona horaria con NestJS

- **Estado:** Aceptada (2026-09-26)
- **Fecha:** 2026-09-26
- **Origen:** hallazgo **J-06** del informe conjunto (`docs/reviews/2026-09-26-joint-integration-review.md`) y
  ola 3 de su triaje. Lo relacionado con **A-13** (health/info no reflejan nada) y **C-02** (autoridad de la
  zona horaria) se cruza aquí.
- **Alcance:** 100 % de este repo. **No requiere coordinación ni pedido a `saaspa-backend`** para funcionar:
  la comprobación usa un campo que el backend ya envía en cada turno.
- **Base:** **ADR 0003** (tenant por despliegue), **ADR 0005/0006** (identidad por turn token) y **C-02**
  (la zona horaria del tenant es autoridad de este servicio).

## Contexto

El acople entre los dos repos tiene dos valores y **solo uno de ellos falla en silencio**:

- **Tenant:** ya falla cerrado. Si el `tenantId` del turn token no es `saaspa.tenant.default`, este servicio
  responde **403** (A-03). Un despliegue mal alineado se detecta en el primer turno, con un error explícito.
- **Zona horaria:** no había ninguna comprobación. Con `TENANT_TIMEZONE` (backend) y `IA_TENANT_TIMEZONE`
  (este servicio) desalineados, este servicio construye el prompt con **su** zona (C-02: la autoridad es
  nuestra) y valida las fechas relativas en la suya, mientras el backend devuelve sus instantes con un offset
  calculado con la suya. **La respuesta sale con la fecha o la hora equivocada y no hay ningún error visible**:
  ni 403, ni 400, ni aviso. El único rastro era documental (nuestra §11.6 y un comentario en su `.env.example`).

Además, dos datos del despliegue que acotan el problema:

- En el compose de `kamerinos-infra` las dos variables **derivan del mismo `.env`**
  (`TENANT_TIMEZONE` → `TENANT_TIMEZONE` del backend y `IA_TENANT_TIMEZONE` del `ia-bot`), así que **no pueden
  desalinearse solas**: el riesgo entra por un override escrito a mano (como los `TZ: America/Bogota` y el
  `IA_TENANT_DISPLAY_NAME` que sí están fijos), por un renombrado o por un futuro multi-tenant.
- El backend **ya manda su zona en cada turno** (`timezone` del cuerpo, `chat.service.ts`:
  `configService.get('TENANT_TIMEZONE') || DEFAULT_TIMEZONE`), declarada como contexto informativo para el LLM
  (C-02). Ese campo era —hasta esta ADR— **obligatorio y muerto**: se exigía y nadie lo leía.

Y un segundo agujero del mismo hallazgo, solo nuestro: una **errata en nuestra propia** variable
(`America/Bogotá`, un espacio de más) **no fallaba al arrancar**; `TenantProperties.zoneId()` se evalúa al
construir el prompt, así que el servicio arrancaba bien y el **primer turno** reventaba con un 500.

## Decisión

1. **Comprobación en el primer turno, sin coordinar nada.** `TenantCouplingCheck` compara el `timezone` del
   cuerpo con `saaspa.tenant.timezone` y, si no coinciden, **advierte una vez por instancia** nombrando las
   dos variables (`TENANT_TIMEZONE` del backend y `IA_TENANT_TIMEZONE` de aquí) y los dos valores. La
   comparación es literal (el mismo valor que el compose deriva del mismo `.env`), tolera la ausencia del
   campo y **no corta el turno**.
   **La autoridad no cambia (C-02):** la zona del cuerpo es informativa y **nunca** se usa para el prompt, ni
   para validar fechas, ni para la disponibilidad; solo se compara. Se documenta explícitamente para que
   nadie la inyecte en el prompt "para aprovecharla": eso reintroduciría la deriva en la respuesta.

2. **Política: aviso, no fallo duro.** El campo es informativo y el turno es una petición real de una
   clienta; cortarla por un desacople de configuración castigaría a quien no tiene la culpa. Un fallo duro,
   además, convertiría una degradación (fecha equivocada) en una **caída** (chat sin servicio). El aviso deja
   el diagnóstico en el log, y los dos valores se pueden contrastar contra `/actuator/info`.

3. **Validación propia al arrancar.** `TenantProperties` valida en su constructor compacto que el tenant no
   esté vacío y que la zona sea un `ZoneId` válido: con una errata en **nuestra** variable el servicio **no
   arranca**, con un mensaje que nombra la variable de entorno, en vez de arrancar y dar 500 en el primer
   turno. (Esto es lo que sí se puede comprobar sin el otro lado.)
   Comprobado arrancando con `IA_TENANT_TIMEZONE=America/Bogotá`: el analizador de fallos de Spring muestra
   `Failed to bind properties under 'saaspa.tenant'` y, como **Reason**, `saaspa.tenant.timezone
   (IA_TENANT_TIMEZONE) no es una zona horaria valida: America/Bogotá`. Por eso la excepción **no encadena la
   causa**: el analizador imprime la causa raíz y con la `DateTimeException` encadenada el mensaje visible no
   nombraba la variable.

4. **Valores publicados en `/actuator/info`.** `TenantInfoContributor` publica `saaspa.tenant.id`,
   `saaspa.tenant.timezone`, el prompt del agente y el modelo. Son las dos mitades del acople consultables sin
   abrir la base ni los logs, el punto de comparación para operación y para el otro repo, y además
   `/actuator/info` deja de estar vacío (A-13). El endpoint vive en la red interna (`ia-bot` no publica
   puertos) y no expone secretos: tenant, zona, versión del prompt y nombre del modelo son configuración.

5. **Los tres campos del cuerpo siguen informativos y el contrato no cambia.** `timezone` gana el papel de
   **señal del acople**; `now` queda como reloj del backend para correlacionar; `locale` como idioma (hoy es
   una constante del backend, así que no hay nada que comparar). Ninguno se usa para decidir (R13 y C-02
   siguen intactos) y ninguno se retira: hacerlo sería un cambio de contrato coordinado y sin beneficio
   inmediato; su forma se fija en los casos de conformidad de J-13.

6. **Lo que se decide NO hacer:**

   | Alternativa | Por qué se descarta |
   |---|---|
   | Que el backend **consulte** `/actuator/info` al arrancar y compare | Convierte a `ia-bot` en dependencia de **arranque** del backend (hoy tolera que este servicio esté caído: 502 por turno), exige `healthcheck`/`depends_on` en `kamerinos-infra` y un PR coordinado. Aporta poco sobre el punto 1: el aviso llega en el primer turno, que es cuando el desacople hace daño. Queda **documentado como pedido de baja prioridad** (§11.5) y **sin enviar**. |
   | Una **prueba de conformidad** en el CI de los dos repos contra un valor compartido | No cubre el fallo: la desalineación vive en las **variables del despliegue**, que un test no ve. Y mantener un "valor de referencia compartido" entre dos repos es justo lo que J-13 señaló como frágil (números copiados a mano). |

## Consecuencias

- **Positivas:** el único acople que se degradaba en silencio deja de hacerlo, en el primer turno y sin
  coordinación; un error de configuración **propio** ya no arranca el servicio; los valores del acople son
  consultables; y un campo que estaba muerto pasa a tener un uso real y documentado.
- **Negativas / riesgos:** el aviso es un `WARN` (nadie lo ve si nadie lee logs): su valor está en que el
  desacople deja de ser invisible y en que `/actuator/info` permite comprobarlo a mano; un despliegue mal
  configurado **y sin tráfico** no deja rastro (aunque ahí no hace daño); la comparación es literal, así que
  una diferencia equivalente (`Etc/UTC` vs `UTC`) produciría un aviso falso, que se acepta a cambio de no
  normalizar zonas (la que importa es la que el compose deriva del mismo `.env`).
- **Nada que coordinar:** no hay cambio de contrato, ni de forma de datos, ni de comportamiento del agente.

## Referencias

- J-06 (`docs/reviews/2026-09-26-joint-integration-review.md`); triaje §3 y §4; A-13 y C-02 (`AGENTS.md` §13).
- Código: `tenant/TenantCouplingCheck.java`, `tenant/TenantInfoContributor.java`,
  `tenant/TenantCouplingConfig.java`, `config/TenantProperties.java` (validación) y `api/ChatController.java`.
- Tests: `TenantCouplingCheckTest` (aviso una sola vez, silencio cuando coinciden, tolerancia sin valor),
  `TenantPropertiesTest` (errata de zona y tenant vacío), `ChatControllerTest` (el turno sigue respondiendo
  200 con el aviso) y `ChatApiTurnIntegrationTest` (`/actuator/info` con el tenant y la zona).
