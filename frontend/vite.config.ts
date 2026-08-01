import { rm } from 'node:fs/promises'
import { resolve } from 'node:path'
import { defineConfig, type Plugin } from 'vite'
import react from '@vitejs/plugin-react'

/**
 * Keeps MSW's service worker out of the production build.
 *
 * `msw init` puts `mockServiceWorker.js` in `public/`, and Vite copies `public/` verbatim — so a
 * production `dist/` shipped it to the server. Nothing registers it: `main.tsx` gates the worker
 * on a literal `import.meta.env.DEV` that Vite replaces with `false`, so this was a dead 8 kB
 * file rather than an app serving fixtures.
 *
 * Removed anyway, because "dead unless someone registers it" is a property of today's code and
 * the file's whole purpose is to intercept API calls. The alternative — moving it out of
 * `public/` — means teaching MSW a non-default path in dev, i.e. breaking the working case to
 * tidy the broken one.
 *
 * `deploy.yml`'s `web` job asserts `dist/` is clean; this is what makes that assertion pass, and
 * it runs on a local `npm run build` too, so the two do not drift.
 */
function excludeMockServiceWorker(): Plugin {
  return {
    name: 'tcms-exclude-msw',
    apply: 'build',
    // `closeBundle` rather than `generateBundle`: public/ is copied outside the bundle graph,
    // so the file does not exist as an emitted asset to filter — only as a file on disk.
    async closeBundle() {
      await rm(resolve(__dirname, 'dist/mockServiceWorker.js'), { force: true })
    },
  }
}

/**
 * `__BUILD_ID__` identifies the build a tab is running.
 *
 * It is what makes deploy version skew detectable at all: a tab opened before a deploy still
 * reports the build it loaded with, so a stale-chunk failure can be told apart from a genuinely
 * broken build — and the once-only reload guard has something stable to key on.
 *
 * CI should pass the commit SHA (`VITE_BUILD_ID=$GITHUB_SHA`); the timestamp is a local fallback
 * so the value is never empty.
 */
const buildId = process.env.VITE_BUILD_ID ?? `local-${Date.now().toString(36)}`

/**
 * Where `VITE_API_MODE=live` sends `/api/v1`.
 *
 * A **proxy**, not a CORS grant: the browser keeps talking to its own origin, so no preflight,
 * no `Access-Control-Allow-*` configuration on the backend, and no cross-origin rules to relax
 * in production that a developer's machine then depends on. In the shipped product the SPA is
 * served from the same Spring Boot jar as the API, which is same-origin by construction — the
 * proxy exists so dev matches that arrangement instead of inventing a looser one.
 */
const apiProxyTarget = process.env.VITE_API_PROXY_TARGET ?? 'http://localhost:8080'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react(), excludeMockServiceWorker()],
  define: {
    __BUILD_ID__: JSON.stringify(buildId),
  },
  server: {
    port: Number(process.env.VITE_DEV_PORT ?? 5173),
    /**
     * Fail rather than shift when the port is taken.
     *
     * Vite's default is to walk to the next free port, which is friendly with one dev server
     * and actively dangerous with two: a second lane started on a busy 6173 would silently
     * land on 6174 while `TCMS_BASE_URL` still said 6173 — so every invitation link the CLI
     * printed would open lane A's tab, pointed at lane A's backend, and the mistake would look
     * like a backend defect. A refusal to start is the diagnosable version of that.
     */
    strictPort: true,
    proxy: {
      // Configured in both modes and harmless in mock mode: MSW intercepts at the service
      // worker, so a mocked request never reaches the dev server to be proxied.
      '/api': {
        target: apiProxyTarget,
        changeOrigin: true,
      },
    },
  },
  build: {
    /**
     * Raised from the 500 kB default to 600 kB, deliberately and with the reasoning recorded,
     * rather than left to fire on every build until everyone learns to ignore it.
     *
     * The chunk it fires on is `CaseListPage`, at ~528 kB raw / ~155 kB gzip, and the weight is
     * MUI X DataGrid. Two things make that acceptable where a 528 kB *entry* chunk would not be:
     *
     *   1. **It is not on the critical path.** The route is `lazy()`, so the grid is fetched
     *      only when someone opens the case list. First load is the `index` chunk at ~118 kB
     *      gzip, comfortably inside the 130–170 kB per-route budget.
     *   2. **Splitting it further made it worse.** Forcing the DataGrid into its own
     *      `manualChunks` entry defeated tree-shaking: the page chunk fell to 72 kB but the
     *      grid chunk came out at 728 kB, taking total JS from ~909 kB to ~1275 kB. And since
     *      `CaseGrid.tsx` is the only consumer, the separate chunk bought no cache reuse — it
     *      was downloaded in lockstep with the page anyway. Measured, then reverted.
     *
     * The real lever, if this route's weight ever becomes a problem, is dropping MUI X DataGrid
     * for a plain table — a Phase 5 conversation about whether its features earn their cost,
     * not a bundler setting.
     */
    chunkSizeWarningLimit: 600,
  },
})
