# §230 — the requirement a case could not reach

**A8 of `docs/audits/2026-08-15-flow-review-and-improvements.md`**, whose §0 ledger is the tracker
for the authorised A1–A8 scope. The fourth and last slice of that run, after **§227** (A2 + A3 + A5,
reports), **§228** (A1 + A7, the case's status and the suite's review surface) and **§229** (A4 + A6,
the assignee and the register's correction).

**All eight are now done.** What the ledger still carries after this is out-of-scope work — `B`, `C`,
`D` and `E`, none of it started, `E2` and `C10` named as the cheapest next steps.

One phase, **`frontend/` only**: no endpoint, no payload, no permission, no migration, and
`openapi.yaml` is untouched. That is the shape of the finding — every piece of this journey already
existed and nothing joined them.

---

## What was broken

A `doc_link` whose target is a **block** is creatable from the documentation side alone. That
asymmetry is argued at the service (`openapi.yaml`'s rationale at the case end, §169's split: a link
to a paragraph belongs to the documentation, a link to a file belongs to the case) and it is not
what this slice changes.

What it left was a **dead end on the case**. `DocLinksPanel` renders block links, calls them the
coverage claim, and its empty state says *"Not linked to any specification. Nothing traces this case
back to a requirement."* — a sentence naming a target kind the panel offers no way to produce. The
only control on it wrote external targets. So a tester reading a case who wanted to claim a
requirement paragraph had to know, from nowhere on that screen, that the act lives in another route;
and having got there, find the case again in a type-ahead over a project's thousands.

`LinkBlockDialog` is what §172 built to make the documentation side possible at all. It has always
opened with an empty picker, which is right when the reader is on the page reading it and wrong for
every reader who arrived carrying a case.

## What shipped

- **A second button on the Traceability panel** — *Link a specification* — beside the existing
  *Link code or a URL*. It navigates to `/projects/{projectId}/docs?linkCase={caseId}`.
- **`DocsPage` reads `linkCase`**, fetches that case once, announces it in a banner with the way
  back to the case and a *Stop linking*, and hands it down to every block's dialog.
- **`LinkBlockDialog` takes an `intendedCase`** — pre-selected in the picker and pre-typed into its
  input, so Link is enabled without a keystroke.

## The intent lives in the URL, and three navigations exist to break it

`useSearchParams`, this SPA's rule for anything narrowing what a screen is doing, and here it is not
a preference: the jump comes from another route entirely, so there is no component state that spans
it. What that buys is that the intent survives a reload and can be sent to a colleague; what it
costs is that **every navigation on the destination screen has to carry it**, and this screen has
three that did not:

| Where | What would have happened |
|---|---|
| `/docs` with no page id redirects to the first page | The redirect rebuilt the path alone — so the intent died **on arrival**, in the one route the feature is entered by. |
| The page tree's links | The page somebody lands on is rarely the page their case verifies. The intent would have died on the first click of the journey. |
| The page-missing redirect | Same, one edge case further out. |

All three now append the query string, and `BlockLinks.test.tsx`'s *keeps the case while the reader
walks the tree* drives the second — the one a reader hits every time.

## `doc:edit`, not this panel's own key

The new button is a **jump**, and the write it leads to is charged `doc:edit` at the server. Asking
for `case:edit` — the key every other control in this panel asks for, one line away — compiles,
reads as consistent, and sends a Test Engineer's Viewer colleague to a page whose every Link button
is disabled, with nothing on the case to say why. `DocLinksPanel.test.tsx`'s *asks for doc:edit*
drives a caller holding `case:edit` and not `doc:edit`, and asserts the two buttons' opposite
verdicts **in one render** — which is the only observation that can tell the right gate from the
plausible one.

## The picker had to accept a value its own search did not return

Two mechanical things, both silent when wrong:

- **The input is controlled** (`inputValue={query}`), so a `value` set without a matching `query`
  renders as a selection nobody can see — a dialog that looks empty and posts a case. The seed sets
  both, through one `caseLabel` used by the label, the input and the option.
- **The seeded case is merged into the options** when the server's answer does not contain it, and
  `isOptionEqualToValue` compares by id. The query is the case's own label, `filterOptions` trusts
  the server by design (`BindTestDialog`'s rule), and the answer is whatever the answer is — so the
  ordinary case is a value matching no option, which MUI renders as a warning and a picker that
  clears itself. The test's search double deliberately answers **empty** for this reason.

## Not done here

- **The reverse jump** — landing on a case from a block — already exists as the source chip on
  `Docs.Block.Links.Item`, and is untouched.
- **Nothing about B1's `PATCH` semantics, C10's MCP artifact tools, or E2.** They remain the
  ledger's next candidates.

---

## Gates

All on `shanks`, per `CLAUDE.md`:

| Stack | Result |
|---|---|
| `frontend` `npm run build` / `npm run lint` | clean, 0 warnings |
| `frontend` `npm run test:run` | **1478 tests / 109 files** (1464 at §229 phase 1, +5 from phase 2, +9 here) |
| `backend` `./gradlew build` | **BUILD SUCCESSFUL** — 2917 / 0 / 0. **§229 phase 2's outstanding run, completed here** |
| `packages` build / lint / tests | clean, 0 warnings, **78 / 128 / 123**. Also §229 phase 2's outstanding run |

**The frontend suite's first run on shanks failed 19, and two consecutive re-runs were fully
green.** No code changed between them. It is the §186-family load sensitivity — the run overlapped
the tail of the backend build on the same box — and the failures were in `ArtifactTable.test.tsx`
and its neighbours, which this slice does not touch. Recorded rather than dropped: a first run
that fails and a second that passes is worth knowing about before somebody reads one red run as a
regression, and it is also the reason `~/bin/tcms-remote` gates should not be raced against each
other on a six-core box.

**Two of those four are §229's debt, not this slice's.** Phase 2 of §229 landed with its full
`./gradlew build` unrun — another session was running Gradle in the same `~/builds/tcms` tree and
the two writers collided — so that commit sat locally and unpushed. The same hazard recurred at the
start of this slice (a `./gradlew build` from another session was live in that tree again), which is
worth knowing: **`~/bin/tcms-remote` syncs with `rsync --delete`, so the collision is bidirectional**
— running it while somebody else's build is live corrupts theirs as surely as theirs corrupts yours.
Check `pgrep -af gradle-wrapper.jar` on shanks before syncing, not after a failure.

## Queue rows written and not run (§65)

`§230 LK-1…LK-6` in `docs/verification/frontend-browser-checks.md`, lane **A1**.

**No rows in `backend-mutation-checks.md`, and that is a statement rather than an omission**: this
slice changed no server code, so there is no mutation that could be red. `LK-5`'s pair — the caller
holding `case:edit` and not `doc:edit` — is not driveable with the seeded six and is named in the
row rather than skipped.
