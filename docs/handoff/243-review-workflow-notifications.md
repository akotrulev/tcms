# §243 — Review-workflow notifications

**Slice 2 of `docs/plans/2026-08-18-unplanned-gaps.md`**, stepped first into
`docs/plans/2026-08-18-gap-2-review-notifications.md` as that master plan requires for every slice
after the first.

**No migration. No permission seeded or moved. Catalogue unchanged at 55.** Four new event names,
one new port with three implementations, three payload pairs, four handlers, two label maps.

> **Gated on `shanks`.** Backend `./gradlew build` — **3128 tests, BUILD SUCCESSFUL**. Frontend
> **1602 tests / 120 files**, `npm run build` clean, `npm run lint` **0 warnings**. Packages
> **336** across the three workspaces, `npm run build` clean. Both `schema` copies regenerated and
> identical.

---

## What shipped

`execution.run_completed` and `work.item_assigned` had been the whole deliverable vocabulary since
§218. Four names join them, all produced by `project.ReviewService` inside the transactions that
already write the rows they are about:

| Name | Recorded by | Occurrence |
|---|---|---|
| `review.requested` | `ReviewService.addAssignee` | Somebody was asked — **only when a row was actually written** |
| `review.endorsed` | `ReviewService.apply`, `LGTM` | A peer said it looks good |
| `review.changes_requested` | `ReviewService.apply`, `CHANGES_REQUESTED` | A peer asked for changes |
| `review.approved` | `ReviewService.moved`, the `APPROVED` arm | The sign-off — case, suite or plan alike |

The SPA needed **no component change**: the channel dialog's checkbox list is derived from
`notificationEventTypeLabels`, so the work was two label maps and the four help strings, and the
`satisfies Record<NotificationEventType, string>` on both is what turned the contract change into
seven compile errors rather than four blank labels.

---

## Two of the master plan's four names are different, and the reasons are the slice

The master plan named *review-requested, review-approved, changes-requested, case-approved*. Both
substitutions were put to the owner before execution and both were confirmed.

### `review.endorsed`, because an LGTM is not an approval

`ReviewService.record`'s header already refuses to write an `APPROVAL` participation from the
verdict endpoint — *"a participation endpoint that accepted `APPROVED` as a verdict would be a
second, cheaper door to the same sign-off"* — and `ReviewSubjectType.reviewPermission` makes the
same distinction at the permission: recording a peer verdict costs `*:review`, signing off costs
`*:approve`. An event named `review.approved` fired by an advisory LGTM puts that conflation **on
the wire**, where a subscriber acts on it and where no code review will ever look at it again.

`ReviewRenderingTest.theEndorsementIsNotASignOff` asserts the rendered title contains no form of
"approv", which is the one string a phone actually shows.

### `review.approved` covers all three subject types, not just cases

`repository.CaseStatusService:222`, `repository.SuiteStatusService:227`,
`repository.CaseBulkService:624` and `execution.PlanStatusService:200` all call one method —
`ReviewService.moved`. Producing the sign-off there means one constant, one payload and one handler
instead of three, and it means the vocabulary makes no claim that is false by omission: approving a
suite is the same act to a subscriber as approving a case, and the payload says which it was.

### All four are silent where the review workflow is off

§6g's opt-in is per project and per artifact type. With it off no review is opened, `moved` returns
early, and **nothing at all is recorded** — asked-about, judged and signed-off are all facts about a
*review*. `ReviewNotificationTest.aProjectWithoutReviewIsSilent` pins it, and the contract's own
description says so, because an administrator debugging a silent channel has no other way to find
out.

---

## The port the master plan did not scope, and why the slice needed it

A `Review` names its subject as `(type, id)` and nothing else. `ReviewView`'s header says resolving
it in the API would be wrong, and gives the reason: *"the client asked about a subject it already
has open"*.

**A notification reverses that reasoning exactly.** Its reader is in a chat room with no artifact
open, and *"you were asked to review a CASE"* plus a UUID cannot lead anybody to the thing they were
asked to read — which is the entire content of the message. And neither end can resolve it:
`project` declares `{api, tenancy}`, `integration` declares `{api, tenancy, project}`, so a case, a
suite and a plan are out of reach from **both**.

So `project.ReviewSubjectDirectory` is `PeopleDirectory`'s shape for a fourth question — interface
in `project`, one bean per subject type in the module that owns the table
(`repository.CaseReviewSubjects`, `repository.SuiteReviewSubjects`, `execution.PlanReviewSubjects`),
indexed in `integration.ReviewSubjects`. `project/package-info.java` prescribes it in as many words:
*"the fix is a port, never a wider `allowedDependencies`"*.

**The index is computed, and that is the point.** `ReviewSubjectDirectoryTest.theIndexIsTotal`
compares the beans' claimed types against `EnumSet.allOf(ReviewSubjectType.class)`. A fourth member
is added by somebody working on reviews, in a module that cannot see any of the three
implementations — nothing about that edit fails to compile. This reddens at the moment it is added,
naming the type. Verified by breaking it: `PlanReviewSubjects` claiming `CASE` reddens both arms.

