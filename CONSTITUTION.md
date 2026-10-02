# Project Constitution & Architectural Guardrails

Este archivo define las **leyes inviolables y principios de diseño** del repositorio.
Todos los agentes (`coder-agent`, `tester-agent`, `verifier-agent`, `solve-agent`) deben respetar estas reglas de forma obligatoria durante el modo Loop.

---

## 🏛️ 1. Principios Arquitectónicos
- **Separación de Capas**: El código de dominio y lógica de negocio nunca debe importar adaptadores de infraestructura ni dependencias de frameworks externos.
- **Inmutabilidad**: Las entidades de dominio y Value Objects deben ser inmutables a menos que se justifique explícitamente en el contrato.
- **Manejo de Errores**: Nunca silenciar excepciones con bloques `catch` vacíos. Usar tipos de error de dominio tipados o estructuras `Result<T, E>`.

---

## 🛡️ 2. Estándares de Seguridad
- **Secretos y Configuración**: Prohibido hardcodear credenciales, tokens, llaves API o URLs de entornos en el código fuente.
- **Validación de Entradas**: Toda entrada externa (REST, GraphQL, CLI, WebSockets) debe validarse con esquemas estrictos antes de ingresar a la capa de dominio.
- **Sanitización**: Las consultas a bases de datos deben utilizar consultas parametrizadas u ORMs seguros contra inyecciones SQL/NoSQL.

---

## 🧪 3. Políticas de Calidad y Pruebas
- **Trazabilidad 1:1**: Cada regla de negocio (`BR-XXX`) y criterio de aceptación (`AC-XXX`) del contrato debe contar con un test asociado.
- **Determinismo**: Las pruebas unitarias no deben depender de la red, de orden de ejecución aleatorio ni de tiempos de espera (`sleep`) no controlados.
- **Verificación en Capas**: Toda feature debe satisfacer las 4 capas de verificación (`functional`, `static`, `security`, `arch`) antes de marcarse como `RESOLVED`.
