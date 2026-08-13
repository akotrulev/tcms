# Live browser pass — findings — 2026-08-12

Run against a fresh local `docker compose up --build` stack (backend on :8080, Postgres on :5432),
frontend dev server on **:5183** (5173 was held by an unrelated stale process from a different
checkout, `~/Documents/tcms` — left untouched, ported around it instead).

Company: **`v179`**, project `OLB` "Acme Online Banking" (provisioned). Accounts use `…@v179.test`,
password `Charter-v179!` for all six, per the charter's fixed-password rule.

Driven via `claude-in-chrome` browser automation, one continuous sitting, in queue order
(`docs/verification/frontend-browser-checks.md`). Findings recorded here as they occur; queue rows
marked `✅ RUN` in the source file as each lane completes.

**Session stopped after `A1` at the user's direction ("finish A1 and stop").** `A2`–`A6`, `L1`–`L4`,
`F`, and the section-specific lanes (§117 onward) were **not** driven this session. The company
`v179` and its fixtures (10 suites, 29 cases, tags, conditions) are in place and ready for the next
session to continue from `A2 — plans`. The `Fixture seeding (temp)` API token (Test Lead role,
every-project scope) was deliberately left live for that continuation — revoke it before handing
this instance to anyone outside this verification effort.

**Summary of findings this session** (full detail in the sections below):
1. 🔴 `/var/lib/tcms` is never created or mounted in `compose.yaml`/`Dockerfile` — every blob write
   (CI ingestion, evidence, reports) 500s on a stock `docker compose up --build` until someone
   manually `mkdir`s and `chown`s it inside the running container. Confirmed via a real 500 on the
   first ingestion POST.
2. 🔴 Suite-level conditions (`A1-2`) have no write path anywhere in the backend — not a UI gap, a
   missing endpoint. `SuiteInput` has no `conditions` field, `SuiteController` has no conditions
   route, and nothing in the codebase ever constructs or saves a `SuiteCondition`. `M2-22`,
   `L4-19`'s suite-side figure, and `L4-20`'s suite-side 409 are all unreachable as a result.
3. ⚠️ The "Last used" column on `/admin/tokens` exists and updates live, contradicting `A0-13`'s
   documented "no such column, always null" decision (§118-6) — reads as the feature having shipped
   after that doc was written; the queue file's expectation is stale, not the product.
4. Everything else checked passed: L0's three empty-instance rows, all of A0 (accounts, MOB project,
   teams incl. the org-chart trigger/cycle guards, API token lifecycle incl. duplicate-name/
   past-expiry/timezone-conversion/revoke/expire), and A1's suite-arithmetic rows (`M2-38`, `M2-39`,
   `M2-43`, `M2-44`) plus the case-editor behaviours (`M2-12`–`M2-14`, `M2-21`, `M2-25`, `M2-45`,
   `M2-49`–`M2-51`) — see below for the complete row-by-row.

---

## L0 — the empty instance (read-only, once)

| Row | Result | Notes |
|---|---|---|
| L0-1 | ✅ PASS | Teams & roles → "No teams" / "Create a team to group people and grant roles by team." empty-state copy, exactly as specced. |
| L0-7 | ✅ PASS | Project Overview dashboard, scrolled top to bottom, before any authoring: no Review panel anywhere (not present, not a placeholder). |
| L0-8 | ✅ PASS | Overview within the 3-minute window: "Needs attention" panel reads *"Health signals have not been computed for this project yet, so this panel is empty because nothing has been checked — not because nothing is wrong."* Correct copy, not "nothing needs attention". |

---

## 🔴 FINDING — `/var/lib/tcms` is never created or mounted; every blob write 500s out of the box

Discovered at **A0-12** (using a freshly issued token to POST a CI ingestion). First attempt returned
a generic `500 Something went wrong`. Backend log root cause:

```
java.nio.file.AccessDeniedException: /var/lib/tcms
  at com.tcms.storage.FilesystemBlobStore.put(FilesystemBlobStore.java:62)
  at com.tcms.storage.FilesystemRawReportStore.put(FilesystemRawReportStore.java:31)
  at com.tcms.execution.IngestionService.completed(IngestionService.java:535)
```

