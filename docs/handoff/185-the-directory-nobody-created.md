# §185 — the directory nobody created, and the check that was green by cache

**Three phases: `backend/` twice, then the deployment prose and the record. No migration, no
contract change, nothing in `frontend/` or `packages/`.**

Not from §184's candidate list. The slice was chosen by reading the working tree: a live browser
pass had run on **2026-08-12**, produced a findings file with two 🔴, and been left **untracked** —
no handoff section, no index row, no marks in the queue it was driven from. Its first finding is
that `docker compose up --build`, the command this repository's own `CLAUDE.md` gives as the way to
run the stack, produces a backend that 500s on the first evidence upload, the first report download
and the first CI ingestion.

## The finding is not the missing directory. It is that this was the second time.

`application.yml` has defaulted three blob roots under `/var/lib/tcms` since §160. Nothing created
them. `/var/lib` is root-owned in the runtime base image and the process runs as UID 10001, so the
first `Files.createDirectories` under it throws `AccessDeniedException` — and it throws at the first
*write*, which is long after the container reported healthy, because readiness covers the datasource
and Flyway and nothing else.

**§110 found this on 2026-08-06.** Its F10 reads, in full:

> **F10 — the local dev `compose.yaml` has no evidence/reports volume**, so `POST .../evidence`
> 500s on `AccessDeniedException: /var/lib/tcms`. **Production is unaffected** — `compose.prod.yaml`
> bind-mounts both roots explicitly, with §107's reasoning in a comment. The root `compose.yaml` has
> no volume at all. Worked around inside the container; does not survive `docker compose down`.

Every clause of that is true. It fixed nothing, and six days later the next live pass hit the
identical stack trace at its first blob write and spent the same hour on the same
`docker exec … mkdir && chown` workaround. **A finding recorded against the artifact you happened to
be looking at, with a sentence exonerating the others, is how a defect survives being found.**

And *"production is unaffected"* has since gone false without anybody touching it. §160 added a
**third** root, `compose.prod.yaml` mounts it, and the Hetzner runbook's §7 `mkdir`/`chown` still
names two — so `/var/lib/tcms/raw-reports` was created root-owned by the bind mount on the live VM
and every CI upload there has failed since. That was observed independently, on the host, by
`docs/plans/2026-08-12-multi-app-hosting.md` §5, and is fixed here. **A per-deployment fix applied to
one deployment, plus a sentence about the others, is what §110 shipped; the second finding is what it
cost.**

## Phase 1 — the image, and the rule that asks every artifact

`backend/Dockerfile` creates the three roots and chowns them to 10001 before `USER`. **The image,
because it is the one artifact every deployment shares** — so it cannot be fixed per-deployment
again.

`compose.yaml` gains a named volume at `/var/lib/tcms`, which makes `up --build` work and pairs the
blobs with `pgdata` under the `down -v` the setup instructions already document as the reset.

**A named volume and a bind mount behave oppositely, and the whole shape of the fix is that
difference:**

| Mount | Ownership | What the image's `mkdir` buys | Measured |
|---|---|---|---|
| none (writable layer) | the image's | everything; bytes die with the container | dirs `10001:10001`, `touch` **WRITABLE** |
| **named volume** (`compose.yaml`) | seeded **from the image's content, ownership included** | everything — the volume comes up owned by 10001 *because* those directories exist in the image | fresh volume, dirs still `10001:10001`, `touch` **WRITABLE** |
| **bind mount** (`compose.prod.yaml`) | the **host** directory's, image ignored outright | **nothing.** §7's `chown` is the entire mechanism | root-owned source dir → `mkdir: Permission denied` |

**That table was reasoned when it was written and is measured now** — 2026-08-13, on a real
`docker build` of this image on `shanks`, four runs, one per row plus the writable-layer probe. It
is worth the paragraph because the middle row is what the whole of `compose.yaml`'s fix rests on:
if a named volume did *not* inherit the image's ownership, the mount would shadow the directories
with a root-owned empty one and phase 1 would have made the local stack worse rather than better,
with all three rules green. The bottom row is why phase 2 exists.

`ConfigConventionTest` — no context, no database, source text only, which is where it already
checked the JWT default §108 shipped for a year — derives the roots from `application.yml` and asks
each provisioning artifact about **every** member:

