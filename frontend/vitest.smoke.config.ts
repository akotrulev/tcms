import baseConfig from './vitest.config.ts'

/**
 * What CI runs on every push: everything outside `src/features/`, plus the login page.
 *
 * The full suite is ~6 minutes and runs nightly in `tests.yml`. Nearly all of that is a dozen
 * `userEvent`-heavy page tests — `CaseEditorPage.test.tsx` alone is 372s of 943s — so dropping
 * `src/features/**` keeps 838 of 1854 tests and costs 20s.
 *
 * Spread rather than `mergeConfig`: that concatenates arrays, so merging an `include` would union
 * it with the base's and run the whole suite under the name "smoke".
 */
export default {
  ...baseConfig,
  test: {
    ...baseConfig.test,
    include: [
      'src/{a11y,api,app,auth,components,lib,test}/**/*.test.{ts,tsx}',
      'src/features/auth/LoginPage.test.tsx',
    ],
  },
}