**All three subjects have a key and their own words** — `test_case.key`/`title`, `suite.key`/`name`,
`test_plan.key`/`name` — so `Subject(key, title)` is uniform and never partial. The key is
`updatable = false` on all three; the title is not, which is why the pair is resolved **at delivery**
and never carried in the payload (`NotificationProjects`' rule, and it binds harder here).

---

## THE FINDING: a test that counted deliveries could not see which verdict it was

`ReviewNotificationTest.theVerdictsAreSeparatelySubscribable` was written to prove the two peer
verdicts are two event types. It subscribed a channel to `review.changes_requested`, recorded an
LGTM, asserted no `review.endorsed` delivery, then recorded a changes-requested and asserted one
`review.changes_requested` delivery.

**Swapping the two arms of the switch in `ReviewService.apply` left it green.** Under the swap the
LGTM records `changes_requested`, the channel *is* subscribed to that, and one delivery lands; the
first assertion — no `endorsed` delivery — is then true for the wrong reason, and the second finds
the delivery it wanted, from the wrong verdict. The totals are identical and the mapping is
inverted.

This is §241's finding in a new place: **a check written about the total passes straight through a
defect in which one it was.** What reddens is asserting the *occurrence* in both directions —
`occurrences(REVIEW_ENDORSED) == 1` **and** `occurrences(REVIEW_CHANGES_REQUESTED) == 0` — before
looking at any delivery at all. Measured: the swap was applied, the test stayed green, the two lines
were added, the swap was re-applied and exactly one arm reddened, and the revert is green.

---

## The second hole, and the class written for it

Three tests existed and none of them could see the handler's wiring:

| Test | Sees |
|---|---|
| `ReviewPayloadTest` | the document parses |
| `ReviewRenderingTest` | given a subject and two names, the copy is right — it **hands them in** |
| `ReviewNotificationTest` | rows in `outbox` and `notification_delivery` — its webhook is unreachable **by design**, so no rendered message reaches anywhere a test can read |

So a notifier that put the **team** id through the *people* directory, or ignored `ReviewSubjects`
and rendered a null subject, would be green in all three — and both are one-line mistakes that ship
a message naming nobody and nothing. `ReviewNotifierTest` is the answer: Mockito over this package's
own collaborators, capturing what reaches `NotificationDispatchService`. Both mutations were applied
and both redden it.

That leaves one real gap, recorded rather than closed: **no test in this repository has ever read a
rendered notification off the wire.** Every delivery in the suite posts to `127.0.0.1:1`. Row
`§243 RN-3` in the browser queue says so and says what it would take.

---

## The hazard shipped knowingly: bulk fan-out

`CaseBulkService` moves up to **500** cases in one operation (`openapi.yaml`'s `maxItems`) and calls
`moved` per row. A bulk approval in a review-enabled project therefore records **500 occurrences**
and, per subscribed channel, attempts 500 messages.

Shipped, at the owner's direction, for three reasons that have to be read together:

- Every one of the 500 is **true** — 500 artifacts really were signed off, each named by a different
  subject, so a coalesced form could name only one of them honestly (`Outbox#recordCoalesced`'s
  rule).
- Subscription is **opt-in per channel**, and the checkbox's help text says the number out loud.
- The truthful fix is a **subscriber-side digest**, a mechanism this module does not have. Half-built
  here it would be worse than absent.

**No `bulkOperationId` is carried**, deliberately: `moved` is not told it is inside a bulk operation
by any of its four call sites, so the field would be null on every row the product can produce —
`ItemAssignedPayload`'s writerless shape.

`§243 RN-M7` in the mutation queue is marked **expected GREEN** and is the row to run before anybody
subscribes a production channel: it turns "up to 500" from an argument into a measurement.

---

## Smaller things worth carrying

- **`store()` answers instead of returning void.** Re-asking somebody already asked is idempotent —
  §79's rule, so `assignedBy` is not handed to whoever clicked last — and an event on that path
  would announce a second time that a colleague had been asked once. At-least-once delivery means
  the channel sees both. `ReviewEndpointTest.reAskingAnnouncesNothing` pins it.
- **`ReviewVerdictEvent` is one record for two names, with no verdict field.** The name *is* the
  verdict; a field beside it is the same fact twice, and the copy in the payload is the one that can
  disagree.
- **`ReviewSubjectType` crosses the boundary as an enum, and that is not plan §3's forbidden
  sharing.** The rule is about the *payload* class. A subject type is a vocabulary `integration`
  already depends on and is the key its index is built on; reading it as a `String` and parsing it
  would be the same coupling with the compiler switched off.
- **`ReviewService` is the first foundation module to record an outbox occurrence.** `tenancy`
  holding names and not meanings is untouched, but "who could have written this row" is no longer
  answerable by module layer alone. It stays answerable by grep, which is what
  `OutboxHandler#eventType()` asks for. Noted in `OutboxEvents`' header before a fifth arrives.
- **`ProjectSettings`' mutators are package-private**, so the end-to-end test configures the project
  through `ProjectSettingsService` — which is the better fixture anyway, because it is what an
  administrator does.
- **The contract carried a false sentence** and it is corrected in this slice's first commit:
  `NotificationEventType` said `work.item_assigned` *"carries the item and both assignees"*. There is
  one assignee, and `ItemAssignedPayload`'s header records at length why there is no previous one.

---

## Owed

- **A digest for `review.approved`**, or any other subscriber-side answer to the bulk fan-out. Its
  own slice; the constant and the help text both name it as absent.
- **A rejection arm** in `ReviewNotificationTest` — `§243 RN-M3`. Moving the `outbox.record` call out
  of the `APPROVED` branch would announce a sign-off for an artifact that was sent back, and nothing
  currently reddens. One arm closes it, the shape `onlyCompletionIsAnOccurrence` already has for
  runs.
- **A version-drift arm** — `§243 RN-M4`. The approval fixture never edits between submission and
  sign-off, so the submitted version and the current one are equal there and the assertion is weak
  by construction.
- **A team request driven end to end.** `ReviewNotifierTest` covers the wiring with mocks and
  `ReviewEndpointTest` covers the payload; nothing drives one through a real dispatch.

**Built and unverified** — `RN` rows in both queues (`docs/verification/backend-mutation-checks.md`
and `docs/verification/frontend-browser-checks.md`), none run, per §65.
