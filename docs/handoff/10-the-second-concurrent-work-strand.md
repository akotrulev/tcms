# §10. A SECOND, CONCURRENT WORK STRAND — read before editing the plan

**Another session was actively editing `docs/plans/…` while this handoff was being written.** The plan changed under me twice in one sitting. Nothing here is broken, but **assume the plan is ahead of this handoff** and re-read its §8 resolved tables before acting on anything in §3 above.

That strand is driven by **`docs/audits/2026-07-30-kiwi-tcms-capability-gaps.md`** — a one-directional competitive analysis of Kiwi TCMS capabilities the plan didn't cover (committed in `2e22120` for safekeeping). It produced a **"Resolved — second pass"** decision table in the plan's §8, and these are the ones that change scope:

| Decision | Impact |
|---|---|
| **Grants travel in the JWT** — `sys`/`cmp`/`prj` assignments + a `pv` revocation epoch, so no request resolves permissions from the DB | Viable *only because* the six built-in roles are immutable, making `role → permission[]` a compile-time constant. **The `pv` epoch check is mandatory** — without it a revoked grant survives until token expiry. Lands M0.5. |
| **`§6v` Plan Templates & Cloning — now written** (it was a dangling reference earlier; that is resolved) | Clone case → M1; plan templates + clone plan/run → M2. A template stores the **selection query**, not a frozen case list. `automationKey` is never cloned; review state resets to Draft. |
| **LDAP / Active Directory in scope for M4** | Separate code path (bind, not redirect). LDAPS mandatory. Identity keyed on `objectGUID`/`entryUUID`, **never the DN** — the LDAP equivalent of "never key on email". Kerberos/SPNEGO stays out of scope. |
| **Four `IssueTracker` adapters in M4** — Jira · GitHub · GitLab · Azure DevOps | Building four at once is what stops the port leaking Jira's vocabulary. Plus tracker-side back-links on filing, and **cached** status hover-cards — never a synchronous tracker call during render. **M4 grew to 8–10 weeks.** |
| **Defects tracked on the kanban board**, local `Defect` record always exists | This is the no-tracker fallback, and it means TCMS keeps its own bug-progress view regardless of tracker — including across projects on different trackers. Not a workflow engine. |
| **Mermaid diagrams in the doc space** | Source stored in the block so diagrams version and diff; pre-rendered to inline SVG for HTML/PDF, passed through as a fence for Markdown. M2.5. |
| **TAP and Robot Framework parsers: not now** | M3 parser set stays JUnit XML · TestNG · Allure · Cucumber JSON. |

### The one open question that has a deadline

**Outstanding #43 — do we want a third-party extension mechanism at all?** The only gap-analysis item not decided. It **must be answered before M3 freezes the ingestion contract**, because a result-parser SPI is nearly free if designed in and expensive to retrofit. The plan recommends (a): make the ingestion pipeline's existing parser interface public — a documentation and stability commitment more than new code. The alternative is declaring it a non-goal so the integration list stays bounded by our own throughput.

### What this strand has NOT done yet

- **The ERD and `V1__initial_schema.sql` do not reflect `TestPlanTemplate` or the reshaped `Defect`** (local record + `externalRef` + board work item). Both need a pass if those amendments stand.
- The JWT-grant decision has schema implications too — the `pv` revocation epoch needs a home.
- `openapi.yaml` has no plan-template, clone, or defect endpoints.

### Uncommitted at the time of writing

`docs/plans/2026-07-30-tcms-high-level-plan.md` — left uncommitted **deliberately**, because the other session was mid-edit and committing would have interleaved two authors in one diff. Commit it from a fresh session once that strand is quiet.
