# §7. How to run

**The two stacks are independent.** Gradle lives in `backend/`, npm in `frontend/`; neither build file sits at the repo root. Nothing at the root needs to be run.

```bash
# Backend — REQUIRES Docker running (every test boots a real Postgres 17)
cd backend
./gradlew build      # THE gate: compile with -Werror + all 376 tests via Testcontainers
./gradlew test       # tests only
```

There is deliberately **no H2 fallback**: RLS, `ltree` and `citext` have no H2 equivalent, so an in-memory lane would test a schema the product never runs on. If Testcontainers reports `Could not find a valid Docker environment` while `docker ps` works, it is the Docker-29 API-version floor, already pinned in `build.gradle.kts` — see §11.

```bash
# Frontend — two API modes (§20). MOCK is the default and needs no backend.
cd frontend
npm install
npm run dev          # http://localhost:5173  (mock: sign in with any password of 4+ chars)
npm run build        # THE gate: tsc -b + vite build
npm run lint         # must stay at 0 warnings

# LIVE mode — the SPA against the real Spring Boot API, through the Vite proxy
cp .env.example .env.local && sed -i '' 's/VITE_API_MODE=mock/VITE_API_MODE=live/' .env.local
# …then, from the repo root: docker compose up -d, and provision a company to sign in as:
#   docker compose run --rm backend provision-company --slug=acme --name="Acme" \
#     --admin-email=ops@acme.test --admin-name="A. Admin"
# The CLI prints a single-use /accept-invitation?token=… link. Open it, set a password, sign in.
# After changing .env.local, RESTART vite and clear node_modules/.vite — an in-place restart
# leaves a stale optimize-deps hash and the page renders blank with no error (§20).

# Regenerate types after ANY openapi.yaml change
npm run gen:api

# Audit test-id collisions (should print only per-file-prefix or exclusive-branch dupes)
grep -rhoE 'data-testid=(\{`[^`]*`\}|"[^"]*")' src | sed -E 's/data-testid=//' | sort | uniq -d
```

**Driving the resilience behaviour** (dev only — from the browser console):

```js
tcmsFaults.status(503, 30)  // every request 503s, with Retry-After: 30
tcmsFaults.hang()           // requests never answer — proves the timeout budget
tcmsFaults.expire()         // the next request 401s once — proves in-place session recovery
tcmsFaults.drift()          // a 200 with the wrong shape — must read as FAILED, not empty
tcmsFaults.only('/cases')   // scope the fault to one endpoint family
tcmsFaults.off()
```

Faults live in module state, so a **full page reload clears them** — arm the fault *after* the page has loaded when testing a failed refetch. Client error reports are also kept on `window.__tcmsErrors` in dev, so a caught error can be read back instead of expanded by hand in the console.

**The mock API is stateful and in-memory** — it resets on reload. `mockServiceWorker.js` lives in `public/` and is committed; re-run `npx msw init public/ --save` only if it is ever deleted.

`npm run gen:api` is wired for this. `npm run lint` (oxlint) must stay at 0 warnings — it is part of the phase gate, not advisory.

**Why `gen:api` runs via `npx` rather than a local dependency:** the Vite scaffold pinned **TypeScript 6.0.3**, and `openapi-typescript@7` peer-requires `^5.x`. Installing it locally needs `--legacy-peer-deps` or a TypeScript downgrade — neither is worth it for a build-time codegen tool that only reads YAML and emits a `.d.ts` (it never typechecks this project). `npx --yes openapi-typescript@7` resolves its own TypeScript and sidesteps the conflict. Revisit when `openapi-typescript` supports TS 6.

**Later, for production packaging:** Gradle should invoke the frontend build and copy `frontend/dist` into `backend/src/main/resources/static`, while `npm run dev` stays fully independent for day-to-day UI work. Conflating those is what makes monorepos feel slow.
