# §125 — `storage` becomes a module, and the foundation becomes four

**Date:** 2026-08-07 · **Slice:** M3 slice 9 · decision round 3's **step 5 (#51)** · **two phases,
both shipped** · commits `c96bea3`, `de9150b`

Decision round 3 fixed an order and sequenced it on *what each step lets the next one observe*. Step
5 is the architectural move, taken **alone**: `Attachment`, `AttachmentRepository`, `EvidenceStore`,
`StoredBlob`, `ReportStore` and both filesystem adapters leave `execution` and `reporting` for a new
`com.tcms.storage`.

The row in the plan said this would cost "§3's module list amended and `ModuleStructureTest`'s
hard-coded list **and** its cross-module FK rule changed". It cost exactly that, and two things it
did not say.

---

## 1. The condition was written down years before it was met, and it held

`Attachment`'s javadoc did not merely note that a `storage` module would be tidier. It named the
**exact condition** for doing it and refused until then:

> A `storage` module owning this would be the tidier home. It would also need plan §3 amended and
> two architecture tests changed … to serve exactly **one** caller. So it lives here until there is
> a second caller to design against, which is the point at which the right shape is knowable rather
> than guessed.

Decision **#49** creates that second caller twice over — `CASE`, `DOC_PAGE` and `ARTIFACT` owners get
writers — and #51 is sequenced **before** it deliberately: *in one diff, a module extraction and a
feature are indistinguishable*, and the extraction is the half a reviewer cannot check by reading
the feature.

The same javadoc also named what would make the move cheap when it came:

> What keeps that honest is `forExecution` and `forStepResult`: **no call site ever spells an owner
> type**, so the day this moves, the writers that must move with it are exactly the ones this file
> names.

That prediction held in full. The move was a package line and two modifiers (`static` → `public
static` on the factories, package-private → public on five getters). No caller changed shape; four
files gained an import. **This is the opposite outcome from §121 phase 3**, where a seam written
ahead of its consumer guessed a module boundary wrong and had to be deleted — and the difference is
instructive: that seam guessed at *a caller*, this javadoc committed to *a shape* and left the
caller open.

The owner vocabulary is now `storage`'s. A new owner is a factory in that file, never a string at a
call site, which is what keeps `V1`'s polymorphic table ("six near-identical attachment tables is
the worse trade") readable from one place when #49 adds three of them.

---

## 2. THE COST IS A FOURTH FOUNDATION MODULE — and the rule needed a rule of its own

Plan §3: a cross-module dependency points only at `tenancy`, `identity` or `project`; everything
else crosses as a `CompanyScopedEvent`. `execution` and `reporting` both need **bytes back,
synchronously, inside a request** — a download cannot be an event — so extracting the ports out of
the two feature modules that happened to hold them left no way to keep that list at three.

`ArchitectureRules.FOUNDATION_MODULES` names four now, and `ModuleStructureTest`'s hard-coded list
names eleven. §76 refused a `review` module on the sentence *"a fourth foundation module is a
decision, not an import"*; that sentence is still the rule, and this is the decision it was waiting
for.

**`storage` is the first foundation module that is not a domain concept**, which is why the constant
now carries the three properties that made it defensible rather than merely necessary — a bar the
next proposal should be held to:

1. **It declares `tenancy` alone.** Not `identity`, not `project`. It cannot see a person, a
   permission or a gate, so it can never become a second place authorization is decided — the
   failure both ports' headers already warned about ("an implementation that could be asked *list
   this company's files* would be one refactor away from being the place that decides").
2. **It holds no domain concept.** No run, no case, no report. An `owner_id` is a `UUID` it never
   resolves.
3. **It answers a question the database cannot.** The other three pass that test too. A feature
   module proposing itself as a fifth will not.

### The new rule, and why the declaration was not enough

Modulith enforces that a module stays inside its `allowedDependencies`. It has nothing whatever to
say about that declaration being **widened** — adding `"identity"` to `storage/package-info.java` is
one word, it is *legal* under the foundation rule (`identity` is itself a foundation module), and it
would pass every other test in `ArchitectureRulesTest` and `ModuleStructureTest` alike.

So property 1 above is now an ArchUnit rule over the **code**: no class in `com.tcms.storage` may
depend on anything under `com.tcms` outside `tenancy`. Same shape as the standing `tenancy depends
on nothing` rule and deliberately not the same rule — `tenancy` may reach for nothing at all,
`storage` may reach for `tenancy`, because `attachment` is a company-owned table and carries
`CompanyFilter` like every other one. It fails on the import, which is both earlier and more useful
than failing on the declaration.

### §3 already had a home for this, and it is the option that was refused

The plan's own module list reads `shared — storage, audit, attachments, search`. Bundling would have
been faithful to the document and is worse: **a foundation module every other module may depend on
must not be a grab bag.** Making storage reachable from everywhere in that shape makes audit and
search reachable from everywhere too, which is how a `shared` module becomes the place layering goes
to die. `storage` earns the edge on the three properties above; `audit` and `search` would not. §3
is struck through and corrected rather than quietly re-scoped, and what remains of `shared` stays a
normal module.

