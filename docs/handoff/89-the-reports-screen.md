# §89 — the Reports screen, and the download an anchor cannot make

**Date:** 2026-08-04 · **Slice:** F1 of `docs/plans/2026-08-04-m2.5-reporting-slice-plan.md` ·
**Three phases, three commits** · **No migration (`V27` still head, `V28` still free), no contract
change, no `gen:api`, no new permission key, no backend file touched at all.**

§87 built the pipeline and §88 gave its output a header; both landed with **nothing that could ask
for a report**. This is the client: `/projects/:projectId/reports`, a generate dialog, a card per
job that polls itself to a terminal state, and a download.

---

## 1. The finding: neither obvious way to download works, and both fail silently

This is the part of the slice that could not be borrowed from a sibling screen, and it is the
reason phase 1 exists as its own commit.

**An `<a href>` cannot authenticate.** `SecurityConfiguration` is `STATELESS` with the session in an
`Authorization` header the client sets explicitly — there is no cookie anywhere, and the class's own
comment says so in the course of explaining why CSRF protection is off. So a plain anchor pointed at
`/api/v1/…` sends no credential and the download answers **401**. It renders as a working link.

**`api.get` cannot take the URL either**, for two independent reasons. `request` prepends `BASE`,
and `ReportJob.downloadUrl` already carries `/api/v1` because the **server** composes it —
deliberately, so the client holds no second copy of the server's routing (`openapi.yaml` on
`downloadUrl`, §67's argument on `Evidence.url`). Passing it through `api.get` produces
`/api/v1/api/v1/…`, a 404 that reads as a missing report. And `request` ends in `res.json()`, which
would parse a Markdown report as JSON and fail at a layer that has nothing to do with the problem.

Hence **`fetchArtifact`** in `api/client.ts`, which takes the server's URL verbatim and returns a
`Blob` plus whatever `Content-Disposition` named, and **`lib/download.ts`** for the DOM half. The
split is not tidiness: the transport half is testable without a document, and the anchor dance lives
in one place rather than being re-derived by the next screen that needs a file.

**The same-origin guard in `fetchArtifact` is not defensive programming.** The URL is data from the
server and this function attaches the caller's bearer token to it, so an absolute URL — a
misconfigured public-url setting, a wrong deployment value — would send that token to another host.
It must be root-relative and inside `BASE`, checked rather than trusted. **It has no test, and the
mutation queue says so as a row rather than implying otherwise.**

### The queue row this obsoletes

§87's browser row 8 reads *"As a Test Lead, follow `downloadUrl` in the address bar → the file
downloads"*. That is false, and it was written before anything had tried it. Restated as **row 22**:
the address bar gives 401, the screen's button gives the file, and running the pair is what makes
the reason visible rather than a claim in a comment.

### The sibling defect, named and not swept

`EvidencePanel` renders `Evidence.url` as a plain `<a href target="_blank">` and has the identical
hole — every evidence download in the product is a 401 in live mode. It is **not fixed here**: the
fix is a different rendering (evidence is served `inline` so a screenshot shows beside the step it
failed on, where a report is `attachment`), and folding it in would make one slice two things. It is
the class this slice's finding belongs to, so it is recorded rather than left for the next reader to
rediscover.

---

## 2. The screen has to remember what it asked for, because nothing else does

The pipeline's whole surface is `POST /reports/generate` and `GET /reports/jobs/{jobId}`. **There is
no operation that lists jobs.** So the set of cards this screen shows is the set *this tab* created,
held in component state, and a reload loses the links — the reports are still generated and their
artifacts still exist, and **nothing in this version can find them again**.

That is a real gap. It is stated on the running card, in the page's header comment, and in browser
row 21, rather than papered over with copy implying otherwise. It is deliberately **not** worked
around by writing job ids into the URL or into `localStorage`: a list endpoint is the fix, it belongs
to the server, and a client-side register of ids is a second, drifting copy of a table that already
exists — §81's finding, where the mock invented a feature the server did not have and it looked
finished for 28 slices.

---

## 3. Two open-set fields, closed for opposite reasons

The dialog's `type` and `format` are both open on the wire and both narrowed here, and the reasons
point in opposite directions — which is why they are worth stating rather than treating as one rule.

**The report type is an open string in the contract** because §6e names ten and a deployment grows
them slice by slice; a client generated from a stale enum could not ask for a report that exists.
What the dialog offers is therefore the set it **can build a parameter form for** — one, today — and
never a claim about what the server can compute. The day R6 lands, the list and the parameter form
move together, which is the only way they can stay consistent.

**The format is offered in full, PDF included.** That looks like `ui-rules`' *"never render an
action the backend will always reject"* and it is the contract's explicit design, verbatim: *"the
enum is not narrowed to what is installed: what a deployment can render is a property of that
deployment, and the refusal names it."* A client-side list of installed renderers would be a second
copy of somebody else's configuration — wrong in **both** directions, offering nothing on a
deployment where PDF has been installed and claiming to know on one where it has not. The refusal is
a **400 at request time carrying a field error**, engineered for exactly this, and it lands on the
format field naming what the server *can* produce. **The deviation is recorded in the code, in a
test, and here**, because "delete the option that always fails" is what a later reader would do.

