/**
 * Which API the app talks to in development: the MSW mock, or the real backend.
 *
 * Until this file existed there was only one answer — MSW started unconditionally in dev, so
 * the SPA and the Spring Boot API had never exchanged a single request. The mock is still the
 * default, deliberately: `npm run dev` needs no Docker, no Postgres and no provisioned company,
 * and that loop is worth keeping. Live mode is the opt-in that proves the two halves agree.
 *
 *   VITE_API_MODE=mock   (default) — MSW intercepts /api/v1; no backend required
 *   VITE_API_MODE=live             — requests go to the Vite dev proxy → the real backend
 *
 * **Production never mocks**, whatever this says: `main.tsx` gates the worker on
 * `import.meta.env.DEV` first, so the whole mock branch — handlers, fixtures, faults — is
 * statically eliminated from a production build.
 */

export type ApiMode = 'mock' | 'live';

/**
 * Read once, at module load. A mode that could change at runtime would mean a session
 * authenticated against one API and reading from the other.
 */
export const API_MODE: ApiMode = import.meta.env.VITE_API_MODE === 'live' ? 'live' : 'mock';

/** True when the mock API should serve this session. Always false in a production build. */
export const IS_MOCK_API = import.meta.env.DEV && API_MODE === 'mock';
