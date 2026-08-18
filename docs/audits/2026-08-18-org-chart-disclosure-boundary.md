# T9 — the org chart's disclosure boundary, over real HTTP

**Date:** 2026-08-18 · **Target:** `GET /api/v1/org-chart` on a locally provisioned instance ·
**Authorization:** this project's own feature, on a disposable local stack bound to `127.0.0.1`.
**Task:** T9 of `docs/plans/2026-08-17-org-chart-disclosure-pass-tasks.md`; the specification is
`docs/plans/2026-08-15-org-chart.md` **§4.4**, row by row.

**Result: no finding.** 69 of 69 assertions green, 45 tamper probes with no disclosure. Nothing was
fixed because nothing was found; no product code was changed by this pass.

**What this pass is not.** It did not run the §65 queues — `§241 OC-1` is the *mutation* form of this
same boundary and is deliberately not this lane's to run. It asserts only on what the server sent to
a named caller.

---

## 1. The instance, as re-runnable commands

Everything below runs against a **fresh volume**, so it reproduces from nothing. Harness scripts are
reproduced in §5; they are throwaway curl wrappers and are deliberately not committed.

```bash
cp .env.example .env                 # the checked-in .env predated TCMS_IDENTITY_SECRET_KEY and
                                     # compose refused to interpolate; refreshing it is the fix
docker compose down -v               # -v so initdb re-runs and the fixture starts from empty
docker compose up --build -d
docker compose run --rm backend provision-company --slug=acme   --name="Acme"   \
    --admin-email=admin@acme.test   --admin-name="A. Admin"
docker compose run --rm backend provision-company --slug=globex --name="Globex" \
    --admin-email=admin@globex.test --admin-name="G. Admin"
```

No SMTP relay is configured, so neither invitation is emailed. Each is redeemed through
`POST /api/v1/auth/invitations/{token}/accept` with the token the CLI printed; later invitations are
re-issued with `docker compose run --rm backend issue-invitation --slug=<slug> --email=<address>`,
which is the documented operator fallback. **Every principal below signs in as itself through
`POST /api/v1/auth/login`.** No API token is used anywhere in this pass: a token caps *its owner's*
permissions, so an admin-issued one is an admin's reach narrowed rather than the member's, and
probing with one would measure the wrong principal entirely.

Everything after provisioning is created over the API as the company admin — `POST /projects`,
`POST /users`, `POST /teams`, `PUT /teams/{teamId}/members/{userId}`, `POST /role-assignments`.

### Company `acme` — four projects, five people

| Project | Key |
|---|---|
| Alpha Banking | `ALPHA` |
| Bravo Retail | `BRAVO` |
| Charlie Logistics | `CHARLIE` |
| Delta Health | `DELTA` |

| Person | Grants | Teams | Sees, as columns |
|---|---|---|---|
| `admin@acme.test` | Company Admin (COMPANY) | — | 4 |
| **`member@acme.test`** | **Test Engineer on `ALPHA` only** | QA Guild | **1 — the probe principal** |
| `colleague@acme.test` | Test Lead `BRAVO`, Viewer `CHARLIE`, Viewer `ALPHA`, + `DELTA` via Ops Guild | QA Guild (lead), Ops Guild (lead) | 4 |
| `outsider@acme.test` | **Instance Admin** (`grant-instance-admin`) | — | 4 |
| `nobody@acme.test` | none | — | 0 |

Teams: `QA Guild` (Test Engineer on `ALPHA`), its child `QA Platform` (empty — the nesting arm), and
`Ops Guild` (Test Engineer on `DELTA`, colleague only).

**Four projects rather than two is load-bearing**: a dropped filter then reads as a count — 1 versus
4 — and not as a coin flip. `colleague` exists so that a person the member *can* see holds roles on
three projects the member *cannot*; that is the shape a leak would surface through.

**`outsider` is an Instance Admin on purpose.** Assertion 5 is unfalsifiable without a live
SYSTEM-scope grant on the instance — with nobody holding one, "no Instance Admin fact reaches the
wire" is true of a document that has nothing to leak.

### Company `globex` — the cross-tenant control

Projects `ZULU`, `YANKEE`; `gmember@globex.test` is Test Engineer on `ZULU` and leads
`Globex Secret Squad`. Assertion 6 cannot be made against a single-company instance.

### One correction made mid-fixture

The first cut granted `QA Guild` a role on `DELTA` while `member` was in that team, so the member
held `project:read` on **two** of four. That is the fixture being wrong, not the server: the grant
was moved to `Ops Guild` (colleague only) and the member's reach re-measured at exactly one column
before any assertion was scored.

---

## 2. §4.4 row by row — 69 assertions, all green

Every check is made against the **raw response body** for a named caller. Rows whose answer is
"unredacted" are checked too: a field wrongly withheld is also a defect.

