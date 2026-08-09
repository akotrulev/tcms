# §153 — the seams only a test exercises, and the guard whose test stayed behind

**Date:** 2026-08-09 · **Branch:** `master` · **Scope:** `backend/` only — **no migration, no
contract change, no frontend, no `packages/`.**

A re-derivation, in §28/§29/§46's sense, and the direct continuation of §152. Driven by the
`be-java:be-java` plugin skill.

---

## 1. The question

§152 asked *which repository methods have no caller* and, in answering it, granted a widening it
wrote down at the time: a **test** counts as a caller, because
`AuditEventRepository.findByActionOrderByOccurredAtDesc` is read only by a test asserting what a
write left in the database, the audit trail has no screen yet, and *"a seam whose consumer is a test
is a seam with a consumer."*

That is true of a **read** seam and the slice said so. It is not obviously true of a **write** seam,
and §152 did not ask. So: *which production seams are exercised by nothing but a test?*

## 2. THE FINDING — two, and the widening does not survive the other direction

The rule is `ArchitectureRulesTest.everyPublicBeanMethodIsExercisedInProduction`.

A public method on a Spring bean is something the application offers to **do**. If every caller is a
test, the product has never once done it, and what the test proves is that it **would** work rather
than that it does. That is a different claim from §152's read seam, where the row being read was
written by the product and the test merely observed it.

| | |
|---|---|
| `Outbox.record` | the outbox's **occurrence** arm — §131 built both arms and only `recordCoalesced` has ever been called |
| `ProvisionCompanyCommand.isRequestedBy` | superseded by `OperatorCommands.isRequestedBy`, **and its javadoc was rewritten to argue for keeping it in the same slice that stopped calling it** |

Two entries, and they resolve in opposite directions — which is the point of measuring rather than
asserting a policy.

## 3. Three narrowings, each measured

Broad, the population is **98** and unshippable, exactly as §152 predicted of its own broad form:
record accessors, entity getters, and every implementation of an interface whose production callers
hold the interface (`ReportParser.parse` has four implementations and no direct caller of any).

1. **Spring beans, not controllers.** A handler's caller is the dispatcher, reflectively.
2. **Public only.** This is what makes the rule work *here*, and it is a property of **this
   codebase** rather than of Java: all four `@Scheduled` methods are package-private, so the
   container-invoked methods are already out. A package-private method only a test calls is a seam
   somebody deliberately opened for the test — `TokenRateLimiter.trackedTokens` exists so §141's
   eviction can be observed at all. `UX-3` in the mutation queue bounds this claim.
3. **Not an override.** The caller of an implementation is whoever holds the interface.

**A self-call counts as a production caller.** `ProvisionCompanyCommand.parse` is reached from
`run()` in its own class, and a method its class uses is exercised whenever that class is.

**The first draft got this backwards and the cost was instructive**: the filter meant to drop
self-calls was a *prefix* match, so `com.tcms.tenancy.OutboxDispatchTest` read as a self-call of
`com.tcms.tenancy.Outbox` — the run reported `parse`, which is live, and **hid `Outbox.record`,
which is the finding**. §152's lesson about a hand-grep that got two of ten wrong in both
directions, reproduced inside the check written to replace hand-greps, one slice later.

## 4. The population is PINNED, not emptied — and why that is not an ignore list

§152 refused an ignore list and was right to: its broad form would have shipped with one longer than
the rule. This rule keeps `UNEXERCISED_BY_DECISION`, one entry, and the two objects are not the
same.

An ignore list says *"these do not count."* This says *"these are all of them, and here is the
argument for each"* — and it **fails in both directions**. Adding a seam nothing exercises is red;
**deleting one whose argument is written down is also red**, so the argument cannot outlive the code
it argues about. That is the exact failure §152 found in `SuiteRepository.countLiveCasesBySuite` and
that this slice found again in `ProvisionCompanyCommand.isRequestedBy`, and it is the one an ignore
list structurally cannot catch.

## 5. `Outbox.record` — kept, and the reason is a trade rather than a preference

Decided 2026-08-09, at the user's direction, against §121's and §152's delete-it precedent.

