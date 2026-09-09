# Stack Preset: TypeScript Node.js / Bun

Preset oficial de SDD-GL para aplicaciones TypeScript modernas en backend y fullstack.

---

## 1. Tecnologías y Versiones
- **Runtime**: Node.js 20+ LTS o Bun 1.1+
- **Language**: TypeScript 5.4+ (Strict Mode)
- **Validation**: Zod
- **Database / ORM**: Prisma o Drizzle ORM con PostgreSQL / SQLite
- **Package Manager**: pnpm / npm / bun

---

## 2. Convenciones de Testing
- **Framework**: Vitest (o Jest / Node.js native test runner `node:test`)
- **Test Naming Convention**:
  - `test[CriterionID]_[descriptiveCamelCase]` o `it('AC-001: [description]')`
  - Ejemplo: `testBR001_amountMustBeGreaterThanZero()`
  - Ejemplo: `testAC001_shouldCompletePaymentSuccessfully()`

---

## 3. Comandos de Ejecución para Agentes
- **Ejecutar todos los tests**: `npm test` o `pnpm test` o `bun test`
- **Ejecutar un test específico**:
  - Vitest / Jest: `npm test -- -t "BR-001"` o `npm test -- -t "BR001"`
  - Bun: `bun test -t "BR001"`
- **Verificar tipado y linting**: `npm run typecheck` o `npx tsc --noEmit`

---

## 4. Pautas de Diseño para `coder-agent`
- **Inferencia de Tipos**: Usar `z.infer<typeof Schema>` para tipos de entrada/salida.
- **Manejo de Errores**: Preferir Result pattern (tipo `Either`/`Result`) o clases de error personalizadas (`DomainError`).
