# api-gateway + SPA (FEAT-0009 BR-010).
#
# Etapa 1: construye el SPA con Node y lo deja en /web/dist. Requiere red (registry npm); es la
# única pieza del proyecto que la necesita, y por eso **no** se integra al ciclo `mvn -o` del
# backend: la imagen la ejecuta en su propio stage.
FROM node:24-alpine AS spa

WORKDIR /web
# `npm ci` sobre el lockfile versionado (reproducible y con integridad verificada)
COPY web/package.json web/package-lock.json ./
RUN npm ci --ignore-scripts --no-audit --no-fund
COPY web/ ./
RUN npm run build

# Etapa 2: runtime del gateway (jar ya construido con `mvn -o install -DskipTests` en services/).
FROM eclipse-temurin:25-jre

WORKDIR /app
COPY target/*.jar app.jar

# El SPA se sirve como recursos estáticos del gateway. Se copia al filesystem (no dentro del jar)
# para no tener que reempaquetar el artefacto: `static-location` apunta a `file:/app/static/`.
COPY --from=spa /web/dist/ /app/static/

EXPOSE 8084
ENTRYPOINT ["java", "-jar", "app.jar"]