The **run picker is this report's parameters**, not a general one. `run_summary` takes a required
`runId` validated **inside the worker** — a bad one is a FAILED job carrying a sentence, not a 400 —
so a dialog that let it be omitted buys a job that spins and then fails for a field the form could
have required. A generic form over whatever a future report declares is **not built and named as not
built**: nothing in the contract describes a report's parameters, so there is nothing to generate one
from.

---

## 4. What is gated, and the rail item that is not

**Generate asks `report:export`**, disabled-with-a-reason (§31), never hidden.

**Nothing else is gated**, and the rail item is ungated — a **deliberate departure from the plan's F1
line** (*"Rail item gated on `report:view`"*). `V3` grants `report:view` to five of the six built-in
roles and the sixth is Automation Service, which is API-token only and never renders a rail, so the
gate would hide the entry from **nobody who can reach the page** — while teaching that rail entries
come and go by permission, which is the opposite of what this rail does and of what §31 says. The
`Review` item's own comment already carries the argument: hiding an entry leaves a reader with no way
to find out why they cannot see something.

The item sits under **Execution** rather than Project: what a report is *of* is execution results,
and filing it beside the administration screens is where people would stop looking for it.

The **route is under the project segment**, unlike §83's and §85's two boards — a report is computed
over one project's facts and its own header says *"this report covers X in full and no other
project"*, so switching projects genuinely changes what the screen is about. That is the test the
rail's headings apply, and it lands on the other side here.

---

## 5. The mock gained a failure, and that is what makes FAILED reachable

§88's mock advanced `PENDING → RUNNING → COMPLETED` unconditionally. So the **one job state the plan
names as this screen's characteristic defect** — a poller that treats FAILED as "keep waiting", or
renders it as empty — could not be produced without a backend. A state that only ever appears in
production is one nobody has seen the rendering of.

`advance` now resolves `parameters.runId` against `db.runs` and fails the job with the worker's
sentence when it cannot — **the server's own arrangement**, not an invention: a report's parameters
are worker-validated because checking them on the request path would mean a second copy of every
report's rules. This is the *"build the fault switch before the feature it proves"* rule applied to a
state machine rather than to a fault injector.

The mock's stored model also reads its **real** parameters now, so the header's selection block shows
what was sent (§88's rule: the selection is what the client sent, never what the report understood),
and the rendered title names the run that was actually chosen.

---

## 6. Two tests that had to be repaired before they meant anything

**The poll test was vacuous on its first draft.** It waited 250ms against a 2000ms interval, so a
poller that never stopped would not have fired either — it passed, and proved nothing. It is now a
**pair in one test**: a RUNNING job whose request count must *increase* over one and a half
intervals, and a FAILED one whose count must *not*. The running half proves the clock runs at all;
the failed half proves the check exists. Neither is worth anything alone. §84's pair rule, applied to
a timer.

**The gate test is asserted in both directions** for §66's reason, which has now cost §76 a mutation
that failed to redden and §80 four tests on their first run: `useCan` is fail-closed while pending,
so `toBeDisabled()` for a caller without the key is satisfied by the *loading* state and passes
against a component with **no gate in it at all**. The holder's `toBeEnabled()` is the barrier — it
can only pass once the gate has resolved and granted.

---

## 7. Gate

- `npm run build` — clean, all three phases.
- `npm run lint` — 0 warnings.
- `npm run test:run` — **723/723**, 47 files (705 → 711 → 723). The +6 at phase 1 is **two
  contract-hygiene checks over three new `api.*` call sites**, which is the evidence those checks
  saw the new endpoints; the +12 at phase 3 is this slice's own tests.

**All twelve new tests passed on their first run.** Stated plainly rather than taken as evidence they
can fail — the poll test *had* passed on its first run in a form that could not fail, and it took
reading the interval to notice.

---

## Found and not fixed

- **`EvidencePanel`'s anchor is a 401 in live mode** — §1 above. Same class, different fix, named.
- **`fetchArtifact`'s same-origin guard has no test.** A unit test passing it an absolute URL is the
  smallest thing that closes it; the mutation row says so rather than implying coverage.
- **No job list.** §2 above — the screen's largest limitation, and a server-side fix.
- **§86/§87's three open rows are untouched**: the unobservable bytes-first ordering, the two
  unasserted scheduler company loops, and `case_health`'s missing index on `failing_repeatedly`.

---

## Verification status

**Built and unverified.** Per §65 **zero mutations were run inline** and no browser pass was driven.
Queued this slice:

- `docs/verification/frontend-browser-checks.md` — **twelve rows (14–25)**, the first in this file
  that drive a screen. Rows 15, 19 and 22 are the three to run first.
- `docs/verification/backend-mutation-checks.md` — **six frontend rows**. Two of them are recorded
  as reddening **nothing**, which is the finding rather than a shortfall.

---

## Named next

- **F2 — the on-screen report view**, now the natural pair: this screen has the job, the model
  artifact and its `{ header, report }` shape, and F2's job is to render it rather than compose it.
  It also has an obvious home — a card that is `Ready` should be openable, not only downloadable.
- **R5 — saved report definitions get a server.** The panel and its empty state are already built
  against the shape; what is missing is the writer, and the "Save this report" control that empty
  state promises nothing about today.
- **R6 — the aggregate execution report**, which is where the dialog grows the policy and filter
  fields the plan's F1 line lists and this slice deliberately did not invent.
- **A way to list jobs** — not on the plan, and §2 is the argument for putting it there.