- `everyStorageRootIsCreatedInTheImage`
- `everyStorageRootIsMountedInProduction` (the mount **and** the env var: §107's decision that the
  app's path and the mount target are one string, which fails in opposite directions if you do one)
- `everyStorageRootIsPersistedLocally` (derived, not literal — every root must be *under* what
  compose mounts)

The population is asserted before it is used. A text rule that matches nothing passes every check it
makes, which is §154's halved corpus and §156's swallowed literals; `storageRoots()` fails if it sees
fewer than three.

**Measured, by deleting the fix and re-running:** `everyStorageRootIsCreatedInTheImage` and
`everyStorageRootIsPersistedLocally` fail, the production rule and the three pre-existing rules stay
green.

## The second finding, which came out of taking that measurement: the rules were green by cache

Deleting the fix and re-running reported **`BUILD SUCCESSFUL`, `:test UP-TO-DATE`.**

Gradle tracks a `Test` task's inputs as its classpath and its resources. `Dockerfile` and
`compose.yaml` are neither, so editing exactly the file a rule exists to check leaves the task
up-to-date and **the rule about the edit never executes**. Only `--rerun-tasks` produced the
failures above.

This is not specific to the new rules. **`IdempotencyKeyCapTest` has read
`../docs/specs/openapi.yaml` the same way since §136** — a contract edit alone has never re-run it.

`build.gradle.kts` declares the out-of-source artifacts as task inputs: `Dockerfile`,
`../compose.yaml`, and the `../deploy` and `../docs/specs` *directories*, so a fifth artifact is
covered without anyone remembering the list — which is the hand-kept tally §152–§156 replaced four
times. `optional`, because the Docker build stage configures this build with neither path present.
Measured after: breaking the Dockerfile alone re-runs the task and fails the rule.

**It is the same shape this repo keeps meeting from the other end.** §183's file says it plainly —
*a written check that is never driven is a claim, not an observer* — about a queue row sitting unrun
for five slices. Here the check was written, committed, and reported green while not running at all,
which is the worse arrangement: an unrun queue row at least does not say `PASSED`.

## Phase 2 — refuse to start, rather than to answer

Every check in phase 1 reads a file **in this repository**. The deployment that matters most is a
directory on a host no test can see, and it is *already wrong* there.

`FilesystemBlobStore`'s constructor creates its root and probes it, and throws naming the kind, the
path and the `chown` — because the fix is a `chown` and an operator reading a stack trace should not
have to find the runbook. In the **constructor**, not in each adapter, so a fourth adapter cannot
forget it; the three existing call sites did not change.

**The cost is deliberate and worth stating as a decision.** A deployment whose roots are not
writable now refuses to start, where before it started and 500'd at the first upload weeks later.
That includes the operator CLI, which builds the same beans. **The live VM is in that state today**,
so the next deploy there will refuse until §7 is run — which is the check working, and the runbook
now says so in those words.

**The test provokes the failure without touching permissions.** The obvious fixture is `chmod 0555`
on a parent, and it is a fixture that quietly stops testing anything when the suite runs as root —
one CI configuration away on the nightly lane, and a test that passes by being unable to fail is
what §184 measured its way out of. A root whose parent is a regular **file** cannot be created by
any uid. The second test pins the other direction, which is the one that would make this a
regression: **absent is created, not refused** — every test root is a `/tmp` path that does not
exist yet, and so is a first boot against a fresh named volume.

## Phase 3 — the prose no rule can read, and the record

- **Hetzner runbook §7** and **`2026-08-06-shanks-deployment.md`** (two rows): `raw-reports` added
  to the `mkdir`/`chown`, struck through with the reason, plus what the boot refusal now looks like
  to an operator mid-deploy.
- **The 2026-08-12 findings file and the Remote Control runbook are committed**, having been
  untracked for a day.
- **`frontend-browser-checks.md` gains a "what has actually been driven" table at its head.**
  Two sittings, both partial, neither having marked a row in place — so *do not read an unmarked row
  as unrun*. It names where each sitting stopped (the next resumes at `A2 — plans`), the fixture and
  the still-live seeding token left for it, and what that fixture does **not** cover.
- **`A0-13`'s expectation was stale and is corrected.** It asserts *"There is no 'Last used'
  column"*; §132 gave `api_token.last_used_at` a writer and `ApiTokensPage.tsx` added the column in
  the same slice, both files carrying the reversal struck through in their own headers, and only the
  queue row went on asserting the absence. The pass observed the column, watched it go from
  *"Never used"* to a real timestamp, and correctly filed it as a divergence. **A stale expectation
  is worse than a missing row**: it asks the next reader to file correct behaviour as a defect.

## The other 🔴, verified and left open

**Suite-level conditions have no write path anywhere in the product.** Re-derived rather than taken
on the pass's word: `new SuiteCondition(` occurs nowhere in `backend/src/main`, nothing saves through
`SuiteConditionRepository`, `SuiteInput` has no `conditions` property, and `SuiteController`'s write
surface is `POST` / `PATCH /{suiteId}` / `DELETE /{suiteId}` / `PUT /{suiteId}/status`. The **read**
path is fully built — `SuiteService` resolves `SuiteConditionView`, and `CaseConditions` documents
inheriting from it in detail. `SuiteConditionView`'s own javadoc already calls this "the gap's
record".

So `A1-2` and `M2-22` are **undriveable rather than undriven**, and `L4-19`/`L4-20`'s suite sides go
with them. Not taken here: it is a contract change plus a write path plus a screen, and it is a
different slice from this one. It is the top of the next list.

## Gates

Run on `shanks`. Phase 1: `./gradlew build` **SUCCESSFUL in 7m19s — 2603 tests across 527 result
classes, 0 failures** (+3 tests, no new result class — three rules in the existing
`ConfigConventionTest`). Phase 2: **SUCCESSFUL in 7m18s — 2605 tests across 528 result classes, 0
failures** (+2 tests, +1 result class — the nested `UsableRoot` group). Phase 3 changes no code a
gate runs. `frontend/` and `packages/` untouched and not re-run.

**Mutation rows `§185 SR-1`…`SR-4` recorded and not run** (§65). No new browser rows: the queue's
`A0-12` and `A1-2` already name both findings, and what this slice changed there is an expectation
and a header, not a row.

**Built and unverified** — with one exception worth stating precisely, because it is the reason this
slice exists: the *defect* is verified, twice, by two live passes. The *fix* is not.

## The next slice

§184's list, plus what this slice found and did not take:

- **suite-level conditions have no write path** — the 🔴 above, verified here, `A1-2`/`M2-22`
  undriveable until it is built;
- **resume the browser queue at `A2 — plans`** — the fixture is in place and the blocker that ended
  both previous sittings is fixed; the still-live `Fixture seeding (temp)` token wants revoking
  either way;
- the **read-only-archive** decision §159 did not take;
- **serving the relabel audit** — §174's recorded absence;
- **`CurrentUser.teams` has no reader** — §175 kept the array on a permission argument;
- **a run has no edit endpoint at all** — §177 leaned on that twice and did not build it;
- §184's named shape, untaken: **what the SPA computes from a contract enum that the server also
  computes**, derived rather than read for.

**And one this slice opened.** Phase 1's second finding is about *observers*, not about storage: a
test that reads a file outside the backend's own source sets observes nothing unless
`build.gradle.kts` says so.

**The population was measured rather than guessed, and it is exactly two** —
`ConfigConventionTest` and `IdempotencyKeyCapTest`. Both are now covered. The other five files
matching `"../"` are traversal *keys* (`"../../../etc/passwd"`), not file reads, and were checked
one by one. `ArchitectureRulesTest`'s source-text rules read `src/main/java`, `src/test/java` and
`src/main/resources`, all of which Gradle already tracks through the two compile tasks and
`processResources` — **and it strips comments before matching**, so the one edit those inputs would
miss is the one it does not care about. That is the control, and it is why this is a gap of two
files rather than of a family.

**What is not done is making it stay two.** Nothing enumerates the population, and the next rule
that reads `../docs/` or a deploy asset joins it silently — reporting `PASSED` without executing,
which is strictly worse than the unrun queue row §183 wrote about, because an unrun row does not
claim to have run. A rule over the test sources that fails when a `Path.of("..` is not in
`build.gradle.kts`'s `inputs.files` is the shape, and it is the §152–§156 family exactly: replace
the hand-kept list with the check that recomputes it.
