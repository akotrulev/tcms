# §139 — the CLI's write arms, and the `If-Match` that lies when the body is not a delta

**2026-08-08 · three phases in three commits · `packages/` only — no migration, no contract change,
no backend or frontend code.**

Phase 7 of [`docs/plans/2026-08-08-mcp-server-wip-plan.md`](../plans/2026-08-08-mcp-server-wip-plan.md):
*CLI writes — edits direct, creates as proposals*. Sixteen write commands over `@tcms/client`'s
existing machinery, which §137 built and tested with nothing calling it. The slice plan is
[`docs/plans/2026-08-08-cli-writes.md`](../plans/2026-08-08-cli-writes.md).

It found three things, and two of them are defects §137 shipped.

---

## 1. The finding: a self-read `If-Match` is only honest when the request is not a delta

Every edit needs a `lockVersion`, and the obvious convenience is for the command to fetch it —
nobody should have to run `case show` before `case edit`. That convenience is safe, and the reason
it is safe is **narrow enough that it does not generalise one command over**.

A **flag edit states its whole intent in its flags.** `PATCH` reads an absent key as *leave it
alone*, so `--title x` is a request to change the title and nothing else. A version read one
millisecond before the write is a truthful precondition, and a concurrent change to another field
is not a conflict at all.

A **whole-body edit inverts that, silently.** `--file` carries a body composed from an *earlier*
read: the caller pulled `case show --json`, changed three fields, and is sending the result. Its
precondition is the version **that** read returned, which may be several saves ago. A command that
read a fresh version here would send an `If-Match` that **matches**, the server would accept it, and
the concurrent author's words would be gone.

That is last-write-wins arriving **through the header that exists to prevent it**, with every
party's code looking correct, no error anywhere, and a green build. It is the failure §137's
`concurrency.ts` header calls *"the single worst thing this surface can do"*, reached by the
mechanism that module is.

So the rule is a type, not a convention — `core/mutation.ts`:

| Edit shape | `If-Match` |
|---|---|
| flag edit (`--title`, `--priority`, …) | read for the caller, immediately before the write |
| `--file` | **`--lock-version` is required**; the command refuses, names why, and makes no request |

`EditVersion` is `{ explicit: number } | { fresh: unknown }`. There is no spelling of *work it out*,
and `requireExplicitVersion`'s refusal names the fix — *read the resource, take the `lockVersion` it
returned, pass it* — because "version required" would read as a flag somebody forgot rather than as
the reason the flag exists.

**Its neighbour is the same shape and got the same treatment.** `strictNumber` throws on
`--lock-version latest` instead of answering `undefined`, because `undefined` means *absent* and
absent means *read a fresh one*: a typo would silently select the unsafe path. That is `CW-2` in the
mutation queue and it is the whole reason a second numeric helper exists beside `number()`.

---

## 2. §137 shipped a parser that dropped every string flag

`main.ts` parsed with `strict: false`, and the comment said why: *"so a command's own flags
(`--suite`, `--tag`) parse without every one being declared here."*

**That is not what `strict: false` does.** `util.parseArgs` gives every **undeclared** option the
value `true` and pushes what followed it into the positionals:

```
parseArgs({ args: ['--title', 'Login works'], options: { json: … }, strict: false })
→ { values: { title: true }, positionals: ['Login works'] }
```

So `first()` read `true` as absent, and **every string flag on every read command was dropped**:
`case list --q login --suite TS-7 --size 50` filtered by nothing and answered the whole project's
cases, with a 200, a full table, a correct exit code and no diagnostic anywhere. The value the user
typed was sitting in `positionals`, where nothing looked.

It was live from §137 and nothing could see it: the tests exercised only flags nobody had declared
as strings, `--json` and `--project` are global and *were* declared, and the read commands'
assertions are about what came back rather than what was asked for.

The fix is per-command `OptionSpec` declarations merged into `parseArgs`, which makes the parse
correct **and** lets the parser run **strict** — so a misspelled flag is now a usage error instead
of a boolean nobody reads. Both halves are needed: declaring the options without strict mode leaves
`--titel x` a silent no-op on a command that has already reported success.

---

## 3. `npm run build --workspaces` had never once been green in CI

`@tcms/cli` typechecks against `@tcms/client`'s emitted `dist/`. The `packages` job's own comment
asserted that *"npm's topological ordering is load-bearing here rather than incidental"*.

**npm runs `--workspaces` alphabetically, and `cli` sorts before `client`.** On a clean checkout the
CLI compiles against a `dist/` that does not exist:

```
> @tcms/cli@0.1.0 build
src/commands.ts(18,8): error TS2307: Cannot find module '@tcms/client' …
```

It passed on a developer's machine only because a previous run had left the directory behind — which
is the second half of the same defect: **a change to the client was invisible to its only consumer
until the command was run twice.** That is exactly how this slice met it, and the first symptom was
a build that failed and then passed with no edit in between.