---

## 3. Phase 2 — one body, two ports, and the argument that expired

§87 shipped `ReportStore` as a **documented copy** of `EvidenceStore`: same four methods, same
signatures, a `ReportBlob` that was `StoredBlob` under another name, with the reciprocal admission in
both headers. It was §58's E1 — the copy that drifts — and it was accepted on exactly one argument:
`reporting` may not import `execution`, so the type was *unreachable* rather than unnoticed, and a
`shared` module for two interfaces and a record was refused as a fourth foundation module.

**#51 took that decision.** Co-located in one package, the argument is gone, so what was left had to
be decided on its own merits rather than inherited:

- **`ReportBlob` is deleted.** It and `StoredBlob` are bytes and a length. Unlike the ports, a blob
  record has **no wrong one to be handed**, so nothing was keeping them apart but their names.
- **The two ports stay two types**, and this is now a choice. One `BlobStore` with two beans puts
  *"which store am I holding"* in a **qualifier string**, and the failure of getting it wrong is
  evidence written under the reports root: no exception, no failing test, discovered when somebody
  goes looking for the screenshot behind a verdict. Two interfaces make that a compile error.
- **The duplicated *body* is what the move actually removed.** `FilesystemBlobStore` holds the four
  filesystem operations and the traversal guard once; the adapters are four delegating methods and
  their own root. So decision **#55**'s deferred S3 adapter — which that entry corrected to "blocks
  on TWO adapters, not one" — is **one** piece of work with two roots again.
- **What deliberately did not collapse is the kind label.** The two roots are configured separately
  because they have different retention and different loss consequences, so a failure has to name
  which one is full or gone. That is the one distinction a shared body silently turns into "could
  not store bytes", and it has a test of its own.

---

## 4. THE FINDING: the report adapter's traversal guard had never been asserted

`FilesystemEvidenceStoreTest` was **the only test either filesystem adapter had**. Its own comment
explains why it exists — the guarantee lives in how the caller *builds* the key, "and a guarantee
that lives only in the caller is one refactor from being absent, with no test that would notice."

`FilesystemReportStore` carried the identical body and the identical check, written twice, and
nothing had ever asserted that the second copy refuses anything. It shipped in §87, past ~700 tests
at the time and 2024 by now.

It is not a review failure. **The two files were in different packages**, so nothing in the repo
ever put them side by side — which is the same shape as `RollupAgreementTest`'s problem and §100's
rule about a comment asserting a fact about another file. The move is what surfaced it, and one body
is one test away from covering both. Each adapter also gets a delegation test, because "one body" is
a claim about *wiring*: right by inspection, wrong by omission, and a copy-pasted adapter rooted at
the wrong property would pass everything else.

---

## 5. Gates

| Gate | Phase 1 | Phase 2 |
|---|---|---|
| `cd backend && ./gradlew build` | **SUCCESSFUL — 2025 tests / 427 classes / 0 failures** | **SUCCESSFUL — 2028 tests / 428 classes / 0 failures** |

§124's baseline was 2024/427. Phase 1 adds one test (the storage-sink rule) and no class — the moved
adapter test stayed one class. Phase 2 adds three tests and one class.

**No migration, no contract change, no frontend change.** Nothing about the wire, the schema or the
SPA moved: this slice is entirely about which package a type is compiled into and which module may
name it. `V38` is still the Flyway head and `V39` is still the next free number.

**NO RED RUN.** Stated because it is unusual and because it is not a claim of care: a move of this
shape either compiles or does not, and the compiler ran ten times before the suite did. The one
thing that could have been silently wrong — the delegation in phase 2 — is what the two adapter
tests exist for.

---

## 6. Verification

**Built and unverified**, per §65. **13 mutation rows** appended to
`docs/verification/backend-mutation-checks.md` under a §125 heading, **none run**. **No browser
rows**: nothing a user can see changed, and a slice that adds rows to the browser queue for a
package rename would make that queue harder to read for no signal.

---

## 7. What this leaves for step 6

**#49 + #50 — attachment owners — is next and is now unblocked.** `CASE`, `DOC_PAGE` and `ARTIFACT`
get writers; `STEP` is dropped from the `CHECK` constraint, which is the round's **only remaining
migration** in the first six steps and will be `V39`. Three notes it should start from:

- The owner vocabulary is `Attachment`'s factories and nothing else. Three new ones, no strings at
  call sites, or the property this slice was able to rely on stops being true for the next move.
- **The row/bytes ordering is the caller's.** Neither port has an opinion, deliberately — `execution`
  writes rows first and `reporting` writes bytes first, and both arguments are in their own headers.
  A third caller must state which it chose and why rather than copying whichever it read first.
- `attachment` is company-owned and carries `CompanyFilter`; a new owner type does not change that,
  and `SchemaConventionTest` will not notice a writer that forgets to set `company_id` because the
  column has been `NOT NULL` since `V1` and the factory sets it.
