# §107 — The mock layer is deleted, and the deploy stops eating evidence

**Date:** 2026-08-06 · **Lane:** frontend + deploy · **Migrations:** none · **Contract:** unchanged

Two asks in one sentence: *"drop everything in connection to the mocks we had for the UI to start
working (this was early phase work) and DB should not be wiped on deploy."*

They are unrelated in the code and are kept apart below.

---

## Part 1 — `src/mocks/` is gone

MSW was scaffolding: it let the SPA be built while the Spring Boot API did not yet answer. The API
answers now, so the layer's whole purpose has expired, and what it still cost was **a second
implementation of the server's rules that could agree with the client instead of the server**.

That is not a hypothetical. It is the single most productive defect generator in this project's
history, and it produced, among others:

| § | What the mock said | What the server said |
|---|---|---|
| §52 | `useSuiteHistory` read `{ runs: … }` | the contract says `entries` |
| §81 | a reviewer inbox, assigned to the case's **owner** | no review cards at all |
| §98 | `Suite.caseCount` = the resolved union | `?suiteId=` selected the *filed* arm alone |
| §100 | evaluated exit-criteria gates | the server evaluates none |
| §73 | `met: false` handed straight back | both fields null |

Every one of them typechecked, linted and tested green, because **the mock agreed with itself**.
Deleting the layer removes the class, not just the instances.

### What changed