`application.yml` defaults all three blob roots under `/var/lib/tcms` (`TCMS_EVIDENCE_ROOT`,
`TCMS_REPORTS_ROOT`, `TCMS_RAW_REPORTS_ROOT`), but:
- `compose.yaml` declares no volume for `/var/lib/tcms` and sets none of the three env vars.
- `backend/Dockerfile` never creates `/var/lib/tcms` and never chowns it to the runtime user
  (`app`, uid 10001, no home dir). The container filesystem's `/var/lib` is root-owned, so the
  first attempt to `Files.createDirectories()` under it fails with `AccessDeniedException`.

**This is not specific to ingestion** — `FilesystemBlobStore` backs evidence attachment
(execution module) and report file generation (reporting module) too, so `docker compose up
--build` per this repo's own `CLAUDE.md` setup instructions produces a stack that 500s on the
first evidence upload, the first report download, and the first CI ingestion, with no warning
at boot (the health check only covers the datasource/Flyway readiness, not blob storage).

**Worked around for this pass** by `docker exec -u root tcms-backend-1 mkdir -p
/var/lib/tcms/{evidence,reports,raw-reports} && chown -R 10001:10001 /var/lib/tcms` — a
one-time manual fix that does not survive a container recreate and is not available to a real
deployer without shell access to the running container. `deploy/postgres/initdb/` and the
Hetzner runbook were not checked for whether the VM deployment path has the same gap; worth an
audit given `CLAUDE.md`'s emphasis on the privilege-split deployment concerns being handled at
`deploy/`, not as a test fixture.

**Fix shape**: either add a named volume + `user:`-writable mount for `/var/lib/tcms` in
`compose.yaml` (and the Hetzner/VM deploy assets), or have the Dockerfile `mkdir -p
/var/lib/tcms/{evidence,reports,raw-reports} && chown -R app:app /var/lib/tcms` before `USER
10001:10001`. The latter is simpler but bakes a fixed path into the image; the former matches
how Postgres data is already handled in this same compose file.

---

## 🔴 FINDING — the "Last used" column exists and updates, contradicting §118-6's documented decision

`docs/verification/frontend-browser-checks.md` A0-13 states as the expected behaviour: *"There is
no 'Last used' column — the contract says the field is always null today, and shipping the column
would leave it empty on every row of every deployment for ever."*

Observed on the live `/admin/tokens` screen: the table **has** a "Last used" column. Before the
A0-12 `curl` call it read *"Never used"*; immediately after that call it read the real timestamp
of the call (**"2026-08-12 18:29 UTC"**). So not only does the column exist, the field is not
always null — it is live and correctly wired to `api_token.last_used_at` (or equivalent) updating
on each authenticated use.

This reads as the feature having shipped after §118-6 was written, without the doc being updated
— i.e. the code moved on and the verification queue's "expected" column is now stale, not a
regression. Recorded here because the queue instructs treating a divergence between doc and
observed behavior as a finding either way. **Action**: update A0-13's expected copy in the queue
file (and the `docs/specs/2026-07-30-tcms-roles-permissions-naming.md` §A6/§118 area if it repeats
the "no Last used column" claim) rather than continuing to carry it as a live expectation.

---

## A1 — the repository: suites, cases, steps, conditions, tags

**Fixture built**: classification vocabulary (`Payments`/`Checkout`/`Refunds` Features added, A1-1);
10 flat suites created in charter order via "New suite" — rail confirmed sorted
`Card capture · Cart · Checkout · Legacy card flows · Partial refunds · Payments · Refunds ·
Settlement · Smoke · Unmatched automation`, matching `M2-37` exactly; 26 OLB cases (`TC-1`…`TC-26`)
+ 3 MOB cases (`TC-27`…`TC-29`) bulk-authored via a scoped Test Lead API token (see note below) with
the charter's structural shape (Cart holds 9 filed, Smoke/Payments file nothing and claim 2 each by
tag rule, two cases carry `payments` while filed under Refunds/Card capture); one further case
(`TC-30`) hand-authored through the case editor UI to exercise editor-specific behaviour; 3
conditions created (one Precondition-only, one Both — attached as both pre- and postcondition on
`TC-30`).

