import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'

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
 * `__APP_VERSION__` is the release the user is looking at — what the footer renders.
 *
 * It exists because the footer used to carry the string `0.1.0` as a literal in `AppShell.tsx`,
 * which meant the number on screen was not a fact about the running build at all: it survived
 * every release unchanged, and a deploy of 0.1.2 still read 0.1.0 with no cache anywhere at
 * fault. Three unmanaged copies of the version existed (that literal, `package.json`'s 0.0.0,
 * and Gradle's 0.1.0-SNAPSHOT) and none of them was the one `deploy.yml` was dispatched with.
 *
 * So this is deliberately fed from the workflow's `version` input (`VITE_APP_VERSION`) — the
 * same value that tags the image and names the artifact — and not from `package.json`. The
 * dispatch input is the only thing that knows what is being released.
 *
 * Distinct from `__BUILD_ID__` on purpose: that is the commit SHA, for telling one build from
 * another during skew detection; this is the human-facing release name. A SHA in the footer
 * would be unreadable, and a version tag cannot identify a build (two dispatches can share one).
 *
 * `dev` rather than a fake number for local builds: a developer's `npm run build` has no release
 * name, and inventing a plausible one is how the literal got there in the first place.
 */
const appVersion = process.env.VITE_APP_VERSION ?? 'dev'

/**
 * Where the dev server sends `/api/v1`.
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
  plugins: [react(), tailwindcss()],
  define: {
    __BUILD_ID__: JSON.stringify(buildId),
    __APP_VERSION__: JSON.stringify(appVersion),
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
      // The only path to the API in development. `npm run dev` therefore requires a running
      // backend — `docker compose up` at the repo root, plus a provisioned company to sign in as.
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
