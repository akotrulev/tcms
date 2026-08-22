<!-- orientation-cache v1 -->

# code-auditor — orientation cache (TCMS)

**Last verified:** 2026-08-22 · **Baseline commit:** `62875cc5`

**This file records the audit SPINE only** — lanes, layout, where artifacts live, how to orient.
It never records verdicts, findings, severities, coverage numbers or resolution status. Those live
in `docs/audits/` with IDs, rounds and dates, and "Resolved" is only ever claimed after re-reading
the cited code in that round.

## Read these first, in this order

1. `docs/agent-context/fe-react.md` / `be-java.md` — the stack agents' caches. Long, narrative,
   §-numbered newest-first. **Trust the body over the `Baseline commit:` header.** The structural
   sections are near the end: `## Version lane`, `## Build, run & test`, `## Layout`,
   `## Seams to reuse`, `## Conventions observed`, `## Known unknowns`, `## Session log`.
2. `CLAUDE.md` at the repo root — architecture, working agreement, the harness rules, and the §65
   deferred-verification policy. **It has drifted from reality before**; verify version claims
   against `node_modules`/`package.json` rather than quoting it.
3. `docs/HANDOFF.md` — an index; each section is `docs/handoff/NN-slug.md`, so `§246` resolves by
   filename prefix.

## Stacks and lanes

Three stacks, no root build (the root runs only `docker compose`):

| Lane | Root | Gate | Notes |
|---|---|---|---|
| backend | `backend/` | `./gradlew build` | Spring Boot 3.5.6 / Java 21 / Gradle. Every test boots real Postgres 17 via Testcontainers. No H2 fallback. |
| frontend | `frontend/` | `npm run build` (`tsc -b` + vite) | Vite SPA. **Never trust `tsc --noEmit`.** |
| packages | `packages/` | `npm run build` (NOT `--workspaces`) | `@tcms/client`, `@tcms/cli`, `@tcms/mcp`. |

**Tests run on `shanks`, never locally and never by a subagent.** An audit is static-first; treat
executing anything as opt-in only.

## Frontend layout (audit spine)

`frontend/src/{a11y,api,app,auth,components,features,lib,notifications,test}` — 348 TS/TSX files,
125 of them tests, 19 feature areas, 39 `*Page.tsx`. Routes in `app/router.tsx`, project-scoped
under `/projects/:projectId/*`; five are not (`/`, `/admin/teams`, `/admin/tokens`, `/my-work`,
`/teams/:teamId/board`, plus `/search` since §249).

## Reference standards a frontend finding is judged against

| Standard | Where |
|---|---|
| The REST contract, and the source of `src/api/schema.d.ts` | `docs/specs/openapi.yaml` |
| The permission catalogue (55 keys) and the six roles | `docs/specs/2026-07-30-tcms-roles-permissions-naming.md` §A3 |
| Convenience matrix copy (pinned to §A3 by a test) | `docs/specs/permission-matrix-summary.md` |
| Deferred browser passes — an unrun row is intentional, a **missing** row is not | `docs/verification/frontend-browser-checks.md` |
| Deferred backend mutation checks | `docs/verification/backend-mutation-checks.md` |

## Cheap computed checks worth re-running each round

- Contract path coverage: every `openapi.yaml` path appears as `"<path>": {` in `schema.d.ts`.
- `md5` `frontend/src/api/schema.d.ts` vs `packages/client/src/schema.ts` — must be identical.
- Permission catalogue: `openapi.yaml` `PermissionKey` enum vs `frontend/src/lib/permissions.ts`
  `permissionAreas` — count, membership and order.
- `useQuery`/`useMutation` outside `src/api/queries/`; raw `fetch` outside `api/client.ts`.
- Every `import.meta.env.DEV` is a literal at the point of use.
- Components with no test referencing them **by name** (sibling-filename checks give false results).
- Handoff `§N` coverage in both verification queues, cross-checked against whether that slice
  touched the stack at all.

## Audit lane decisions taken for this subject

- **`frontend/` is a FEATURE-lane subject.** `openapi.yaml` and the roles spec are used as
  reference standards, not consolidated into a requirement register — so there is no
  `*-requirements.md` for it and Mode B reloads the round file itself as the register.
- Artifact numbering in `docs/audits/`: the pre-existing files are **date**-prefixed; the audit
  series is `NNN-`-prefixed and independent. `001` = `frontend/`.

## Artifacts

| Subject | Series | Rounds so far |
|---|---|---|
| `frontend/` | `001-frontend-audit-r<N>.md` | r1 (2026-08-22) |
