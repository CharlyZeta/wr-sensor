# Stack Preset: Java Spring Boot 3.x

Preset oficial de SDD-GL para aplicaciones Java modernas basadas en Spring Boot y Arquitectura Limpia/Hexagonal.

---

## 1. Tecnologías y Versiones
- **Language**: Java 21+ (LTS)
- **Framework**: Spring Boot 3.3+
- **Build Tool**: Maven (`pom.xml`) o Gradle (`build.gradle.kts`)
- **Database Access**: Spring Data JPA / Hibernate o jOOQ con PostgreSQL
- **Migration**: Flyway o Liquibase

---

## 2. Convenciones de Testing
- **Unit Testing**: JUnit 5 (Jupiter) + AssertJ + Mockito
- **Integration Testing**: `@SpringBootTest` con `@ActiveProfiles("test")` o Testcontainers
- **Test Naming Convention**:
  - `test[CriterionID]_[DescriptiveCamelCase]`
  - Ejemplo: `testBR001_montoDebeSerMayorACero()`
  - Ejemplo: `testAC001_transferenciaExitosaEntreDosCuentas()`

---

## 3. Comandos de Ejecución para Agentes
- **Ejecutar todos los tests**: `./mvnw test` o `./gradlew test`
- **Ejecutar un test específico**:
  - Maven: `./mvnw test -Dtest=*BR001*`
  - Gradle: `./gradlew test --tests "*BR001*"`
- **Verificar build e integración**: `./mvnw verify` o `./gradlew check`

---

## 4. Pautas Arquitectónicas para `coder-agent`
- **Capas Hexagonales**:
  - `domain`: Entidades puras, Value Objects, Excepciones de Dominio y Reglas de Negocio (sin anotaciones de Spring/JPA).
  - `application` / `ports`: Casos de uso (Use Cases) e interfaces de puertos de entrada/salida.
  - `infrastructure` / `adapters`: Controladores REST, Repositorios JPA y adaptadores externos.
- **Inmutabilidad y Records**: Preferir Java `record` para DTOs, Comandos y Eventos.
