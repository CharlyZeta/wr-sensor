# Stack Preset: Python FastAPI

Preset oficial de SDD-GL para aplicaciones Python modernas basadas en FastAPI y Pydantic v2.

---

## 1. Tecnologías y Versiones
- **Language**: Python 3.12+
- **Framework**: FastAPI 0.110+
- **Validation**: Pydantic v2
- **ORM / Database**: SQLAlchemy 2.0 (Async) + asyncpg / aiosqlite + Alembic
- **Package & Environment**: Poetry / uv / pipenv

---

## 2. Convenciones de Testing
- **Framework**: Pytest + pytest-asyncio + httpx (`AsyncClient`)
- **Test Naming Convention**:
  - `test_[criterion_id]_[descriptive_snake_case]`
  - Ejemplo: `test_br001_monto_debe_ser_mayor_a_cero()`
  - Ejemplo: `test_ac001_transferencia_exitosa()`

---

## 3. Comandos de Ejecución para Agentes
- **Ejecutar todos los tests**: `pytest` o `poetry run pytest`
- **Ejecutar un test específico**:
  - `pytest -k "br001"` o `pytest -k "test_ac001"`
- **Verificar formateo y tipado**: `ruff check .` && `mypy .`

---

## 4. Pautas de Diseño para `coder-agent`
- **Modelos de Dominio**: Usar dataclasses o modelos Pydantic puros para entidades de dominio.
- **Inyección de Dependencias**: Usar `Depends()` de FastAPI para repositorios y servicios.
- **Tipado Estricto**: Anotaciones de tipos completas (`typing`) en todos los métodos y funciones.