| # | §4.4 row | Check | Result |
|---|---|---|---|
| A1 | matrix column | The three unreachable projects' **ids, keys and names** are absent from the member's raw document — grepped as text, not read off parsed columns | ✅ |
| A1 | matrix column | `projects[]` has length **exactly 1** | ✅ |
| A1 | §4.3 Option D | **No count anywhere**: no key matching `count`/`total`/`hidden`/`omitted`/`more`/`redact` at any path | ✅ |
| A1 | *unredacted* | `ALPHA`'s id, key, name and `archived` **are** served | ✅ |
| A2 | a matrix cell | Every cell's `projectId` is a served column; **no null-keyed and no placeholder cell** | ✅ |
| A2 | a matrix cell | `colleague`, who holds four roles, shows the member **exactly one cell** — `ALPHA` | ✅ |
| A2 | a matrix cell | **Team project chips are filtered by the reader too**: `Ops Guild` (granted `DELTA`) shows the member no chip, `QA Guild` shows `ALPHA` | ✅ |
| A2 | a matrix cell | The zero-reach member gets **0 columns, 0 cells, 0 chips — and all five people** | ✅ |
| A3 | `lastLoginAt` | **null on every row** for member, colleague and the zero-reach member | ✅ |
| A3 | *unredacted* | **Not** null for the Company Admin, who holds `company:members` | ✅ |
| A4 | grant `id` | None of the seven live grant ids (read from `GET /role-assignments`) appears anywhere in the member's document | ✅ |
| A4 | grant `id` | No `assignmentId` / `grantId` / `revocable` / `canRevoke` key at any path | ✅ |
| A5 | SYSTEM scope | `outsider` **is** an Instance Admin; the string never appears — not for the member, not for the Company Admin, **not in the Instance Admin's own chart** | ✅ |
| A5 | `companyRoles[]` *unredacted* | The Company Admin's `Company Admin` **is** served to an ordinary member | ✅ |
| A5 | `companyRoles[]` | The `Company Member` baseline is filtered out of every row | ✅ |
| A6 | tenancy | Globex's project ids, project keys, member id, team name and email domain are absent from **all five** acme charts; acme's are absent from **both** globex charts | ✅ |
| A6 | tenancy | The Instance Admin's acme chart is still a **company** document — five people, not seven | ✅ |

`id`, `displayName`, `avatarUrl`, `email`, `status`, `teams[]`+`isLead` are §4.4's unredacted rows
and are served to every member, as the table requires; they are visible in the captured documents and
are what A2/A3/A5 are read against.

---

## 3. Assertion 7 — 45 tamper probes, all refused

All with the **member's own session token** unless stated. Scored identically every time: status,
column count, and a raw-byte search for any id the member may not see.

| Class | Probes | Outcome |
|---|---|---|
| Query parameters | `companyId` (both companies), `projectId`, `projectIds`, `userId`, `asUser`, `includeAll`, `unredacted`, `filter`, and pairs | **11 × `200`, one column, no leak** — every parameter ignored |
| Headers | `X-Company-Id`, `X-Tenant-Id`, `X-Company`, `X-User-Id`, `X-Forwarded-User`, `X-Original-URL`, `X-Project-Id` | 7 × `200`, one column, no leak |
| Path | `/org-chart/{companyId}`, `/{otherCompanyId}`, `/{projectId}`, `/`, `/all`, `%2f`-encoded, `/../org-chart`, `;companyId=`, `/projects/{id}`, `.json` | `404`/`400`/`401`; the one that resolves is the endpoint itself, with one column |
| Method | `POST` `PUT` `PATCH` `DELETE` → `405`; `HEAD` `OPTIONS` → `200` with no body | no write surface |
| Credential | absent header, garbage bearer, **`alg=none` forgery carrying the acme admin's `sub` and `co`**, and a valid globex token whose `co` claim was rewritten to acme | **4 × `401`** |
| Control | globex admin, untampered | `200`, its own two columns — the probe scoring can see a difference |

The controller takes the caller from the token and the company from `CompanyContext`; neither is a
parameter. These probes are the empirical half of that claim.

---

## 4. The trap, and why this pass is not inside it

§241's own most valuable-looking test stayed green with the disclosure filter removed, because a
leaked cell still names somebody who legitimately holds that role: **a test about who is NAMED passes
straight through a rule about who is ASKING.**

So the instrument was checked rather than trusted. The member's captured document was replaced with
the **Company Admin's** — byte-for-byte what a dropped `reach.maySee` would have sent that member —
and the same suite re-scored:

```
TOTAL pass=56 fail=13     # leaked document
TOTAL pass=69 fail=0      # real document
```

Nothing in the product was mutated to get that number; only the file the assertions read. It is a
control on the harness, not a run of `§241 OC-1`.

**One check did not go red, and that is the finding-shaped part of this section.** A2's *"every
cell's `projectId` is a served column"* stayed **green on the leaked document** — because there the
columns leaked too, so the document was internally consistent and simply larger. That is the §241
trap in miniature, at the HTTP layer. What actually caught the leak was the count (`1` vs `4`) and
the raw-text search for ids the caller may not hold. **A consistency assertion cannot detect a
uniform over-disclosure; only an assertion anchored to what THIS caller is entitled to can.** Anyone
extending this suite should add checks of the second kind.

---

## 5. Re-running it

The battery is three throwaway scripts over `curl` + `jq` — `lib.sh` (request helpers),
`assert.sh` (the 69 named checks), `probe.sh` (the 45 probes) — driven from the ids the
provisioning commands in §1 print. They are not committed: they hard-code one run's UUIDs, and the
fixture is a dozen documented API calls that any later pass should rebuild rather than inherit. §1
and §2 are written so it can be reconstructed exactly.

Two harness bugs were hit and are worth stating so a re-run does not read them as findings:
both admins have the local part `admin`, so keying captures on it silently overwrote one company's
document with the other's (five spurious reds); and a `${t:+-H "Authorization: Bearer $t"}` expansion
word-splits, sending a broken header and reading as `401`.

---

## 6. What was NOT driven

- **`§241 OC-1`…`OC-8` and the browser queue** — deferred and batched under §65, and OC-1 is the
  mutation form of this very boundary. Untouched.
- **T10**, the manual test-case suite — not this slice.
- **`archived` project behaviour** was observed (`archived: false` served on the visible column) but
  no archived project was created; whether archiving changes reach is `GET /projects`' question, not
  §4.4's.