CI does not have a leftover `dist/`, so **master has been red on this job since it was added**
(`gh run view 31260828083`: forty errors, first one `Cannot find module '@tcms/client'`). Fixed by
naming the order in the root's `build` script and pointing CI and `CLAUDE.md` at it.

The general shape is worth keeping: **three documents asserted a property of a tool that the tool
does not have** — the workflow comment, the `CLAUDE.md` command block, and §137's own handoff table.
None of them was a claim about this repo's code, which is why nothing in this repo could contradict
them. Sixth or seventh instance of the family §108 is the expensive member of.

---

## 4. What shipped

Sixteen commands, each declaring its **kind** (`create` / `edit` / `state` / `delete`) and the
permission the surface declares for it:

| Noun | Commands |
|---|---|
| `case` | `edit`, `propose` |
| `suite` | `create`, `edit`, `status` |
| `doc` | `create`, `edit`, `link`, `unlink` |
| `plan` | `create`, `edit`, `status` |
| `run` | `create`, `status` |
| `result` | `record` |
| `review` | `comment` |

**`case create` is absent and the absence is the feature** — §1.3 is settled, a generated case is a
proposal and a person accepts it. **`case accept` is absent for a stronger reason than policy**:
§138's gate is `case:create` **and no `TokenContext`**, and every request this CLI makes carries a
token, so an accept command would 403 on every invocation it could ever make. It is the one arm this
surface is structurally incapable of, which is different from one it declines to offer.

### The computed check

Surface spec §8.3, and the reason `WriteDeclaration` is a registry field rather than a comment:
`writes.test.ts` walks `COMMANDS`, drives every declared write through a recording `fetch`, and
asserts the header its kind implies — a `create` carries `Idempotency-Key`, an `edit` carries
`If-Match`, a `state` and a `delete` carry **neither**, which is asserted rather than left unsaid.

It **fails on a command it does not recognise** rather than skipping it. A conformance check that
silently ignores what it has not been told about is a check on the table, not on the code — §108's
rule, one stack over.

### Four shapes the contract forced

- **`doc edit --title` sends no `blocks` key at all.** `blocks` is the whole page in order and an
  omitted block is **deleted**, so a helpful round-trip of the page's blocks is one stale read away
  from replacing somebody's specification with an older copy of it. `CW-5` is the only mutation in
  either queue that destroys authored content rather than a field.
- **`TestPlanUpdateInput` requires `name`**, alone among the edit inputs — so a `--description`-only
  edit is not expressible on the wire. The command threads the plan's current name through from
  **the same read** that gave it the version, which is what keeps the pair consistent.
- **An execution has no display key and never will** (§6t), so `result record` takes the pair a
  person holds — `TR-5 TC-42` — and finds the execution in the run's detail. The version comes from
  the execution's **own** detail, because `TestExecutionSummary` carries no `lockVersion`; the cheap
  read is the wrong one. The status is named, not a UUID: `case_status` is per-project vocabulary.
- **`review comment` reads the subject type off the key's prefix**, one of the few places on this
  surface where a display key is not a convenience — a bare UUID cannot say what it is and the
  review list requires the type. It refuses a **closed** review locally rather than earning the
  server's 409: a review is history once it ends and re-submitting opens a *new* record, so *the
  newest review* and *the one that can still be commented on* are different questions.

### What was not filtered client-side

`suite status`, `plan status` and `run status` send **every** value the contract accepts, including
the ones an `Agent` token cannot reach. The CLI is a person's tool as much as an agent's and a token
issued under Test Lead holds `suite:approve`; a client-side ladder would be a second copy of §6g's,
in a package versioned independently of the instance it is pointed at. Each status endpoint's own
description refuses that for the same reason — *"reviving a deprecated suite is spelled `DRAFT`, not
`APPROVED`"* is exactly the rule that does not survive being written down twice.

---

## 5. Gates

| Gate | State |
|---|---|
| `cd packages && npm run build` | clean — **and note the command changed**; `--workspaces` is the broken form |
| `cd packages && npm run lint --workspaces` | 0 warnings |
| `cd packages && npm run test:run --workspaces` | **135 passed** — 73 in the client (+5), 62 in the CLI (+48) |

`ci.yml`'s `packages` job builds through the root script now, so it can pass for the first time.

---

## 6. Verification (§65)

`CW-1` … `CW-12` are appended to
[`docs/verification/backend-mutation-checks.md`](../verification/backend-mutation-checks.md).
Nothing goes in the browser queue: a CLI is not a browser lane.

**The gap §137 opened is wider, and it is the honest summary of this slice's state:** no `@tcms/cli`
command has ever been run against a real TCMS, and there are now sixteen **write** commands in that
condition. Every test drives a stubbed `fetch`, and a stub that agrees with the contract is the same
shape of observer as the mock layer §107 deleted — it cannot see the server disagreeing with its own
`openapi.yaml`.

One setup closes it for the reads *and* the writes at once: `docker compose up`, `provision-company`,
issue an `Agent` token, export `TCMS_URL` and `TCMS_TOKEN`. After that every command is one
invocation. It needs a person at a terminal only because it needs a provisioned company.