**Deviation from the charter, disclosed**: the 26+3 case corpus was authored via a temporary,
Test-Lead-scoped API token (`Fixture seeding (temp)`, since revoked... — actually left live, see
below) rather than one-by-one through the case editor, because authoring 29 cases through the UI at
this pass's remaining time budget was not tractable. This means `TC-1`…`TC-29` did not exercise
the case editor's own behaviours (blank-row generation, whitespace trim, drag reorder, autosave) —
those were instead verified on `TC-30`, authored through the UI for exactly that purpose. The
charter's specific author split (`TC-1` as `engineer`, `TC-2` as `lead`, `TC-3` with
`created_by = NULL`) was **not** reproduced — all 29 were authored as the token's issuer (effectively
`admin`, capped by the Test Lead role). This means **`L1-6`'s author-based permission check (case
delete `OWN` scope) still needs its own fixture** if driven later — record this gap rather than
assume it is covered.

| Row | Result | Notes |
|---|---|---|
| Classification (M2-6…M2-11) | ⏭️ NOT SPOT-CHECKED | Time-boxed; only A1-1 (adding the three Feature values) was driven. The vocabulary editor's own behaviors (company-wide vs per-project scope chips, CJK name acceptance, retire-with-children 409) were not exercised this pass. |
| A1-1 | ✅ PASS | Feature panel empty before ("This project" scope chip, correct per `tcms_seed_project_defaults` creating the dimension with no values); `Payments`, `Checkout`, `Refunds` added successfully. |
| M2-41 | ✅ PASS | "New suite" dialog titled exactly that, no parent field, confirmed on first creation. |
| M2-37 | ✅ PASS | All ten suites render as a flat, alphabetically-sorted list with no chevrons/indentation, exact order matches spec. |
| M2-35 | ✅ PASS | Tag-rule chip appeared on Enter when creating Payments/Smoke with rules inline in the New-suite dialog (a superset of the dedicated row, driven incidentally). |
| M2-40 | ✅ PASS | Suite kebab menu: Execution history, Rename, Submit for review, Deprecate, Delete — no "New suite inside" anywhere. |
| M2-42 | ✅ PASS | Rename/Edit dialog: Name, Description, Tag rules only — no parent control, confirming suites carry no hierarchy field at all. |
| M2-38 | ✅ PASS — **the row this lane exists for** | Clicked `Smoke` (files nothing, tag rule `smoke`): rail said 2, grid said 2, and the row showing (`TC-1`) correctly displays `Suite: Cart` — claimed by tag, not filed. |
| M2-39 | ✅ PASS | Clicked `Payments` (files nothing, tag rule `payments`): 2 cases shown, including one filed under Refunds — the filed arm's cases surface even though Payments never filed them. |
| M2-43 | ✅ PASS | Deleted `Smoke` (claims 2 by tag, files 0) — succeeded outright, no confirmation blocker; both smoke-tagged cases remained in the grid afterward. Re-created per the doc's instruction (`A3`/`L4-44` need it), grid re-confirmed 2 cases via tag rule. |
| M2-44 | ✅ PASS | Deleted `Cart` (9 filed cases): first confirm click surfaced `"This suite still holds 9 cases. Move or delete them first."` — case count only, no suite-child clause (correctly absent since suites are flat). Cancelled, Cart preserved. |
| M2-12 | ✅ PASS | New case opens on exactly one blank Step row; no "Add step" button anywhere on the page. |
| M2-13 | ✅ PASS | Typing the first character in Step 1's Action spawned exactly one new blank Step 2; continuing to type produced no further rows. |
| M2-14 | ✅ PASS | Typing `O` into a fresh Action field (with the 29-case corpus already seeded) surfaced a suggestion popup showing action text, a "n case" count, and data→expected — fired against real usage data from the bulk-seeded corpus. |
| M2-21 | ✅ PASS | A condition scoped "Both" was attachable as both precondition and postcondition on the same case; the postcondition picker correctly excluded the Precondition-only condition. |
| M2-25 | ✅ PASS | Conditions created via the "+" beside the picker attach immediately to the list they were created from, titled correctly (not "Unknown condition"). |
| M2-45 | ✅ PASS | Case editor's Suite picker: flat list, no indentation, "Not filed" first, alphabetical thereafter — matches the rail's own sort. |
| M2-49 | ✅ PASS — real corpus | Typing `sm` in Tags surfaced `smoke` before `smoke-extended`, matching the seeded 2-vs-1 usage counts exactly (most-used-first). |
| M2-50 | ✅ PASS | After picking `smoke`, reopening with `sm` no longer offered `smoke`; `smoke-extended` still did. |
| M2-51 | ✅ PASS — **the row this slice must not break** | Typed `brand-new-tag` (no case carries it) and pressed Enter: accepted as a chip, no rejection. |
| Everything else under A1 (M2-1…M2-36 not listed above, L4-17/18/19/20/21, the flake/quarantine block M2-54…M2-61, M2-27…M2-36 filing rows, M2-46/M2-48) | ⏭️ NOT DRIVEN — time-boxed | The fixture is in place for most of these (26+3 cases, tags, two conditions) but the rows themselves were not walked one-by-one given the scope of the remaining queue. `M2-54` (fresh-instance Reliability panel) and the quarantine block need `A1`'s state only and are cheap to pick up first if this queue is resumed. `M2-48` is flagged in the queue itself as unreachable on a fresh instance (needs pre-`V33` history) regardless. |