| Path | Change |
|---|---|
| `src/mocks/**` (31 files, ~9,300 lines) | **deleted** |
| `public/mockServiceWorker.js` | **deleted** |
| `src/app/apiMode.ts` | **deleted** — `VITE_API_MODE` / `IS_MOCK_API` have no second value to select |
| `src/lib/devFaults.ts` | **new** — the fault switch, rehomed (below) |
| `src/api/client.ts` | the fault seam sits in front of `fetch`, in `request` and `artifactResponse` |
| `src/main.tsx` | no `startMockApi()`; `installFaultConsole()` behind a literal `DEV` |
| `src/features/auth/LoginPage.tsx` | no seeded fixture email in `defaultValues` |
| `vite.config.ts` | the `tcms-exclude-msw` plugin is gone; the `/api` proxy is now the only path to the API |
| `package.json` | `msw` removed (it survives only as `vitest`'s optional peer) |
| `.env.example` | `VITE_API_MODE` removed; states that a backend is now required |
| `.github/workflows/deploy.yml` | the "assert MSW is not in the bundle" step became "assert the dev fault seam is not in the bundle" |

Two test files went with it — `mocks/db/suites.test.ts` and `mocks/db/workItems.test.ts`. Both
asserted the **mock's** rules against the fixture store, so they test a thing that no longer exists.
**954/954 across 70 files** (976/72 at §106), `npm run build` green, `npm run lint` 0.

### The fault switch survived, and got better

`tcmsFaults` was an MSW handler, which meant the resilience claims could only be driven **against
the mock** — the one place the failures they simulate cannot occur. It is now `lib/devFaults.ts`,
injected in front of `fetch`, so the same five switches fault a session talking to the real backend
and everything downstream of the injected response is the production path.

`hang` is the one that needed thought. It returns a promise that never resolves and rejects only
when the caller's signal aborts, **with `signal.reason`** — for `AbortSignal.timeout` that is a
`TimeoutError` DOMException, exactly what a real dead socket produces, so `request`'s existing catch
converts it with no special case and a genuine cancellation during a hang still reads as an abort.

**The `import.meta.env.DEV` guards are literals and that is load-bearing.** Vite replaces the
literal with `false` in place, so Rollup proves the branch dead and drops the module; behind an
imported constant it cannot, and doing exactly that once shipped the whole mock layer as a 485 kB
production chunk with a green build. Verified by grepping `dist/assets/` for `tcmsFaults` — absent —
and `deploy.yml` now runs that same grep as a gate.

---

## Part 2 — the deploy was destroying evidence and reports

**The database was never being wiped.** Worth stating plainly, because that is what the symptom
looked like and it sent the investigation to the wrong place first. Production Postgres is
host-native (§2); `compose.prod.yaml` has no `postgres` service; the deploy runs `pull` + `up -d`
and never `down`, let alone `down -v`; Flyway has `baseline-on-migrate: false`, `ddl-auto: validate`,
no `clean`, and none of the 35 migrations contains a `DROP TABLE` / `TRUNCATE` / `DELETE FROM`.

What *was* being destroyed, on every single release:

`application.yml` defaults `tcms.storage.evidence.root` to `/var/lib/tcms/evidence` and
`.reports.root` to `/var/lib/tcms/reports`. `compose.prod.yaml` **declared no volumes and set
neither variable**, and `backend/Dockerfile` declares no `VOLUME`. So both directories lived in the
container's **writable layer** — and `docker compose pull && up -d` does not restart a container, it
*replaces* it. Every screenshot behind every failed result, and every generated report, went with
the old one.

**Why it read as a database wipe, and why nothing caught it.** The Postgres rows survive. `evidence`,
`attachment` and `report_artifact` still point at bytes that are gone, so the deploy succeeds, the
healthcheck passes, and the only observer is whoever later opens an old failure and gets a 404. No
migration repairs it. `application.yml` has called the evidence root *"STATEFUL AND BACKUP-CRITICAL…
it needs the same treatment in the deployment runbook that the database gets"* since §51, and
handoff §67 listed the missing backup story as an open item — the warning was written down twice and
the mount was never added.

### What changed

| Path | Change |
|---|---|
| `deploy/compose.prod.yaml` | bind mounts for `/var/lib/tcms/{evidence,reports}`; `TCMS_EVIDENCE_ROOT` / `TCMS_REPORTS_ROOT` set explicitly so the app's path and the mount target are one string |
| `deploy/compose.prod.yaml` | **`TCMS_JWT_SECRET` added** — see below |
| runbook §7 | `mkdir` + `chown -R 10001:10001 /var/lib/tcms` before first `up`; `TCMS_JWT_SECRET` in the env template |
| runbook §12 | a `tar` cron for evidence, and why reports are deliberately *not* backed up |
| runbook §14 | both gaps recorded and closed |

Bind mounts rather than named volumes, for the same reason §2 keeps Postgres host-native: state you
can `ls` and back up with the job that already runs `pg_dump`, rather than a volume someone has to
remember exists. It also puts them out of reach of `docker compose down -v`, which is in the
local-development command list and one bad ssh from the box.

Evidence is backed up and reports are not, deliberately: every report is regenerable from
`execution_fact`, and `report_artifact.filter_snapshot` — what makes one interpretable months later,
§6e's determinism requirement — is in Postgres. Separate roots is what makes that policy
expressible.

### `TCMS_JWT_SECRET` was missing from the deployment entirely

Found while tracing the same file. `application.yml` requires it (`${TCMS_JWT_SECRET:?}`), and it
appeared in **no** production artifact: not in `compose.prod.yaml`'s `environment:` block, not in the
runbook's `/etc/tcms/tcms.env` template. Note that `--env-file` supplies *interpolation* variables to
compose and does **not** inject them into the container, so adding it to `tcms.env` alone would not
have reached the app either.

Now declared `${TCMS_JWT_SECRET:?}` and documented in §7. **This is a required-value change: the next
deploy's `docker compose config` check fails loudly until the line exists in `/etc/tcms/tcms.env`.**
That is the intended behaviour — a refusal beats minting sessions signed with something guessable.

It is also a candidate explanation for the reported symptom that is *not* data loss: rotating this
secret invalidates every stateless token at once, so the whole instance lands on the sign-in screen
after a release. Nothing is deleted; it reads exactly like everything was.

---

## Verification

- `npm run build` ✅ · `npm run lint` ✅ 0 · `npm run test:run` ✅ 954/954 across 70 files
- `dist/assets/` greps clean for `tcmsFaults` and `mockServiceWorker` ✅
- `docker compose -f deploy/compose.prod.yaml config` ✅ — mounts resolve, and it correctly
  **refuses** with `TCMS_JWT_SECRET` unset ✅
- Backend untouched; `./gradlew build` not re-run (no backend file changed)

**Not verified, and it needs the box.** I could not reach the host — the SSH command was blocked by
the sandbox — so three things are inference from the repo rather than observation:

1. whether prod Postgres is in fact host-native as §2 specifies (nothing in the deploy path could
   wipe it *if* it is);
2. how the running container is currently getting `TCMS_JWT_SECRET`, given no committed file
   supplies it — if it is a hand-edit to `/srv/tcms/compose.yaml`, the deploy has been overwriting
   it on every release since the CD job landed;
3. whether `/var/lib/tcms/{evidence,reports}` already exist with the right ownership.

**Before the next deploy:** add `TCMS_JWT_SECRET` to `/etc/tcms/tcms.env` and run the §7 `mkdir`
/`chown`, or the release will fail at the compose-config step.

**Anything uploaded before this slice is already gone.** There was no copy.

## Left for later

- **Off-box backups** — still runbook §12's biggest gap, and now it covers two directories instead
  of one.
- **Per-project storage quota** (#28's other half) — the bind mount makes the disk bound explicit
  without bounding it.
- **The verification queues are still unrun.** This slice adds no browser rows: it deletes a
  transport rather than changing a screen. But the deletion means **every queued browser row now
  requires a running backend and a provisioned company** — the mock-mode column in
  `docs/verification/frontend-browser-checks.md` no longer has a lane to run in, which makes the
  live-only rows the only rows.
