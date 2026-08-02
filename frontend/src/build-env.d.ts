/**
 * Build-time constants injected by Vite's `define` (see `vite.config.ts`).
 *
 * `__BUILD_ID__` identifies the build a tab is running. It is what makes deploy version skew
 * detectable at all: a tab opened before a deploy still reports the build it loaded with, so
 * a stale-chunk failure can be told apart from a genuinely broken build.
 */
declare const __BUILD_ID__: string;

/**
 * `__APP_VERSION__` is the release name the footer shows — the `version` input `deploy.yml` was
 * dispatched with, `dev` for a local build. See `vite.config.ts` for why it is not read from
 * `package.json`, and why it is separate from `__BUILD_ID__`.
 */
declare const __APP_VERSION__: string;