Deleting it would make `outbox.aggregate_type`, `aggregate_id` and `payload` **write-constant** —
`"company"`, `NULL`, `"{}"` — which is precisely the writerless-column shape §116, §117, §132 and
§151 each found and each treated as a defect. **The trade is one recorded defect shape for the
other**, and the columns are `V1`'s rather than this method's. The one candidate producer §131
examined, the invitation email, it refused on grounds that do not expire: only a digest of the token
is stored, so a payload carrying the accept URL would put a live credential at rest.

What is now written at the site, and was not before:

- **Nothing in the product calls it**, and the serialization, the aggregate naming and a handler
  receiving a non-empty payload have **never run outside a test**.
- **`OutboxMessage.payload`'s honesty mechanism does not exist.** It said the producer's record and
  the consumer's *"are two declarations of one shape, and the thing that keeps them honest is a test
  on each side rather than the compiler."* There is no consumer, so there is no such pair — the
  sentence is conditional now, and building the second half is part of what the first occurrence
  event owes rather than something it inherits.
- **`OutboxEvents`' naming convention has one instance and it is the coalesced half.** The
  past-tense/`record` rule it states at length has never discriminated anything; `case.deleted` is
  an example in a comment, not an entry.

## 6. The second finding — the guard moved and the test stayed behind

`ProvisionCompanyCommand.isRequestedBy` answered *"do these arguments invoke **this** command"*.
`TcmsApplication` stopped asking it when `OperatorCommands` took the question over for every verb —
and the method's header was rewritten **in that slice** to argue for keeping it: *"the narrower
question, and its test pins it."* Nothing asked the narrower question. Neither sibling command has
such a method, so it was not even a convention.

**What it cost is the part worth recording.** That test — six assertions, with a careful comment
about a normal server start not being switched to non-web, and a prefix-match case so a future
`provision-company-dry-run` could not silently provision — **was the only test the non-web decision
had.** It was pointed at the dead copy. The live guard `TcmsApplication` calls had **none**, and the
two verbs added since (`issue-invitation`, `grant-instance-admin`) had never been asserted to switch
the jar to non-web at all. The failure that guard prevents is the one `OperatorCommands`' own header
describes: the command does its work correctly and then **never exits**, so an operator sees a
success that hangs.

The method is deleted. `OperatorCommandsTest` is the test now — five tests in two `@Nested` groups,
**derived from `OperatorCommands.ALL`** rather than listing the verbs, so a fourth verb is covered on
the day it is added. `ALL` is package-private for that, with the reason at the field: a test that
lists the verbs is a test that is silent about the fourth one, which is the defect the set replaced.

## 7. What is NOT here

- **No deletion of `Outbox.record`** — §5, decided.
- **No annotation-based exclusion mechanism.** The `public` narrowing carries the container-invoked
  methods today because this codebase happens to keep them package-private. `UX-3` records what to
  do if that stops being true; building it now would be scaffolding for a case that does not exist.
- **No sweep of non-bean production types.** The broad population's 98 members are dominated by
  accessors and interface implementations, and separating a meaningful finding from them needs a
  different question rather than a longer ignore list.
- **`PermissionCatalogue.contains` and `ProvisionCompanyCommand.parse` are NOT findings** — both
  have in-class production callers, and both were false positives of the first draft.
- **`ArchitectureRulesTest`'s own class javadoc said "four properties"** while the class holds
  thirteen rules. Corrected, and it now says the rules below are the list rather than restating a
  count that has been wrong before — §147's stale-failure-message shape, in the file whose whole job
  is checking claims.
- **Nothing on a screen or a wire differs**, so there is no browser-queue row and no contract change.

## 8. Gates

`cd backend && ./gradlew build` — **SUCCESSFUL in 8m59s, 2407 tests across 501 result classes, 0
failures** (§152's run: 2402 / 499). +5 tests, +2 result classes: `OperatorCommandsTest`'s two
`@Nested` groups report separately, one architecture rule was added, and one test was deleted with
the method it pinned. `frontend/` and `packages/` were not rebuilt — neither was touched.

The rule was verified red on the pre-fix tree, naming `ProvisionCompanyCommand.isRequestedBy` and
nothing else. Rows `UX-1`…`UX-4` in `docs/verification/backend-mutation-checks.md`; `UX-1` is marked
RUN.