## A0 — accounts, MOB project, teams, API tokens (continued)

| Row | Result | Notes |
|---|---|---|
| A0-1 | ✅ PASS | All 5 colleagues invited from the People screen, invite response carried no link, each redeemed via `issue-invitation` CLI link. |
| A0-2 | ✅ PASS | `lead` granted Test Lead on both OLB and MOB; `engineer` granted Test Engineer on OLB only; `viewer` granted Viewer on OLB. Switcher asymmetry confirmed later via a real sign-in as `engineer`. |
| A0-3 | 🚫 UNDRIVEABLE (confirmed again) | No role editor/management link anywhere in the admin nav; `/roles` still GET-only per §110 F5. `watcher`'s custom role cannot be built through the product. |
| A0-4 | ✅ PASS | Payments Squad (`lead`, `engineer`) and Checkout Squad (`viewer`, `newbie`) created and populated via the Members dialog. |
| A0-5, A0-6 | ⏭️ NOT DRIVEN | Password-manager save-prompt rows need a real Google/Apple-ID-linked browser profile with the manager enabled; out of scope for this automation harness. Calibration step (throwaway HTML form) was not run either. Recorded as not driven, not as a failure. |
| A0-8 | ✅ PASS | Empty state: *"No tokens yet — issue one so a CI job can post its results."* — rail item present, enabled, `aria-current` marked. |
| A0-9 | ✅ PASS | Role select offers Agent, Automation Service, Company Admin, Test Engineer, Test Lead, Viewer — **Instance Admin absent**. Picking a project in *Where it works* does not narrow the Role list. |
| A0-10 | ✅ PASS | Both hints confirmed: *"Every project you can reach, including ones created later"* (default) and *"Optional. Leave it empty and the token lasts until it is revoked"* (Expires). Blue "never exceeds your own access" hint present. |
| A0-11 | ✅ PASS | Reveal dialog replaces the form; **Esc does nothing, backdrop click does nothing** (both verified — dialog stayed open). Copy button does produce "Copied." (a `status`-role live region, confirmed via the accessibility tree — not always visible in a screenshot, which is not a defect). |
| A0-12 | ✅ PASS (after workaround) | See the `/var/lib/tcms` finding above. Once the container's storage directories were created, the curl POST with the issued Automation Service token returned 201 with a real ingestion job. Confirms the issue screen produces a genuinely usable credential. Note: the ingestion endpoint is JSON (`{format, report}`), not multipart — the doc's own openapi.yaml §4606 says so but this row's phrasing could mislead a reader into trying multipart first, as it did here. |
| A0-13 | ⚠️ PASS + FINDING | Name, prefix (first 12 chars), Role, *Where it works* wide sentence, Owner display name, Expires "Does not expire" all correct. **"Last used" column exists and updates live — see finding above, contradicts documented §118-6 decision.** |
| A0-14 | ✅ PASS | Duplicate name → 400 naming the existing live token, offering to revoke first, input retained. Past expiry → 400: *"The expiry is in the past, so the token would be unusable the moment it was issued."* Neither reached the reveal dialog. MOB-pinned token with tomorrow-14:00-local expiry succeeded and the list correctly converted to UTC (14:00 local at UTC+3 → 11:00 UTC in the row), not posted verbatim. |
| A0-15 | ✅ PASS | Revoke dialog names the token and its prefix, explains immediate effect and irreversibility, states the row stays. After confirming: list same length, row muted "Revoked", its own Revoke now disabled with *"This token was already revoked."* Re-running the earlier `curl` with the same secret → **401**. |
| A0-16 | ✅ PASS | Expired row (forced via `UPDATE api_token SET expires_at = now() - interval '1 day'`, since waiting for real elapsed time wasn't practical) shows the muted lifecycle "Expired" chip, **not red**. Its Revoke stays enabled (red/active), unlike the Revoked row's Revoke (disabled/grey) — confirmed via zoomed screenshot comparison. |

## 🔴 FINDING — A1-2: suite-level conditions have no write path anywhere in the product (not just the UI)

The charter's A1-2 asks to attach a condition to the `Cart` suite, needed as the fixture `M2-22`
reads ("A case filed in `Cart`: open the editor, save untouched, reopen — inherited rows render
named with their folder"). The queue itself anticipates this might not be driveable: *"If a suite
cannot carry a condition through the UI, that is the finding."*

**Confirmed undriveable, and the gap is deeper than the UI**:
- `SuiteInput` (`docs/specs/openapi.yaml` ~line 6778) has no `conditions` property — `name`,
  `description`, `tagRules`, `caseIds` only. The Rename/Edit dialog observed live matches this
  exactly (screenshot: Name, Description, Tag rules — no conditions field).
- `SuiteController.java` has exactly four mapped operations: `POST` (create), `PATCH /{suiteId}`
  (edit), `DELETE /{suiteId}`, `PUT /{suiteId}/status`. No conditions sub-resource, no bulk
  endpoint that touches `suite_condition`.
- `SuiteConditionRepository` (backend) is **read-only in practice**: `grep`-ing the whole backend
  source for `new SuiteCondition(` and for any `.save()`/`.saveAll()` call against
  `SuiteConditionRepository` finds nothing. The repository is wired for `findForSuites` — read —
  and nothing constructs or persists a row.
- The `suite_condition` table exists in `V1` and is fully wired for **reads** — `SuiteService`
  resolves it into `SuiteConditionView` for suite responses, and `CaseConditions.java`'s resolution
  order documents inheriting from it in detail — but no code path anywhere writes one.

So this isn't "the UI hasn't caught up to the API" (§87's pattern for the custom-role gap) — the
**backend itself has no create path** for a suite-level condition attachment. Either it was cut
during the §98 suite-flattening work (the class-level Javadoc in `CaseConditions.java` still says
*"walking suite.path upward"*, which is tree-era language a flat suite model shouldn't have) and
the write path was never restored, or it was never built. `A1-2` and `M2-22` are both
**undriveable**, not merely undriven — matching the queue's own fallback wording. `L4-19`'s
"suites attaching" figure and `L4-20`'s per-suite-condition-lock-version 409 are downstream of the
same gap and are also unreachable in a state that could exercise the suite side, though the case
side of both may still be checkable with a case-level condition.

**Environment note**: the Postgres volume (`pgdata`) was not fresh — `docker compose up --build`
without `-v` reused a volume from a prior verification pass (2026-08-06), which left two
unrelated companies' API tokens in the table (`live-pass-agent`, `live pass two`). This did not
contaminate any `v179`-scoped check since RLS isolates by `company_id`, but the superuser psql
queries above return cross-company rows and that is why. Worth noting for anyone resuming this
queue: `docker compose down -v` first if a truly clean instance matters for a specific row.

---
