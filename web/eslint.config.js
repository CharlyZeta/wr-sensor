import js from '@eslint/js'
import globals from 'globals'
import reactHooks from 'eslint-plugin-react-hooks'
import reactRefresh from 'eslint-plugin-react-refresh'
import tseslint from 'typescript-eslint'

/**
 * Lint del SPA (FEAT-0009). Además de lo idiomático de React/TS, incluye dos reglas que la revisión
 * de seguridad pidió como verificables: nada de `dangerouslySetInnerHTML`/`innerHTML` (los datos del
 * backend se renderizan como texto) y nada de `console.*` en el código de producción (un log podría
 * imprimir el token o una URL con `?token=`).
 */
export default tseslint.config(
  { ignores: ['dist', 'coverage', 'node_modules', 'playwright-report', 'test-results', 'e2e/stubs'] },
  {
    extends: [js.configs.recommended, ...tseslint.configs.recommended],
    files: ['**/*.{ts,tsx}'],
    languageOptions: {
      ecmaVersion: 2022,
      globals: globals.browser,
    },
    plugins: {
      'react-hooks': reactHooks,
      'react-refresh': reactRefresh,
    },
    rules: {
      ...reactHooks.configs.recommended.rules,
      'react-refresh/only-export-components': ['warn', { allowConstantExport: true }],
      'no-console': ['error', { allow: ['warn', 'error'] }],
      'no-restricted-syntax': [
        'error',
        {
          selector: "JSXAttribute[name.name='dangerouslySetInnerHTML']",
          message:
            'Prohibido: los datos del backend se renderizan como texto (A2 de la revisión de seguridad).',
        },
        {
          selector: "MemberExpression[property.name='innerHTML']",
          message: 'Prohibido innerHTML: usar textContent o nodos de React (A2/A3).',
        },
        {
          selector: "MemberExpression[property.name='insertAdjacentHTML']",
          message: 'Prohibido insertAdjacentHTML (A2/A3).',
        },
      ],
      '@typescript-eslint/no-explicit-any': 'error',
      '@typescript-eslint/consistent-type-imports': ['error', { prefer: 'type-imports' }],
    },
  },
  {
    // los tests pueden usar mocks y helpers de testing sin las restricciones de producción
    files: ['src/**/*.test.{ts,tsx}', 'src/test/**/*.{ts,tsx}'],
    rules: {
      'no-console': 'off',
      '@typescript-eslint/no-explicit-any': 'off',
    },
  },
)