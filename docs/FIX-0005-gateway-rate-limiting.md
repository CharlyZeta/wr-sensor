# FIX-0005 — API Gateway y rate limiting delante de los servicios expuestos

**Status:** DRAFT
**Mode:** GATE
**Servicio(s) afectado(s):** infraestructura (nuevo componente `gateway`), `sensor-registry`,
`query-api`
**Relacionado:** `docker-compose.yml`, FEAT-0006 (auth JWT)
**Depende de:** ninguno

## 1. Contexto

`sensor-registry` (8080), `query-api` (8082) y `alerting-service` (8083, WebSocket) se
exponen hoy con puertos directos en `docker-compose.yml`, sin capa intermedia. No hay rate
limiting, ni punto único de entrada, ni protección contra abuso de los endpoints de auth
(`POST /api/auth/login`) o de listado (`GET /api/sensores`).

## 2. Business Rules (BR)

- **BR-01**: Debe existir un único punto de entrada (`gateway`) que enrute a
  `sensor-registry`, `query-api` y `alerting-service` (incluyendo upgrade de conexión
  WebSocket).
- **BR-02**: `POST /api/auth/login` debe tener rate limit específico y más estricto que el
  resto de los endpoints (mitigar fuerza bruta de credenciales).
- **BR-03**: Los endpoints de lectura (`GET /api/sensores`, histórico en `query-api`) deben
  tener rate limit configurable por IP o por token, con valores default razonables para
  uso normal de dashboard.
- **BR-04**: Al superar el rate limit, la respuesta debe ser `429 Too Many Requests` con
  header `Retry-After`, nunca un timeout silencioso ni un `500`.
- **BR-05**: El gateway no debe convertirse en punto ciego de observabilidad: debe propagar
  o generar un `correlationId`/`traceId` por request si el servicio downstream no lo trae.
- **BR-06**: Los servicios internos (`ingestion-service`, `data-simulator`) no se exponen
  a través del gateway — permanecen solo accesibles dentro de la red de Docker Compose.

## 3. Acceptance Criteria (AC)

- **AC-01** (BR-01): Dado el stack levantado con `docker compose up`, cuando se hace una
  request a `gateway:PUERTO/api/sensores`, entonces la respuesta llega correctamente
  enrutada desde `sensor-registry`.
- **AC-02** (BR-01): Dado un cliente WebSocket, cuando se conecta a
  `gateway:PUERTO/ws/alertas`, entonces el upgrade de conexión se completa y recibe eventos
  de `alerting-service`.
- **AC-03** (BR-02, BR-04): Dado un rate limit de N intentos por minuto en `/api/auth/login`,
  cuando se supera, entonces el intento N+1 recibe `429` con `Retry-After`, no se reenvía
  a `sensor-registry`.
- **AC-04** (BR-03): Dado el rate limit default en `GET /api/sensores`, cuando un cliente
  hace requests dentro del límite, entonces todas se resuelven normalmente sin fricción
  perceptible.
- **AC-05** (BR-06): Dado que `ingestion-service` no tiene ruta configurada en el gateway,
  cuando se intenta acceder directamente desde fuera de la red Docker, entonces la conexión
  falla (puerto no publicado al host).

## 4. Fuera de alcance

- TLS/HTTPS termination (Contract futuro si se despliega fuera de entorno local).
- Autenticación a nivel gateway más allá de pasar el JWT existente (no se reemplaza el JWT
  de `sensor-registry`).

## 5. Ambiguity Log

- [ ] Tecnología del gateway: ¿Spring Cloud Gateway (reactivo, coherente con el resto del
  stack) vs. algo liviano como Traefik/Nginx a nivel Compose? — **pendiente de decisión
  humana, impacta si esto es un servicio Java más o infraestructura declarativa.**
- [ ] Valores concretos de rate limit (N por minuto) — **pendiente, requiere estimar tráfico
  esperado del dashboard.**

## 6. Completion Map

- [ ] BR-01 → AC-01, AC-02
- [ ] BR-02 → AC-03
- [ ] BR-03 → AC-04
- [ ] BR-04 → AC-03
- [ ] BR-05 → (sin AC hasta definir formato de traceId, ver FIX-0002/FIX-0009)
- [ ] BR-06 → AC-05
