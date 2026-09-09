# WR-Sensor — Registro de Decisiones (ADR-español)

Decisiones tomadas en el ciclo SDD-GL (Gate humano). Fuente primaria: Ambiguity Log
de cada Contract y audits `.sdd/runs/`.

## ADR-0001 · Lectura de sensores para roles {ADMIN, VIEWER} (FEAT-0002)
**Contexto:** spec §7 rotulaba GET como "(admin)". **Decisión (HO-Gate 2026-08-13/14):**
la lectura (listado y detalle) se permite a `ADMIN` y `VIEWER`; la escritura
(POST/PUT/DELETE) sigue siendo ADMIN-only. Se agregó el sentinel `Rol.OTHER` para
distinguir 401 (sin auth) de 403 (rol desconocido).

## ADR-0002 · Timestamps naive-UTC (fix keyset FEAT-0002)
**Contexto:** el desempate del cursor keyset fallaba entre filas con igual
`fecha_instalacion` (perdía la fila empatada). **Causa raíz:** `java.sql.Timestamp`
convertido por Spring R2DBC a wall-clock local (UTC-3) en cada cruce
escritura/lectura/predicado. **Decisión:** bindear siempre
`LocalDateTime.ofInstant(ts, UTC)` en write/read/predicado (columna `TIMESTAMP`
naive-UTC). Verificado por `FEAT0002MainFlowIT`.

## ADR-0003 · Error de id malformado → `SENSOR_INVALID_ID` (FEAT-0003)
**Decisión (HO-Gate 2026-09-09):** nuevo code `SENSOR_INVALID_ID` (400), consistente
con la familia `SENSOR_INVALID_LIMIT/CURSOR`, en vez de `SENSOR_INVALID_REQUEST`.

## ADR-0004 · Visibilidad de sensores INACTIVO (FEAT-0003/0005)
**Decisión:** el detalle y el listado **no filtran por estado** (INACTIVO visible);
la baja lógica (FEAT-0005) no oculta en v1. Reactivación vía `PUT` (FEAT-0004).

## ADR-0005 · Alcance del PUT y exclusión de INACTIVO (FEAT-0004)
**Decisión (HO-Gate 2026-09-09):** PUT edita solo la configuración
(estado/histéresis/frecuencia/rangos); identidad inmutable; `INACTIVO` rechazado en
PUT (400 `SENSOR_INVALID_ESTADO`) porque la baja es FEAT-0005; sin evento en v1;
body = subset completo (Jackson estricto `fail-on-unknown-properties` →
`SENSOR_INVALID_REQUEST`).

## ADR-0006 · Guards por atributo de exchange (fix FEAT-0005, transversal)
**Contexto:** los guards que leían el rol del Reactor Context fallaban de forma
intermitente en `DELETE` (el handler se re-suscribe sin contexto; rol=null → 401
espurio). **Decisión:** `RolFilter` resuelve el token **una vez por request** y lo
guarda como **atributo del exchange** (`RolGuard.ATTR_ROL`); los guards de controller
reciben el exchange (`requireAdmin/requireReader(exchange, …)`). Se conservan
overloads por Context para unit tests. Aplicado a los 5 endpoints protegidos.

## ADR-0007 · JWT HS256 con JDK estándar (FEAT-0006)
**Contexto:** el repo local Maven offline no tiene `jjwt` ni dependencias completas
de nimbus. **Decisión:** implementar emisión/verificación HS256 con `javax.crypto` +
Base64url + parseo JSON manual (claims `sub/rol/iat/exp`); sin dependencias nuevas.
Desviación documentada de las Notas del Contract (indicativas). Expiración 1 h
configurable; `AUTH_JWT_SECRET`; seed dev de usuarios (BCrypt).

## ADR-0008 · Migración de ITs a JWT real (FEAT-0006)
**Decisión (HO-Gate):** los ITs de FEAT-0001..0003 dejaron el header literal
`Bearer ADMIN` y obtienen tokens reales vía `/api/auth/login` (helper `TestTokens`);
roles ajenos se mintean con la firma dev.

## ADR-0009 · data-simulator (FEAT-0010)
**Decisiones (HO-Gate 2026-09-09):** sensores por **config local** (6 seeds §9.5);
control **sin auth** en v1; **pom standalone** (multi-módulo se arma en infra);
frecuencia default 30 s configurable; payload `Lectura` formal hacia
`sensor.lecturas` (BR-002) sin `severidad`.

## ADR-0010 · ingestion-service (FEAT-0011)
**Decisiones (HO-Gate 2026-09-09):** config del sensor vía **REST a sensor-registry**
(login VIEWER + cache en memoria); **AlertaEvento simple por cambio de severidad**
(histéresis de degradación = FEAT-0012); última severidad en memoria;
**TimescaleDB real** (hypertable `lectura`, `create_hypertable` al arranque);
DLQ/DLX con `x-rechazo`.

## ADR-0011 · Histéresis por debounce temporal (FEAT-0012)
**Contexto:** FEAT-0011 emite solo eventos de cambio, no cada lectura; la "permanencia
en banda" de la spec §5 no puede medirse con valores continuos acá. **Decisión
(HO-Gate):** bajada = candidata que se confirma si no llega una re-subida dentro de la
ventana `alerting.histeresis-segundos` (configurable, default 60); subida = inmediata.
Entrega por **WebSocket `/ws/alertas`** (push JSON confirmado). Documentada como
aproximación temporal sobre el stream de cambios.

## ADR-0012 · Proceso (notas de gobernanza)
- Los Loops de FEAT-0002..0012 se ejecutaron por el orquestador con bookkeeping del
  protocolo (Completion Map/audit) ante delegaciones de subagentes estancadas
  (verificado con sonda que el entorno sí las soporta). Documentado en los audits.
- Ajustes menores de spec durante Loops (AC-009 FEAT-0004) quedaron anotados en los
  Contracts y audits.
