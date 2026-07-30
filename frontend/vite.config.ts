import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

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

// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],
  define: {
    __BUILD_ID__: JSON.stringify(buildId),
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
