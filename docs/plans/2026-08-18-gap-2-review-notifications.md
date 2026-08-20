# Review-Workflow Notifications Implementation Plan

**Date:** 2026-08-18 · **Written by:** `ep-writing-plans`

> **For agentic workers:** REQUIRED SUB-SKILL: Use `ep-executing-plans` to implement this plan
> task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fire notifications for the review workflow, so that being asked to review something, a
peer's verdict on it, and its sign-off all reach a project's configured channels.

**Architecture:** Four new past-tense occurrence names on `OutboxEvents`, produced by
`project.ReviewService` inside the transactions that already write the review rows, consumed by four
`OutboxHandler`s in `com.tcms.integration`. No schema change and no permission change. The one
genuinely new piece of machinery is a **port** — `project.ReviewSubjectDirectory` — because a review
names its subject as a `(type, id)` pair and `project` cannot read `test_case`, `suite` or
`test_plan`, so without it every message names a UUID and no reader can reach the artifact.

**Tech Stack:** Spring Boot 3.5.6 / Java 21 / Spring Modulith / Jackson · `openapi.yaml` as the
contract, generated into both `schema.d.ts` copies · React 19 / TypeScript for the two label maps.

---

## Slice 2 of `docs/plans/2026-08-18-unplanned-gaps.md`

The master plan scoped this slice; this document is the stepped version it requires before work
starts. **Read the master plan's Slice 2 section first** — it names the two traps, and both are
answered below (idempotency: Task 4/5/6 mint an `eventId` per occurrence; company context: the
handlers re-establish nothing, because `OutboxDispatcher` opens it from the envelope).

### Five deviations from the master plan, and why

The master plan named the four events as *review-requested, review-approved, changes-requested,
case-approved*. Four events ship; two of the names and one of the producers are different, and one
piece of work the master plan did not anticipate is included.

1. **`review.endorsed`, not "review-approved", for a peer's LGTM.** `ReviewService.record`'s header
   is explicit that the participation endpoint **cannot** write an `APPROVAL` row — *"a
   participation endpoint that accepted `APPROVED` as a verdict would be a second, cheaper door to
   the same sign-off"* — and `ReviewSubjectType.reviewPermission`'s header says the same from the
   permission side. An event called `review.approved` fired by an advisory LGTM is exactly that
   conflation, on the wire, where a subscriber would act on it.

2. **`review.approved` is the sign-off, and it covers all three subject types, not just cases.**
   The master plan's "case-approved" would have to be produced in `repository`, and approving a
   suite or a plan is the same act to a subscriber. `ReviewService.moved`'s `APPROVED` arm is the
   one place all three ladders converge — `CaseStatusService:222`, `SuiteStatusService:227`,
   `CaseBulkService:624` and `PlanStatusService:200` all call it — so one producer covers the lot
   and the vocabulary makes no claim that is false by omission.

3. **All four events therefore fire only where a review was open**, which means only in projects
   that have §6g's review workflow switched **on** for that artifact type (`ReviewSettings`
   `reviewEnabledFor`, off by default). Approving a case in a project with review off records
   nothing. That is correct rather than a gap: every one of the four names is a fact about a
   *review*, and a `review.approved` with no review behind it would be a message about something
   that did not happen. It is stated in the contract description so an administrator configuring a
   channel is not left debugging silence.

4. **A `ReviewSubjectDirectory` port is in scope.** The master plan did not name it. It is required
   for the slice's stated goal: `project` declares `allowedDependencies = {"api", "tenancy"}` and
   `integration` declares `{"api", "tenancy", "project"}`, so **neither the producer nor the
   consumer can read a case, a suite or a plan**. Without the port the best message available is
   *"You were asked to review a CASE"* plus a UUID — a notice that cannot lead anybody to the
   artifact, which is the entire point of sending it. The port is `PeopleDirectory`'s shape
   (interface in `project`, implementations above it, called from `integration`) and
   `project/package-info.java` prescribes it in as many words: *"the fix is a port, never a wider
   `allowedDependencies`"*.

5. **`NotificationSubscriptionView` is not extended.** The master plan named it; it is
   `record NotificationSubscriptionView(UUID id, String eventType, int minFailures)` and an event
   type is already a bare string on it. What actually has to grow is
   `NotificationChannelService.DELIVERABLE_EVENTS`, the contract's `NotificationEventType` enum and
   the SPA's two label maps — the SPA's checkbox list is derived from the labels
   (`NOTIFICATION_EVENT_TYPES` in `frontend/src/lib/labels.ts:733`), so no component changes.

### The known hazard this slice ships knowingly: bulk fan-out

`CaseBulkService` can move up to **500** cases in one operation (`openapi.yaml:9518`), and it calls
`ReviewService.moved` per row. A bulk approval of 500 cases in a review-enabled project therefore
records 500 `review.approved` occurrences and, for each subscribed channel, attempts 500 messages.

This is the same property `work.item_assigned` already has (*"fifty tasks assigned in a planning
session are fifty notices"*) with a worse ratio: there, fifty notices came from fifty human acts;
here 500 come from one. It ships anyway, for three reasons stated together so the next reader is not
left to reconstruct them:

- Every one of the 500 is **true** — 500 artifacts really were signed off, and each names a
  different subject, so a coalesced form could name only one of them honestly
  (`Outbox#recordCoalesced`'s rule).
- Subscription is **opt-in per channel**. An operator who does not want per-artifact sign-off
  notices does not subscribe that channel to `review.approved`; the checkbox's help text says so.
- The truthful fix is a **subscriber-side digest**, which is a mechanism this module does not have
  and which is a slice of its own. It is recorded as owed in the handoff rather than half-built
  here.

**No `bulkOperationId` is carried in the payload.** `ReviewService.moved` is not told it is inside a
bulk operation and the four call sites do not pass one, so the field would be null on every row this
product can produce — the writerless shape `ItemAssignedPayload`'s header refuses.

---

## What already exists — do not rebuild it

Read `docs/agent-context/be-java.md` §218 before starting. The seam is proven end to end and the
pieces are:

| Piece | Where | What it does |
|---|---|---|
| `Outbox#record` | `tenancy/Outbox.java:103` | `record(aggregateType, aggregateId, eventType, payload)`. Throws outside a transaction. |
| `OutboxEvents` | `tenancy/OutboxEvents.java` | The whole name vocabulary, as `String` constants. Four names today. |
| `OutboxHandler` | `tenancy/OutboxHandler.java` | One bean per event type. Context is already open; throwing is how a failure is reported. |
| `NotificationDispatchService#dispatch` | `integration/NotificationDispatchService.java:86` | `dispatch(projectId, eventType, eventId, failures, notification)` — fans out to the project's subscribed channels and settles each. |
| `NotificationLedger#beginAttempt` | `integration/NotificationLedger.java` | The idempotency gate, keyed `(companyId, channelId, eventId)`. |
| `NotificationTexts` | `integration/NotificationTexts.java` | The single place a payload becomes words. |
| `RenderedNotification` | `integration/RenderedNotification.java` | `(title, summary, List<Fact>)` — the one model all three transports render. |
| `NotificationProjects#nameOf` | `integration/NotificationProjects.java` | Project name resolved **at delivery**, never carried in a payload. The precedent Task 1 copies. |
| `PeopleDirectory` / `TeamDirectory` | `project/` | Who is this person / what team is this, across the boundary. |

**The three-test pin is the cost of every new event type** (`be-java.md` §218): a producer-side pin,
a consumer-side pin, and `NotificationDeliveryTest`, which is the only one that fails when a field is
renamed on both sides at once. This plan pays it for each of the three payload records.

---

## File structure

**Create — `backend/src/main/java/com/tcms/project/`**

- `ReviewSubjectDirectory.java` — the port. One method, one nested `Subject` record, one
  `subjectType()` so implementations can be indexed.
- `ReviewRequestedEvent.java` — producer half of `review.requested`.
- `ReviewVerdictEvent.java` — producer half of **both** verdict events; the event *name* carries
  the verdict, so the record does not.
- `ReviewApprovedEvent.java` — producer half of `review.approved`.

**Create — `backend/src/main/java/com/tcms/repository/`**

- `CaseReviewSubjects.java` — `ReviewSubjectDirectory` for `CASE`.
- `SuiteReviewSubjects.java` — `ReviewSubjectDirectory` for `SUITE`.

**Create — `backend/src/main/java/com/tcms/execution/`**

- `PlanReviewSubjects.java` — `ReviewSubjectDirectory` for `PLAN`.

**Create — `backend/src/main/java/com/tcms/integration/`**

- `ReviewSubjects.java` — indexes the three implementations by subject type and reads one, in its
  own read-only transaction. `NotificationProjects`' shape.
- `ReviewRequestedPayload.java`, `ReviewVerdictPayload.java`, `ReviewApprovedPayload.java` —
  consumer halves.
- `ReviewNotices.java` — the three resolutions every review notifier needs (project name, subject,
  a person's name), so four handlers do not carry four copies.
- `ReviewRequestedNotifier.java`, `ReviewEndorsedNotifier.java`,
  `ReviewChangesRequestedNotifier.java`, `ReviewApprovedNotifier.java`.

**Modify**

- `backend/src/main/java/com/tcms/tenancy/OutboxEvents.java` — four constants.
- `backend/src/main/java/com/tcms/project/ReviewService.java` — one constructor parameter, three
  recording sites, `store` returns whether it wrote.
- `backend/src/main/java/com/tcms/integration/NotificationTexts.java` — four renderings.
- `backend/src/main/java/com/tcms/integration/NotificationChannelService.java:77-78` —
  `DELIVERABLE_EVENTS`.
- `docs/specs/openapi.yaml` — `NotificationEventType` enum members and description.
- `frontend/src/lib/labels.ts:728-752` — `notificationEventTypeLabels`,
  `notificationEventTypeHelp`.
- `frontend/src/api/schema.d.ts` **and** `packages/client/src/schema.ts` — both regenerated.
- `docs/agent-context/be-java.md`, `docs/agent-context/fe-react.md` — seam entries, in this slice's
  own commits.

**Create — tests**

- `backend/src/test/java/com/tcms/project/ReviewEventPayloadTest.java` — the producer pin for all
  three records.
- `backend/src/test/java/com/tcms/project/ReviewSubjectDirectoryTest.java` — the port, driven
  against real cases, suites and plans.
- `backend/src/test/java/com/tcms/integration/ReviewPayloadTest.java` — the consumer pin for all
  three records.
- `backend/src/test/java/com/tcms/integration/ReviewNotificationTest.java` — the third leg:
  producer → outbox → handler → delivery row, per event.
- `backend/src/test/java/com/tcms/integration/ReviewRenderingTest.java` — what the four messages
  say, including every "the row no longer resolves" arm.
- `frontend/src/lib/labels.test.ts` — extend if it already asserts the maps; otherwise no new file.

---

## Phase 1 — the vocabulary

**One commit.** Contract, constants and the SPA's labels, so that the four names exist on both sides
of the wire before anything records or consumes them. Nothing is produced yet; a channel subscribed
to one of them is silent until Phase 3, which is stated in the commit message.

### Task 1: Add the four names to `OutboxEvents`

**Files:**
- Modify: `backend/src/main/java/com/tcms/tenancy/OutboxEvents.java`

- [ ] **Step 1: Append the four constants**

Add after `ITEM_ASSIGNED`, inside the class:

```java
    /**
     * Somebody was asked to review an artifact — recorded by {@code project.ReviewService} inside
     * the transaction that wrote the {@code review_assignment} row.
     *
     * <p>An occurrence with a payload, for {@link #RUN_COMPLETED}'s reason. <strong>Only a request
     * that was actually written.</strong> Re-asking somebody already asked is idempotent and
     * records nothing: {@code ReviewService#store} returns early, and an event there would announce
     * a second time that a colleague had been asked once.
     *
     * <p>The four {@code review.*} names are all facts about a <em>review</em>, so none of them
     * fires in a project with §6g's review workflow switched off for that artifact type — there is
     * no review, nobody was asked, and there is nothing to have a verdict on.
     */
    public static final String REVIEW_REQUESTED = "review.requested";

    /**
     * A peer said the artifact looks good — {@code project.ReviewService}'s {@code LGTM} verdict.
     *
     * <p><strong>Not an approval, and the name is the whole reason this constant is separate.</strong>
     * {@code ReviewService#record}'s header refuses to write an {@code APPROVAL} participation from
     * the verdict endpoint, because that would be a second, cheaper door to a sign-off gated on
     * {@code *:approve}. A name that called it {@code review.approved} would put that conflation on
     * the wire, where a subscriber acts on it. The sign-off is {@link #REVIEW_APPROVED}.
     */
    public static final String REVIEW_ENDORSED = "review.endorsed";

    /**
     * A peer asked for changes — {@code project.ReviewService}'s {@code CHANGES_REQUESTED} verdict.
     *
     * <p>Its own name rather than a verdict field on one "a peer spoke" event, for
     * {@link #RUN_COMPLETED}'s reason: one name would make every subscriber re-derive which
     * verdicts it cared about, and the two are opposite requests to act.
     *
     * <p>Advisory — it does not move the artifact off {@code IN_REVIEW}
     * ({@code ReviewParticipation.Verdict}).
     */
    public static final String REVIEW_CHANGES_REQUESTED = "review.changes_requested";

    /**
     * A review was signed off — recorded by {@code project.ReviewService#moved} on the arm that
     * closes an open review as {@code APPROVED} and writes the {@code APPROVAL} participation.
     *
     * <p><strong>One name for all three subject types.</strong> The three ladders — a case's, a
     * suite's, a plan's — converge on that one method, and approving a suite is the same act to a
     * subscriber as approving a case; the payload says which. A name per artifact would be three
     * constants, three payloads and three handlers for one fact.
     *
     * <p><strong>A bulk approval records one of these per artifact.</strong> {@code CaseBulkService}
     * moves up to 500 cases in one operation and calls {@code moved} for each, so one act can
     * produce 500 occurrences and, per subscribed channel, 500 messages. Each is true and names a
     * different subject, so coalescing them would be untruthful in exactly the way
     * {@link Outbox#recordCoalesced} describes; a digest belongs on the subscriber's side and does
     * not exist yet.
     */
    public static final String REVIEW_APPROVED = "review.approved";
```

- [ ] **Step 2: Update the class header's count**

Replace the heading `<h2>FOUR NAMES, AND THE PAST-TENSE HALF IS POPULATED — the paragraphs above are
answered</h2>` with:

```
 * <h2>EIGHT NAMES, AND THE PAST-TENSE HALF IS NOW THE MAJORITY — the paragraphs above are
 * answered</h2>
```

and append this paragraph to the end of that section, before the closing `*/`:

```
 * <p><strong>Four more since the review-notifications slice</strong>, all occurrences and all
 * produced by {@code project.ReviewService}: {@link #REVIEW_REQUESTED}, {@link #REVIEW_ENDORSED},
 * {@link #REVIEW_CHANGES_REQUESTED} and {@link #REVIEW_APPROVED}. They are the first entries
 * produced by a <em>foundation</em> module, which is worth noting before a fifth: the rule that
 * {@code tenancy} holds names and not meanings is untouched, but a foundation module recording an
 * occurrence means the event's producer is a module every other module may call, so "who could have
 * written this row" is no longer answerable by module layer alone. It is still answerable by grep,
 * which is the property {@link OutboxHandler#eventType()} asks for.
```

- [ ] **Step 3: Compile**

```bash
~/bin/tcms-remote backend ./gradlew compileJava
```

Expected: `BUILD SUCCESSFUL`.

### Task 2: Add the four members to the contract

**Files:**
- Modify: `docs/specs/openapi.yaml:13634-13656`

- [ ] **Step 1: Rewrite the `NotificationEventType` schema**

Replace the whole schema (description and `enum`) with:

```yaml
    NotificationEventType:
      type: string
      description: |
        What a channel may subscribe to. Every member is a past-tense **occurrence carrying a
        payload**, which is what makes it deliverable at all: a coalesced event stands for every
        change since the last delivery, so a notification whose entire content is the payload
        cannot be sent from one.

        `execution.run_completed` carries the run, its final status and its pass/fail/blocked
        counts — which is what `NotificationSubscription.minFailures` filters on.
        `work.item_assigned` carries the item and its assignee, and has no counts, so a threshold
        set against it is inert rather than silencing. The same is true of all four `review.*`
        members.

        **The four `review.*` members are facts about a review, and a project with the review
        workflow switched off for an artifact type sends none of them.** Review is opt-in per
        project and per artifact type (`ProjectSettings`); with it off, submitting and approving
        write no review row, so there is nothing to have been asked about, to have a verdict on, or
        to sign off. A channel subscribed to them in such a project is silent, and that is the
        setting rather than a fault.

        - `review.requested` — somebody was asked to look at an artifact. Only a request that was
          actually written: re-asking the same person is idempotent and sends nothing.
        - `review.endorsed` — a peer said it looks good (`LGTM`). **Advisory, and deliberately not
          called an approval**: recording a peer verdict costs `*:review`, signing off costs
          `*:approve`, and one name for both would put a cheaper door to a sign-off on the wire.
        - `review.changes_requested` — a peer asked for changes. Its own member rather than a field
          on one "a peer spoke" event, because the two are opposite requests to act and a single
          name would make every subscriber re-derive which one it wanted.
        - `review.approved` — the sign-off, for a case, a suite or a plan alike; the payload says
          which. **A bulk approval sends one per artifact** — up to 500, the bulk cap — because
          each names a different subject and a single event could name only one of them honestly.
          A channel that does not want per-artifact sign-off notices does not subscribe to this.

        **A "run failed" event is deliberately absent**: it is not an event, it is a predicate on
        `execution.run_completed`, and modelling it as one keeps the producing module from having
        to know what any subscriber considers alarming.

        **`defect created` is absent for a different reason** — it has no producer. Defect filing
        is M4 scope behind an issue-tracker port and is unbuilt, and a name nothing records would
        be a subscription somebody configured that can never fire.
      enum:
        - execution.run_completed
        - work.item_assigned
        - review.requested
        - review.endorsed
        - review.changes_requested
        - review.approved
```

- [ ] **Step 2: Correct the stale sentence beside it**

The old description said `work.item_assigned` *"carries the item and both assignees"*. There is one
assignee — `ItemAssignedPayload` has no previous assignee and its header records why. Step 1 already
replaces that sentence; confirm it is gone:

```bash
grep -n "both assignees" docs/specs/openapi.yaml
```

Expected: no output.

### Task 3: Widen the server's deliverable set and its pin

**Files:**
- Modify: `backend/src/main/java/com/tcms/integration/NotificationChannelService.java:77-78`
- Modify: `backend/src/test/java/com/tcms/integration/NotificationEventTypeTest.java:70`

- [ ] **Step 1: Run the pin and watch it fail**

```bash
~/bin/tcms-remote backend ./gradlew test --tests 'com.tcms.integration.NotificationEventTypeTest'
```

Expected: FAIL — `theVocabulariesAgree` reports four CONTRACT-ONLY members.

- [ ] **Step 2: Widen `DELIVERABLE_EVENTS`**

```java
    static final Set<String> DELIVERABLE_EVENTS = Set.of(
            OutboxEvents.RUN_COMPLETED,
            OutboxEvents.ITEM_ASSIGNED,
            OutboxEvents.REVIEW_REQUESTED,
            OutboxEvents.REVIEW_ENDORSED,
            OutboxEvents.REVIEW_CHANGES_REQUESTED,
            OutboxEvents.REVIEW_APPROVED);
```

- [ ] **Step 3: Widen the second arm's recorded set**

In `NotificationEventTypeTest.everyMemberIsAnOutboxName`:

```java
        Set<String> recorded = Set.of(
                OutboxEvents.RUN_COMPLETED,
                OutboxEvents.ITEM_ASSIGNED,
                OutboxEvents.REVIEW_REQUESTED,
                OutboxEvents.REVIEW_ENDORSED,
                OutboxEvents.REVIEW_CHANGES_REQUESTED,
                OutboxEvents.REVIEW_APPROVED);
```

- [ ] **Step 4: Run it green**

```bash
~/bin/tcms-remote backend ./gradlew test --tests 'com.tcms.integration.NotificationEventTypeTest'
```

Expected: PASS, both arms.

### Task 4: Regenerate both schema copies and label the four events

**Files:**
- Modify: `frontend/src/api/schema.d.ts` (generated)
- Modify: `packages/client/src/schema.ts` (generated)
- Modify: `frontend/src/lib/labels.ts:728-752`

- [ ] **Step 1: Regenerate both copies**

```bash
cd frontend && npm run gen:api && cd ../packages/client && npm run gen:api && cd ../..
```

- [ ] **Step 2: Confirm the frontend build now fails on the labels**

```bash
~/bin/tcms-remote frontend npm run build
```

Expected: FAIL — `notificationEventTypeLabels` and `notificationEventTypeHelp` no longer satisfy
`Record<NotificationEventType, string>`, naming the four missing keys. This is the compile-time
check `lib/labels.ts` exists for; seeing it fail is the point of the step.

- [ ] **Step 3: Add the four labels**

In `frontend/src/lib/labels.ts`, extend `notificationEventTypeLabels`:

```ts
export const notificationEventTypeLabels = {
  'execution.run_completed': 'Run completed',
  'work.item_assigned': 'Work assigned to somebody',
  'review.requested': 'Review requested',
  'review.endorsed': 'Peer said it looks good',
  'review.changes_requested': 'Peer asked for changes',
  'review.approved': 'Review approved',
} as const satisfies Record<NotificationEventType, string>;
```

- [ ] **Step 4: Add the four help strings**

Extend `notificationEventTypeHelp`:

```ts
export const notificationEventTypeHelp = {
  'execution.run_completed':
    'Sent when a run reaches Completed — not when one is aborted or reopened. Carries the run’s final status and its pass/fail/blocked counts, which is what the failure threshold reads.',
  'work.item_assigned':
    'Sent when a task is created with somebody on it. Carries no result counts, so a failure threshold set here is inert rather than silencing.',
  'review.requested':
    'Sent when somebody is asked to review a case, a suite or a plan. Asking the same person twice sends nothing. Needs the review workflow switched on for that artifact type in project settings — with it off, no review exists and nothing is sent.',
  'review.endorsed':
    'Sent when a reviewer records LGTM. This is a peer’s opinion and not the sign-off — approving is a separate act with its own permission, and it sends “Review approved”.',
  'review.changes_requested':
    'Sent when a reviewer asks for changes. Advisory: it does not move the artifact out of In review.',
  'review.approved':
    'Sent when a review is signed off, for a case, a suite or a plan alike. A bulk approval sends one message per artifact — up to 500 — so leave this off for a channel that only wants headlines.',
} as const satisfies Record<NotificationEventType, string>;
```

- [ ] **Step 5: Gates**

```bash
~/bin/tcms-remote frontend npm run build
~/bin/tcms-remote frontend npm run lint
~/bin/tcms-remote frontend npm run test:run
~/bin/tcms-remote packages npm run build
~/bin/tcms-remote packages npm run test:run --workspaces
```

Expected: build clean, lint 0 warnings, both suites green. If
`frontend/src/features/settings/NotificationSettingsPage.test.tsx` asserts a checkbox count or the
whole rendered list, extend it to the six event types rather than pinning two — the list is derived
from `NOTIFICATION_EVENT_TYPES`, so a count assertion is a count of the vocabulary.

- [ ] **Step 6: Backend gate**

```bash
~/bin/tcms-remote backend ./gradlew build
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 7: Commit**

```bash
git add docs/specs/openapi.yaml \
        backend/src/main/java/com/tcms/tenancy/OutboxEvents.java \
        backend/src/main/java/com/tcms/integration/NotificationChannelService.java \
        backend/src/test/java/com/tcms/integration/NotificationEventTypeTest.java \
        frontend/src/api/schema.d.ts frontend/src/lib/labels.ts \
        packages/client/src/schema.ts
git commit -m "contract: the four review-workflow notification events

The names, the deliverable set and the SPA's labels. Nothing produces them yet
— a channel subscribed to one is silent until the producers land in the next
commit."
```

---

## Phase 2 — the subject port

**One commit.** The reason a review notification can name the artifact rather than a UUID.

### Task 5: Write the port

**Files:**
- Create: `backend/src/main/java/com/tcms/project/ReviewSubjectDirectory.java`

- [ ] **Step 1: Write the interface**

```java
package com.tcms.project;

import java.util.Optional;
import java.util.UUID;

/**
 * <strong>What artifact is this review about</strong> — asked from this side of the module
 * boundary, and never <em>may somebody see it</em>.
 *
 * <h2>Why this port has to exist at all</h2>
 *
 * <p>A {@link Review} names its subject as a {@code (type, id)} pair and nothing more, which is
 * what {@link ReviewView}'s header calls correct for the API: <em>"the client asked about a subject
 * it already has open"</em>, so resolving it there would mean reading two other modules' tables to
 * render a title the caller is already displaying.
 *
 * <p><strong>A notification is the case that reverses that reasoning.</strong> Its reader is in a
 * chat room with no artifact open, and a notice that says "you were asked to review a CASE" plus a
 * UUID cannot lead anybody to the thing they were asked to read — which is the whole content of the
 * message. So the pair has to be resolvable, and neither end can do it alone: {@code project}
 * declares {@code {api, tenancy}} and {@code integration} declares {@code {api, tenancy, project}},
 * so the case, the suite and the plan are out of reach from both. {@link PeopleDirectory}'s shape,
 * for the third question — interface here, implementations in the modules that own the tables,
 * called from the module that needs the answer.
 *
 * <h2>One bean per subject type, indexed rather than switched</h2>
 *
 * <p>{@link #subjectType()} lets the caller build a map, which is
 * {@code NotificationChannelPort#kind()}'s arrangement and has its property: a fourth subject type
 * added to {@link ReviewSubjectType} with no directory behind it fails where it is missing, once,
 * naming the type — instead of falling through a {@code switch} that compiled because its default
 * arm returned empty.
 *
 * <h2>Resolved at delivery, never carried in the payload</h2>
 *
 * <p>{@code NotificationProjects}' rule, and it binds harder here: a title is edited, so a title
 * frozen into an event is the one the artifact had when somebody was asked, and a retry an hour
 * later would announce a name that is no longer on the screen the reader opens.
 */
public interface ReviewSubjectDirectory {

    /** Which kind of subject this one answers about. Exactly one bean per member. */
    ReviewSubjectType subjectType();

    /**
     * Resolves one subject in one project.
     *
     * <p><strong>Empty is a state real rows are in, not a defence.</strong> A review is history and
     * outlives its subject: a case can be deleted while an approved review of it stands, and every
     * implementation resolves through its module's {@code findLiveInProject}, so a deleted artifact
     * yields nothing rather than a row nobody may act on. The caller says so in words instead of
     * naming an id.
     */
    Optional<Subject> describe(UUID projectId, UUID subjectId);

    /**
     * How an artifact is written down for a reader who is not looking at it.
     *
     * @param key   the display key — {@code CASE-12}, {@code SUITE-3}, {@code PLAN-7}. Every one of
     *              the three subject tables has one and it is {@code updatable = false}, so unlike
     *              the title it is the same handle tomorrow
     * @param title the artifact's own words. A case's {@code title}, a suite's or a plan's
     *              {@code name} — different columns, one question
     */
    record Subject(String key, String title) {
    }
}
```

- [ ] **Step 2: Compile**

```bash
~/bin/tcms-remote backend ./gradlew compileJava
```

Expected: `BUILD SUCCESSFUL`.

### Task 6: Write the three implementations

**Files:**
- Create: `backend/src/main/java/com/tcms/repository/CaseReviewSubjects.java`
- Create: `backend/src/main/java/com/tcms/repository/SuiteReviewSubjects.java`
- Create: `backend/src/main/java/com/tcms/execution/PlanReviewSubjects.java`

- [ ] **Step 1: The case directory**

```java
package com.tcms.repository;

import com.tcms.project.ReviewSubjectDirectory;
import com.tcms.project.ReviewSubjectType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link ReviewSubjectDirectory} for a test case.
 *
 * <p>{@code @Transactional} because the read is RLS-protected and the caller is the outbox
 * dispatcher's thread, which holds no transaction of its own — {@code NotificationProjects}' reason,
 * one module over.
 */
@Component
class CaseReviewSubjects implements ReviewSubjectDirectory {

    private final TestCaseRepository cases;

    CaseReviewSubjects(TestCaseRepository cases) {
        this.cases = cases;
    }

    @Override
    public ReviewSubjectType subjectType() {
        return ReviewSubjectType.CASE;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Subject> describe(UUID projectId, UUID subjectId) {
        return cases.findLiveInProject(projectId, subjectId)
                .map(found -> new Subject(found.getKey(), found.getTitle()));
    }
}
```

- [ ] **Step 2: The suite directory**

```java
package com.tcms.repository;

import com.tcms.project.ReviewSubjectDirectory;
import com.tcms.project.ReviewSubjectType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link ReviewSubjectDirectory} for a suite.
 *
 * <p>A suite's own words live in {@code name} where a case's live in {@code title}. That the port
 * calls both {@code title} is the point of it: three columns, one question.
 */
@Component
class SuiteReviewSubjects implements ReviewSubjectDirectory {

    private final SuiteRepository suites;

    SuiteReviewSubjects(SuiteRepository suites) {
        this.suites = suites;
    }

    @Override
    public ReviewSubjectType subjectType() {
        return ReviewSubjectType.SUITE;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Subject> describe(UUID projectId, UUID subjectId) {
        return suites.findLiveInProject(projectId, subjectId)
                .map(found -> new Subject(found.getKey(), found.getName()));
    }
}
```

- [ ] **Step 3: The plan directory**

```java
package com.tcms.execution;

import com.tcms.project.ReviewSubjectDirectory;
import com.tcms.project.ReviewSubjectType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link ReviewSubjectDirectory} for a test plan — the third and, since {@link ReviewSubjectType}
 * has three members, the last. A fourth member with no bean behind it fails in
 * {@code integration.ReviewSubjects}' index, naming the type.
 */
@Component
class PlanReviewSubjects implements ReviewSubjectDirectory {

    private final TestPlanRepository plans;

    PlanReviewSubjects(TestPlanRepository plans) {
        this.plans = plans;
    }

    @Override
    public ReviewSubjectType subjectType() {
        return ReviewSubjectType.PLAN;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Subject> describe(UUID projectId, UUID subjectId) {
        return plans.findLiveInProject(projectId, subjectId)
                .map(found -> new Subject(found.getKey(), found.getName()));
    }
}
```

- [ ] **Step 4: Confirm the getters exist**

```bash
grep -n "public String getKey()\|public String getTitle()" backend/src/main/java/com/tcms/repository/TestCase.java
grep -n "public String getKey()\|public String getName()" backend/src/main/java/com/tcms/repository/Suite.java
grep -n "public String getKey()\|public String getName()" backend/src/main/java/com/tcms/execution/TestPlan.java
```

Expected: two hits per file. If a getter is absent, add it beside the field rather than reading the
column another way — every one of these entities exposes the rest of its columns that way.

- [ ] **Step 5: Compile**

```bash
~/bin/tcms-remote backend ./gradlew compileJava
```

Expected: `BUILD SUCCESSFUL`.

### Task 7: Write the port's test

**Files:**
- Create: `backend/src/test/java/com/tcms/project/ReviewSubjectDirectoryTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.tcms.project;

import static org.assertj.core.api.Assertions.assertThat;

import com.tcms.IntegrationTestBase;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The port has one bean per {@link ReviewSubjectType}, and each answers about its own module's
 * table.
 *
 * <p><strong>The first arm is the one that matters and it is computed rather than written out.</strong>
 * A fourth subject type is added to the enum by somebody working on reviews, in a module that
 * cannot see any of the three implementations; nothing about that edit fails to compile. This is
 * what fails, at the moment the member is added, naming it.
 */
@DisplayName("The review subject port — every subject type has exactly one directory")
class ReviewSubjectDirectoryTest extends IntegrationTestBase {

    @Autowired
    private List<ReviewSubjectDirectory> directories;

    @Test
    @DisplayName("every subject type has exactly one directory, and no directory is orphaned")
    void theIndexIsTotal() {
        Map<ReviewSubjectType, List<ReviewSubjectDirectory>> byType = directories.stream()
                .collect(Collectors.groupingBy(ReviewSubjectDirectory::subjectType));

        assertThat(byType.keySet())
                .as("""
                        A ReviewSubjectType with no directory behind it is a review notification
                        that can only name a UUID, and nothing about adding the member fails to
                        compile. Add a directory in the module that owns the table.""")
                .isEqualTo(EnumSet.allOf(ReviewSubjectType.class));

        assertThat(byType.values().stream().map(List::size).collect(Collectors.toSet()))
                .as("two beans claiming one subject type is a coin toss over which table is read")
                .isEqualTo(Set.of(1));
    }

    @Test
    @DisplayName("a subject that does not resolve yields nothing rather than an invented row")
    void anUnknownSubjectIsEmpty() {
        Function<ReviewSubjectType, ReviewSubjectDirectory> byType = type -> directories.stream()
                .filter(directory -> directory.subjectType() == type)
                .findFirst()
                .orElseThrow();

        for (ReviewSubjectType type : ReviewSubjectType.values()) {
            Optional<ReviewSubjectDirectory.Subject> found = inCompany(() ->
                    byType.apply(type).describe(UUID.randomUUID(), UUID.randomUUID()));

            assertThat(found)
                    .as("a review outlives its subject — %s must answer 'no such artifact' rather "
                            + "than throwing, because the handler that asks is delivering a "
                            + "notice about something that has since been deleted", type)
                    .isEmpty();
        }
    }
}
```

- [ ] **Step 2: Adapt `inCompany` to whatever `IntegrationTestBase` actually offers**

```bash
grep -n "inCompany\|protected .* seedDefaults\|CompanyContext.open" backend/src/test/java/com/tcms/IntegrationTestBase.java
```

If the base class has no value-returning `inCompany`, open the context directly in the test:

```java
            Optional<ReviewSubjectDirectory.Subject> found;
            try (var ignored = com.tcms.tenancy.CompanyContext.open(DEFAULT_COMPANY)) {
                found = byType.apply(type).describe(UUID.randomUUID(), UUID.randomUUID());
            }
```

using whatever constant the base class seeds. Do not add a helper to the base class for one test.

- [ ] **Step 3: Run it**

```bash
~/bin/tcms-remote backend ./gradlew test --tests 'com.tcms.project.ReviewSubjectDirectoryTest'
```

Expected: PASS, two tests. If `theIndexIsTotal` fails naming a type, a directory is missing.

- [ ] **Step 4: Full backend gate**

```bash
~/bin/tcms-remote backend ./gradlew build
```

Expected: `BUILD SUCCESSFUL`. `ArchitectureRulesTest` is the one to watch: it reads every import, and
`repository`/`execution` importing `com.tcms.project` is already declared by both.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/tcms/project/ReviewSubjectDirectory.java \
        backend/src/main/java/com/tcms/repository/CaseReviewSubjects.java \
        backend/src/main/java/com/tcms/repository/SuiteReviewSubjects.java \
        backend/src/main/java/com/tcms/execution/PlanReviewSubjects.java \
        backend/src/test/java/com/tcms/project/ReviewSubjectDirectoryTest.java
git commit -m "feat: a review's subject, resolvable across the module boundary

PeopleDirectory's shape for the third question. A notification's reader has no
artifact open, so a (type, id) pair is not a message — and neither project nor
integration may read a case, a suite or a plan."
```

---

## Phase 3 — the producers

**One commit.** Three payload records, three recording sites, the producer pin.

### Task 8: Write the three producer records

**Files:**
- Create: `backend/src/main/java/com/tcms/project/ReviewRequestedEvent.java`
- Create: `backend/src/main/java/com/tcms/project/ReviewVerdictEvent.java`
- Create: `backend/src/main/java/com/tcms/project/ReviewApprovedEvent.java`

- [ ] **Step 1: `ReviewRequestedEvent`**

```java
package com.tcms.project;

import java.util.UUID;

/**
 * {@code review.requested}, as this module writes it.
 *
 * <p>The producer half of a pair; {@code com.tcms.integration.ReviewRequestedPayload} is the other.
 * See {@code execution.RunCompletedEvent}'s header for why the two cannot be one type and what
 * keeps them honest.
 *
 * <h2>The assignee is one of two ids and never both</h2>
 *
 * <p>{@code ReviewAssigneeEdit} refuses a request naming neither and a request naming both, so
 * exactly one of {@link #assigneeUserId()} and {@link #assigneeTeamId()} is set on every row. That
 * is a validated invariant rather than a hopeful one, and the consumer's renderer therefore has two
 * reachable arms and no third.
 *
 * <h2>No names, and no subject key</h2>
 *
 * <p>What somebody is called is a display decision plan §6u makes per company membership, and an
 * artifact's title is edited — so both are resolved at delivery, through
 * {@link PeopleDirectory}/{@link TeamDirectory} and {@link ReviewSubjectDirectory}. A name frozen
 * here is the one that was true when the request was written, and a retry an hour later would
 * announce it.
 *
 * @param eventId     minted per occurrence, and the consumer's idempotency key against
 *                    at-least-once delivery. Not the outbox row's id, which no handler is given
 * @param requestedBy who asked. Never null: the request costs {@code *:review}, so it is always an
 *                    authenticated caller
 */
record ReviewRequestedEvent(
        UUID eventId,
        UUID reviewId,
        UUID projectId,
        ReviewSubjectType subjectType,
        UUID subjectId,
        UUID assigneeUserId,
        UUID assigneeTeamId,
        UUID requestedBy) {

    /** The aggregate noun {@code outbox.aggregate_type} carries for every {@code review.*} event. */
    static final String AGGREGATE = "review";
}
```

- [ ] **Step 2: `ReviewVerdictEvent`**

```java
package com.tcms.project;

import java.util.UUID;

/**
 * {@code review.endorsed} and {@code review.changes_requested}, as this module writes them.
 *
 * <p><strong>One record, two event names, and the record carries no verdict field.</strong> The two
 * occurrences differ only in what a peer said, and that is what the name is for: a subscriber picks
 * the one it wants at the subscription and never has to filter. A verdict field beside the name
 * would be the same fact twice, and the copy in the payload is the one that can disagree.
 *
 * <p>The producer half of a pair; {@code com.tcms.integration.ReviewVerdictPayload} is the other.
 *
 * @param reviewerId  the peer who spoke, resolved to a name at delivery
 * @param submittedBy who put the artifact forward — the person a "changes requested" is addressed
 *                    to. <strong>Nullable</strong>: {@code review.created_by} permits null and
 *                    {@code ReviewService#refuses} already reasons about an unattributable review,
 *                    so the renderer says the artifact was sent back without naming a recipient
 * @param comment     the peer's own words, trimmed, or null when they left none. Carried rather
 *                    than resolved, because unlike a name and a title a remark is immutable —
 *                    {@code ReviewParticipation} is append-only — so the value cannot go stale
 */
record ReviewVerdictEvent(
        UUID eventId,
        UUID reviewId,
        UUID projectId,
        ReviewSubjectType subjectType,
        UUID subjectId,
        UUID reviewerId,
        UUID submittedBy,
        String comment) {

    /** The aggregate noun {@code outbox.aggregate_type} carries for every {@code review.*} event. */
    static final String AGGREGATE = "review";
}
```

- [ ] **Step 3: `ReviewApprovedEvent`**

```java
package com.tcms.project;

import java.util.UUID;

/**
 * {@code review.approved}, as this module writes it — the sign-off, for a case, a suite or a plan
 * alike.
 *
 * <p>The producer half of a pair; {@code com.tcms.integration.ReviewApprovedPayload} is the other.
 *
 * <h2>{@link #subjectVersionNo()} is on the payload and nothing else here is derived</h2>
 *
 * <p>{@code Review#subjectVersionNo} is stamped at submission and never re-stamped, so it is the
 * version somebody was actually asked to read. A sign-off notice that named the artifact without it
 * would be read as approving whatever the artifact says now, which is the exact confusion §6g's
 * diff-since-approval rule exists to prevent.
 *
 * <h2>No bulk operation id, because nothing here is told about one</h2>
 *
 * <p>{@code CaseBulkService} approves up to 500 cases in one operation and calls
 * {@code ReviewService#moved} for each, so one act produces up to 500 of these. A
 * {@code bulkOperationId} would let a future digest group them — and {@code moved} is not given one
 * by any of its four call sites, so the field would be null on every row this product can produce,
 * which is the writerless shape {@code ItemAssignedPayload}'s header refuses. It arrives with the
 * digest, or not at all.
 *
 * @param approvedBy  who signed off — the caller of the ladder, holding {@code *:approve}
 * @param submittedBy who had put it forward. Nullable, for {@link ReviewVerdictEvent}'s reason
 */
record ReviewApprovedEvent(
        UUID eventId,
        UUID reviewId,
        UUID projectId,
        ReviewSubjectType subjectType,
        UUID subjectId,
        int subjectVersionNo,
        UUID approvedBy,
        UUID submittedBy) {

    /** The aggregate noun {@code outbox.aggregate_type} carries for every {@code review.*} event. */
    static final String AGGREGATE = "review";
}
```

### Task 9: Record the three occurrences in `ReviewService`

**Files:**
- Modify: `backend/src/main/java/com/tcms/project/ReviewService.java`

- [ ] **Step 1: Take the outbox as a dependency**

Add the import `com.tcms.tenancy.Outbox`, the field, and the constructor parameter (append it after
`Clock clock` so no existing positional call site is disturbed — there are none outside Spring, but
appending is the smaller diff):

```java
    private final Clock clock;
    /**
     * The only way this module tells anybody anything. A notification is a POST to a third party
     * and this method is inside the ladder's transaction — {@code V1}'s comment on {@code outbox}
     * is what forbids the direct call, and {@code integration} may not be reached from here anyway.
     */
    private final Outbox outbox;
```

and in the constructor's parameter list and body:

```java
            ProjectAccess access,
            Clock clock,
            Outbox outbox) {
        ...
        this.clock = clock;
        this.outbox = outbox;
    }
```

- [ ] **Step 2: Make `store` say whether it wrote**

Change the signature and both early returns (`ReviewService.java:770-793`):

```java
    /**
     * Writes the request, unless it is already there.
     *
     * <p>The read is the check and the unique index is the backstop ({@code V20}'s and {@code V21}'s
     * rule). Two people asking the same colleague at the same instant is a real race and the loser
     * hits the index — which is the one case this method cannot make idempotent, and it surfaces as
     * the constraint violation it is rather than as a silent second row.
     *
     * @return whether a row was written. The caller records {@code review.requested} on true and
     *         nothing on false: re-asking somebody already asked is idempotent, and an event there
     *         would announce a second time that a colleague had been asked once
     */
    private boolean store(UUID companyId, UUID actorId, UUID reviewId, ReviewAssigneeEdit edit) {
        Instant now = clock.instant();

        if (edit.userId() != null) {
            if (assignments.findForUser(reviewId, edit.userId()).isPresent()) {
                return false;
            }
            assignments.saveAndFlush(
                    ReviewAssignment.ofUser(companyId, reviewId, edit.userId(), actorId, now));
            return true;
        }

        if (assignments.findForTeam(reviewId, edit.teamId()).isPresent()) {
            return false;
        }
        assignments.saveAndFlush(
                ReviewAssignment.ofTeam(companyId, reviewId, edit.teamId(), actorId, now));
        return true;
    }
```

- [ ] **Step 3: Record `review.requested` in `addAssignee`**

Replace the `Valid` arm of the switch in `addAssignee`:

```java
            case ReviewAssigneeEdit.Outcome.Valid valid -> {
                if (store(companyId, actorId, review.getId(), valid.edit())) {
                    // Inside the write's own transaction: the request and the notice of it commit
                    // together or neither does. Outbox#record throws when called outside one
                    // rather than trusting this comment.
                    outbox.record(
                            ReviewRequestedEvent.AGGREGATE,
                            review.getId(),
                            OutboxEvents.REVIEW_REQUESTED,
                            new ReviewRequestedEvent(
                                    UUID.randomUUID(),
                                    review.getId(),
                                    review.getProjectId(),
                                    review.getSubjectType(),
                                    review.getSubjectId(),
                                    valid.edit().userId(),
                                    valid.edit().teamId(),
                                    actorId));
                }
                yield new Outcome.Recorded(render(companyId, List.of(review)).getFirst());
            }
```

Add `import com.tcms.tenancy.OutboxEvents;`.

- [ ] **Step 4: Record the two verdicts in `apply`**

After the `participations.saveAndFlush(...)` call in `apply`, before the `return`:

```java
        // The event NAME carries the verdict, so a subscriber picks the one it wants at the
        // subscription and never filters. The two binding verdicts cannot arrive here — parseVerdict
        // refuses them as field errors, because approving is the ladder's act — so the switch has
        // no reachable third arm and says so rather than defaulting.
        String eventType = switch (verdict) {
            case LGTM -> OutboxEvents.REVIEW_ENDORSED;
            case CHANGES_REQUESTED -> OutboxEvents.REVIEW_CHANGES_REQUESTED;
            case APPROVED, REJECTED -> throw new IllegalStateException(
                    "A binding verdict reached the participation endpoint: " + verdict
                            + " — parseVerdict is what refuses these, and it did not");
        };
        outbox.record(
                ReviewVerdictEvent.AGGREGATE,
                review.getId(),
                eventType,
                new ReviewVerdictEvent(
                        UUID.randomUUID(),
                        review.getId(),
                        review.getProjectId(),
                        review.getSubjectType(),
                        review.getSubjectId(),
                        userId,
                        review.getCreatedBy(),
                        comment == null || comment.isBlank() ? null : comment.trim()));
```

- [ ] **Step 5: Record the sign-off in `moved`**

In the `to == ReviewStanding.APPROVED` arm, after `participations.save(...)`:

```java
            outbox.record(
                    ReviewApprovedEvent.AGGREGATE,
                    review.getId(),
                    OutboxEvents.REVIEW_APPROVED,
                    new ReviewApprovedEvent(
                            UUID.randomUUID(),
                            review.getId(),
                            projectId,
                            subjectType,
                            subjectId,
                            review.getSubjectVersionNo(),
                            actorId,
                            review.getCreatedBy()));
```

- [ ] **Step 6: Say in the class header that this module now records events**

Append to `ReviewService`'s javadoc, after the "The ladder tells this class where the artifact
landed" section:

```
 * <h2>This class records outbox occurrences, and it is the first foundation module to</h2>
 *
 * <p>Four names — {@code review.requested}, {@code review.endorsed},
 * {@code review.changes_requested} and {@code review.approved} — each recorded inside the
 * transaction that wrote the row it is about, so the fact and the notice of it commit together.
 * {@code com.tcms.integration} consumes them; this module may not name it and does not need to.
 *
 * <p><strong>Nothing is recorded where no review exists.</strong> §6g's opt-in means a project with
 * review switched off for an artifact type opens no review, so {@link #moved} returns early and
 * there is nothing to be asked about, to judge or to sign off. A notification vocabulary that fired
 * anyway would be announcing reviews that did not happen.
 *
 * <p><strong>{@link #moved} records one occurrence per artifact, including inside a bulk
 * operation.</strong> {@code CaseBulkService} calls it up to 500 times in one transaction and each
 * call names a different subject; see {@code OutboxEvents#REVIEW_APPROVED} for why they are not
 * coalesced and what the honest fix is.
```

- [ ] **Step 7: Compile**

```bash
~/bin/tcms-remote backend ./gradlew compileJava
```

Expected: `BUILD SUCCESSFUL`. A failure naming `ReviewService`'s constructor means a test builds it
by hand; there are none today (`grep -rn "new ReviewService(" backend/src` is empty), so a hit is
new code somebody else added — wire the outbox through rather than reordering parameters.

### Task 10: Write the producer pin

**Files:**
- Create: `backend/src/test/java/com/tcms/project/ReviewEventPayloadTest.java`

- [ ] **Step 1: Write the test**

```java
package com.tcms.project;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

/**
 * <strong>The producer half of the three review payload pins.</strong>
 *
 * <p>{@code com.tcms.integration.ReviewPayloadTest} is the other, and
 * {@code com.tcms.integration.ReviewNotificationTest} is the third leg — the only one that fails
 * when a field is renamed on both sides at once. {@code execution.RunCompletedPayloadTest}'s header
 * carries the argument for the arrangement.
 *
 * <p>No database and no context: the subject is a serialization. Whether {@code ReviewService}
 * calls it, in the right transaction, on the right verdict, is answered where that is driven for
 * real.
 *
 * <p>Ids are unique to this class — {@code b7…}.
 */
@DisplayName("review.* — the shapes this module writes (the producer pin)")
class ReviewEventPayloadTest {

    private static final UUID EVENT = UUID.fromString("b7000000-0000-4000-8000-000000000001");
    private static final UUID REVIEW = UUID.fromString("b7000000-0000-4000-8000-000000000002");
    private static final UUID PROJECT = UUID.fromString("b7000000-0000-4000-8000-000000000003");
    private static final UUID SUBJECT = UUID.fromString("b7000000-0000-4000-8000-000000000004");
    private static final UUID PERSON = UUID.fromString("b7000000-0000-4000-8000-000000000005");
    private static final UUID OTHER = UUID.fromString("b7000000-0000-4000-8000-000000000006");
    private static final UUID TEAM = UUID.fromString("b7000000-0000-4000-8000-000000000007");

    /** Built as Boot builds the injected one — {@code Outbox} serializes with that mapper. */
    private final ObjectMapper json = Jackson2ObjectMapperBuilder.json().build();

    @Test
    @DisplayName("review.requested carries exactly these fields, with the subject type as its name")
    void theRequestIsPinned() {
        JsonNode document = json.valueToTree(new ReviewRequestedEvent(
                EVENT, REVIEW, PROJECT, ReviewSubjectType.CASE, SUBJECT, PERSON, null, OTHER));

        assertThat(fieldNames(document))
                .as("outbox.payload is a wire format: rows written before a rename are still in "
                        + "the table when the consumer that expects the new name deploys")
                .containsExactlyInAnyOrder("eventId", "reviewId", "projectId", "subjectType",
                        "subjectId", "assigneeUserId", "assigneeTeamId", "requestedBy");
        assertThat(document.get("subjectType").asText())
                .as("the enum crosses as its name — a consumer indexes its subject directories on "
                        + "it, so the spelling is load-bearing")
                .isEqualTo("CASE");
        assertThat(document.get("assigneeUserId").asText()).isEqualTo(PERSON.toString());
        assertThat(document.get("assigneeTeamId").isNull())
                .as("exactly one of the two is set — ReviewAssigneeEdit refuses both and neither")
                .isTrue();
        assertThat(document.get("requestedBy").asText()).isEqualTo(OTHER.toString());
    }

    @Test
    @DisplayName("review verdicts carry no verdict field — the event name is the verdict")
    void theVerdictIsPinned() {
        JsonNode document = json.valueToTree(new ReviewVerdictEvent(
                EVENT, REVIEW, PROJECT, ReviewSubjectType.SUITE, SUBJECT, PERSON, OTHER,
                "The third step does not say what to assert"));

        assertThat(fieldNames(document))
                .as("a verdict field beside the event name would be the same fact twice, and the "
                        + "copy in the payload is the one that can disagree with the name")
                .containsExactlyInAnyOrder("eventId", "reviewId", "projectId", "subjectType",
                        "subjectId", "reviewerId", "submittedBy", "comment");
        assertThat(document.get("comment").asText())
                .isEqualTo("The third step does not say what to assert");
        assertThat(document.get("submittedBy").asText()).isEqualTo(OTHER.toString());
    }

    @Test
    @DisplayName("an unattributable review serializes a null submitter rather than dropping it")
    void theSubmitterMayBeAbsent() {
        JsonNode document = json.valueToTree(new ReviewVerdictEvent(
                EVENT, REVIEW, PROJECT, ReviewSubjectType.CASE, SUBJECT, PERSON, null, null));

        assertThat(document.get("submittedBy").isNull())
                .as("review.created_by permits null and ReviewService#refuses already reasons "
                        + "about it — the consumer must be able to tell 'nobody to address' from "
                        + "'field not sent'")
                .isTrue();
        assertThat(document.get("comment").isNull()).isTrue();
    }

    @Test
    @DisplayName("review.approved carries the version somebody was asked to read")
    void theApprovalIsPinned() {
        JsonNode document = json.valueToTree(new ReviewApprovedEvent(
                EVENT, REVIEW, PROJECT, ReviewSubjectType.PLAN, SUBJECT, 3, PERSON, OTHER));

        assertThat(fieldNames(document))
                .containsExactlyInAnyOrder("eventId", "reviewId", "projectId", "subjectType",
                        "subjectId", "subjectVersionNo", "approvedBy", "submittedBy");
        assertThat(document.get("subjectVersionNo").asInt())
                .as("stamped at submission and never re-stamped, so a sign-off notice names the "
                        + "version that was read rather than whatever the artifact says now")
                .isEqualTo(3);
        assertThat(document.get("approvedBy").asText()).isEqualTo(PERSON.toString());
    }

    @Test
    @DisplayName("nothing in any of the three documents could be a credential")
    void thePayloadsCarryNoSecret() throws Exception {
        // OutboxEntry#getPayload's rule, checked rather than trusted: these are rows at rest that
        // an operator browses and a backup copies. The payload names the event; the handler looks
        // the channel — and its webhook URL — up.
        List<Object> events = List.of(
                new ReviewRequestedEvent(EVENT, REVIEW, PROJECT, ReviewSubjectType.CASE, SUBJECT,
                        PERSON, null, OTHER),
                new ReviewVerdictEvent(EVENT, REVIEW, PROJECT, ReviewSubjectType.CASE, SUBJECT,
                        PERSON, OTHER, "no"),
                new ReviewApprovedEvent(EVENT, REVIEW, PROJECT, ReviewSubjectType.CASE, SUBJECT, 1,
                        PERSON, OTHER));

        for (Object event : events) {
            assertThat(json.writeValueAsString(event).toLowerCase(java.util.Locale.ROOT))
                    .doesNotContain("secret")
                    .doesNotContain("token")
                    .doesNotContain("http");
        }
    }

    private static List<String> fieldNames(JsonNode document) {
        List<String> names = new ArrayList<>();
        for (Iterator<String> fields = document.fieldNames(); fields.hasNext(); ) {
            names.add(fields.next());
        }
        return names;
    }

    /** The unused team id keeps the both-arms shape visible; assert it so `-Werror` stays quiet. */
    @Test
    @DisplayName("a team request serializes the team id and a null user id")
    void aTeamRequestIsTheOtherArm() {
        JsonNode document = json.valueToTree(new ReviewRequestedEvent(
                EVENT, REVIEW, PROJECT, ReviewSubjectType.CASE, SUBJECT, null, TEAM, OTHER));

        assertThat(document.get("assigneeUserId").isNull()).isTrue();
        assertThat(document.get("assigneeTeamId").asText()).isEqualTo(TEAM.toString());
    }
}
```

- [ ] **Step 2: Run it**

```bash
~/bin/tcms-remote backend ./gradlew test --tests 'com.tcms.project.ReviewEventPayloadTest'
```

Expected: PASS, six tests.

- [ ] **Step 3: Prove `review.requested` is not recorded twice for one person**

Add to the existing `backend/src/test/java/com/tcms/project/ReviewEndpointTest.java` — it already
drives `assign` end to end, so this arm belongs beside its idempotency test rather than in a new
class:

```java
    @Test
    @DisplayName("re-asking the same reviewer records no second occurrence")
    void reAskingAnnouncesNothing() throws SQLException {
        UUID reviewId = openAReview();

        inCompany(() -> {
            reviews.assign(LEAD, PROJECT, reviewId, new ReviewAssignmentRequest(PEER, null));
            reviews.assign(LEAD, PROJECT, reviewId, new ReviewAssignmentRequest(PEER, null));
        });

        assertThat(count("SELECT COUNT(*) FROM outbox WHERE event_type = 'review.requested'"))
                .as("the second call writes no assignment row — an event there would announce a "
                        + "second time that a colleague had been asked once")
                .isOne();
    }
```

Adapt the helper names (`openAReview`, `count`, `inCompany`, the id constants) to whatever that class
already uses; read it before writing this, and reuse its fixtures rather than seeding new ones.

- [ ] **Step 4: Run the review suite**

```bash
~/bin/tcms-remote backend ./gradlew test --tests 'com.tcms.project.ReviewEndpointTest'
```

Expected: PASS, including the new arm.

- [ ] **Step 5: Full backend gate**

```bash
~/bin/tcms-remote backend ./gradlew build
```

Expected: `BUILD SUCCESSFUL`. **Watch for `OutboxDispatchTest` or any test asserting `outbox` is
empty after a review write** — those are the tests this task can redden legitimately, and the fix is
to widen the assertion, never to stop recording.

**Every event recorded here has no handler until Phase 4.** `OutboxHandler`'s header says zero
handlers is a `DEAD` row, so any test that dispatches after a review write will see one. Do not
"fix" that by deleting the recording; Phase 4 is the fix, and it is the next commit.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/tcms/project/ReviewRequestedEvent.java \
        backend/src/main/java/com/tcms/project/ReviewVerdictEvent.java \
        backend/src/main/java/com/tcms/project/ReviewApprovedEvent.java \
        backend/src/main/java/com/tcms/project/ReviewService.java \
        backend/src/test/java/com/tcms/project/ReviewEventPayloadTest.java \
        backend/src/test/java/com/tcms/project/ReviewEndpointTest.java
git commit -m "feat: the review workflow records its four occurrences

Inside the transactions that already write the rows, so the fact and the notice
of it commit together. No handler claims them until the next commit."
```

---

## Phase 4 — the consumers

**One commit.** Four handlers, the copy they render, and the third leg of each pin.

### Task 11: Write the three consumer payloads

**Files:**
- Create: `backend/src/main/java/com/tcms/integration/ReviewRequestedPayload.java`
- Create: `backend/src/main/java/com/tcms/integration/ReviewVerdictPayload.java`
- Create: `backend/src/main/java/com/tcms/integration/ReviewApprovedPayload.java`

- [ ] **Step 1: `ReviewRequestedPayload`**

```java
package com.tcms.integration;

import com.tcms.project.ReviewSubjectType;
import java.util.UUID;

/**
 * {@code review.requested}, as this module reads it.
 *
 * <p>The consumer half of a pair; {@code project.ReviewRequestedEvent} is the other. See
 * {@link RunCompletedPayload}'s header for why they are two records and what keeps them honest.
 *
 * <h2>{@link ReviewSubjectType} is shared, and that is not the sharing plan §3 forbids</h2>
 *
 * <p>The rule is that the producing and consuming modules may not share the <em>payload</em> class,
 * because that is the shape the wire carries and a compiler agreeing about it hides a drift the
 * wire does not survive. A subject type is a vocabulary this module legitimately depends on —
 * {@code integration} declares {@code project} — and it is the key this module's
 * {@code ReviewSubjects} index is built on. Reading it as a {@code String} and parsing it here
 * would be the same coupling with the compiler switched off.
 *
 * @param assigneeUserId who was asked, or null when a whole team was. Exactly one of this and
 *                       {@link #assigneeTeamId()} is set on every row the product writes, and the
 *                       renderer has two arms for that reason and no third
 */
record ReviewRequestedPayload(
        UUID eventId,
        UUID reviewId,
        UUID projectId,
        ReviewSubjectType subjectType,
        UUID subjectId,
        UUID assigneeUserId,
        UUID assigneeTeamId,
        UUID requestedBy) {
}
```

- [ ] **Step 2: `ReviewVerdictPayload`**

```java
package com.tcms.integration;

import com.tcms.project.ReviewSubjectType;
import java.util.UUID;

/**
 * {@code review.endorsed} and {@code review.changes_requested}, as this module reads them.
 *
 * <p>One record for two event types, because the two occurrences differ only in what a peer said
 * and the name carries that. Two handlers claim the two names and both parse this; see
 * {@code project.ReviewVerdictEvent} for why the record has no verdict field.
 *
 * @param submittedBy the person a "changes requested" is addressed to, or null on an
 *                    unattributable review. The renderer then says the artifact was sent back
 *                    without naming anybody, rather than attributing it
 */
record ReviewVerdictPayload(
        UUID eventId,
        UUID reviewId,
        UUID projectId,
        ReviewSubjectType subjectType,
        UUID subjectId,
        UUID reviewerId,
        UUID submittedBy,
        String comment) {
}
```

- [ ] **Step 3: `ReviewApprovedPayload`**

```java
package com.tcms.integration;

import com.tcms.project.ReviewSubjectType;
import java.util.UUID;

/**
 * {@code review.approved}, as this module reads it — the sign-off.
 *
 * <p>The consumer half of a pair; {@code project.ReviewApprovedEvent} is the other.
 *
 * @param subjectVersionNo the version that was submitted and reviewed, never the artifact's current
 *                         one. Rendered as a fact because a sign-off that named the artifact alone
 *                         reads as approving whatever it says now
 */
record ReviewApprovedPayload(
        UUID eventId,
        UUID reviewId,
        UUID projectId,
        ReviewSubjectType subjectType,
        UUID subjectId,
        int subjectVersionNo,
        UUID approvedBy,
        UUID submittedBy) {
}
```

### Task 12: Write the subject index and the shared resolutions

**Files:**
- Create: `backend/src/main/java/com/tcms/integration/ReviewSubjects.java`
- Create: `backend/src/main/java/com/tcms/integration/ReviewNotices.java`

- [ ] **Step 1: `ReviewSubjects`**

```java
package com.tcms.integration;

import com.tcms.project.ReviewSubjectDirectory;
import com.tcms.project.ReviewSubjectType;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The artifact a review is about, by key and title.
 *
 * <p>{@link NotificationProjects}' shape for the second unresolvable id in a review payload, and
 * its reason: a message's reader is in a chat room with nothing open, and the value is resolved at
 * delivery rather than carried, because a title is edited and a frozen one would be announced by a
 * retry an hour later.
 *
 * <p>The index is built once at construction and fails there, which is
 * {@code NotificationDispatchService#index}'s arrangement: two beans claiming one subject type is a
 * coin toss over which table is read, and it is decidable before anything runs.
 */
@Component
class ReviewSubjects {

    private final Map<ReviewSubjectType, ReviewSubjectDirectory> byType;

    ReviewSubjects(List<ReviewSubjectDirectory> directories) {
        Map<ReviewSubjectType, ReviewSubjectDirectory> index =
                new EnumMap<>(ReviewSubjectType.class);
        for (ReviewSubjectDirectory directory : directories) {
            ReviewSubjectDirectory clash = index.put(directory.subjectType(), directory);
            if (clash != null) {
                throw new IllegalStateException(
                        "Two ReviewSubjectDirectory beans claim " + directory.subjectType() + ": "
                                + clash.getClass().getSimpleName() + " and "
                                + directory.getClass().getSimpleName());
            }
        }
        this.byType = Map.copyOf(index);
    }

    /**
     * @return the artifact, or empty when it has been deleted since the review was written — a
     *         review is history and outlives its subject
     * @throws IllegalStateException when no directory claims the subject type. A missing one is a
     *         deployment that cannot describe an artifact it is announcing, so it is a thrown
     *         error and therefore a retried outbox row an operator can see, rather than a message
     *         that quietly declines to say what it is about
     */
    Optional<ReviewSubjectDirectory.Subject> describe(
            ReviewSubjectType subjectType, UUID projectId, UUID subjectId) {

        ReviewSubjectDirectory directory = byType.get(subjectType);
        if (directory == null) {
            throw new IllegalStateException(
                    "No ReviewSubjectDirectory is registered for " + subjectType);
        }
        return directory.describe(projectId, subjectId);
    }
}
```

- [ ] **Step 2: `ReviewNotices`**

```java
package com.tcms.integration;

import com.tcms.api.TeamRef;
import com.tcms.api.UserRef;
import com.tcms.project.PeopleDirectory;
import com.tcms.project.TeamDirectory;
import com.tcms.tenancy.CompanyContext;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The three resolutions every review notifier needs, in one place instead of four.
 *
 * <p>Four handlers each resolving a project name, a subject and a person is four copies of the
 * "what does this null mean" decision, and the copies would only disagree in a deployment where
 * somebody has configured a channel — a divergence with no observer, which is
 * {@link NotificationTexts}' own reason for existing.
 *
 * <p>What it does <strong>not</strong> hold is any of the copy. A resolution is a lookup; a
 * sentence is a display decision, and that lives one file over.
 */
@Component
class ReviewNotices {

    private final PeopleDirectory people;
    private final TeamDirectory teams;

    ReviewNotices(PeopleDirectory people, TeamDirectory teams) {
        this.people = people;
        this.teams = teams;
    }

    /**
     * @return the person's display name, or null when the id names no account or is itself null.
     *         Both are states real rows are in — {@code review.created_by} is nullable, and
     *         somebody can be off-boarded between the act and the delivery — and
     *         {@link PeopleDirectory} yields no entry rather than inventing one
     */
    String nameOf(UUID userId) {
        if (userId == null) {
            return null;
        }
        Map<UUID, UserRef> resolved =
                people.resolve(CompanyContext.require(), List.of(userId));
        UserRef person = resolved.get(userId);
        return person == null ? null : person.displayName();
    }

    /** @return the team's name, or null when the id names no team or is itself null */
    String teamNameOf(UUID teamId) {
        if (teamId == null) {
            return null;
        }
        return teams.find(CompanyContext.require(), teamId).map(TeamRef::name).orElse(null);
    }
}
```

- [ ] **Step 3: Check `TeamDirectory`'s method and `TeamRef`'s accessor**

```bash
grep -n "Optional<TeamRef> find\|record TeamRef" backend/src/main/java/com/tcms/project/TeamDirectory.java backend/src/main/java/com/tcms/api/TeamRef.java
```

Expected: a `find(UUID companyId, UUID teamId)` returning `Optional<TeamRef>` and a `name`
component. Adapt Step 2 to the real names — `ReviewService` calls
`teams.find(companyId, teamId).orElse(null)`, so the shape is that.

### Task 13: Write the four renderings

**Files:**
- Modify: `backend/src/main/java/com/tcms/integration/NotificationTexts.java`

- [ ] **Step 1: Add the shared subject wording**

Append to `NotificationTexts`, inside the class:

```java
    /** What a message calls a person whose account no longer resolves. */
    private static final String NOBODY = "someone who is no longer on the instance";

    /**
     * The artifact, as a reader with nothing open can find it.
     *
     * @param subject the resolved key and title, or null when the artifact has been deleted since
     *                the review was written — a review is history and outlives its subject, so the
     *                message says what kind of thing it was and declines to name an id. A UUID in a
     *                sentence reads as a fault in the product ({@code NotificationProjects}' rule)
     */
    private static String subjectOf(
            com.tcms.project.ReviewSubjectDirectory.Subject subject,
            com.tcms.project.ReviewSubjectType subjectType) {

        if (subject == null) {
            return "a " + nounOf(subjectType) + " that has since been deleted";
        }
        return subject.key() + " — " + subject.title();
    }

    /** The artifact's kind, in the words the product's own screens use. */
    private static String nounOf(com.tcms.project.ReviewSubjectType subjectType) {
        return switch (subjectType) {
            case CASE -> "test case";
            case SUITE -> "suite";
            case PLAN -> "test plan";
        };
    }
```

- [ ] **Step 2: `reviewRequested`**

```java
    /**
     * Somebody was asked to review an artifact.
     *
     * <p>The title names <em>who</em>, because on a phone the title is frequently all that is read
     * and the one question this message answers is whether it is about the reader.
     *
     * @param askedOf  the assignee's display name — a person's, or a team's. Exactly one of the
     *                 payload's two ids is set, so this is never a choice between two names
     * @param askedBy  who asked, or null when their account no longer resolves
     */
    static RenderedNotification reviewRequested(
            ReviewRequestedPayload payload,
            String projectName,
            com.tcms.project.ReviewSubjectDirectory.Subject subject,
            String askedOf,
            String askedBy) {

        String who = askedOf == null ? NOBODY : askedOf;
        String asker = askedBy == null ? NOBODY : askedBy;
        String artifact = subjectOf(subject, payload.subjectType());

        return new RenderedNotification(
                "Review requested from " + who,
                asker + " asked for a review of " + artifact,
                List.of(new RenderedNotification.Fact("Project", projectName),
                        new RenderedNotification.Fact("Reviewer", who),
                        new RenderedNotification.Fact("Asked by", asker),
                        new RenderedNotification.Fact(
                                capitalized(nounOf(payload.subjectType())), artifact)));
    }
```

- [ ] **Step 3: The two verdicts**

```java
    /**
     * A peer said the artifact looks good.
     *
     * <p>The title says "looks good to", not "approved": this is advisory and the sign-off is a
     * different act with a different permission, and a channel that read the two as one would
     * announce work as signed off that nobody with {@code *:approve} has looked at.
     */
    static RenderedNotification reviewEndorsed(
            ReviewVerdictPayload payload,
            String projectName,
            com.tcms.project.ReviewSubjectDirectory.Subject subject,
            String reviewer,
            String submitter) {

        return verdict(payload, projectName, subject, reviewer, submitter,
                (who, artifact) -> who + " thinks " + artifact + " looks good",
                "Looks good to " + (reviewer == null ? NOBODY : reviewer));
    }

    /** A peer asked for changes — the message the submitter has to act on. */
    static RenderedNotification reviewChangesRequested(
            ReviewVerdictPayload payload,
            String projectName,
            com.tcms.project.ReviewSubjectDirectory.Subject subject,
            String reviewer,
            String submitter) {

        return verdict(payload, projectName, subject, reviewer, submitter,
                (who, artifact) -> who + " asked for changes to " + artifact,
                "Changes requested by " + (reviewer == null ? NOBODY : reviewer));
    }

    /**
     * The two verdicts' shared body. They differ in one sentence and one title, and writing them
     * out twice is how the facts below come to be listed in two orders.
     */
    private static RenderedNotification verdict(
            ReviewVerdictPayload payload,
            String projectName,
            com.tcms.project.ReviewSubjectDirectory.Subject subject,
            String reviewer,
            String submitter,
            java.util.function.BinaryOperator<String> summary,
            String title) {

        String who = reviewer == null ? NOBODY : reviewer;
        String artifact = subjectOf(subject, payload.subjectType());

        List<RenderedNotification.Fact> facts = new ArrayList<>();
        facts.add(new RenderedNotification.Fact("Project", projectName));
        facts.add(new RenderedNotification.Fact(
                capitalized(nounOf(payload.subjectType())), artifact));
        facts.add(new RenderedNotification.Fact("Reviewer", who));
        // Omitted rather than rendered as "nobody": review.created_by is nullable, and a fact
        // saying the artifact was submitted by nobody invites the reader to go looking for a
        // person who was never recorded.
        if (submitter != null) {
            facts.add(new RenderedNotification.Fact("Submitted by", submitter));
        }
        if (payload.comment() != null && !payload.comment().isBlank()) {
            facts.add(new RenderedNotification.Fact("Comment", payload.comment()));
        }

        return new RenderedNotification(title, summary.apply(who, artifact), List.copyOf(facts));
    }
```

- [ ] **Step 4: `reviewApproved`**

```java
    /**
     * A review was signed off.
     *
     * <p>The version is a fact rather than part of the title, and it is here at all because an
     * approval applies to the version that was submitted: an artifact edited since then has moved
     * past what anybody signed, which is §6g's diff-since-approval case.
     */
    static RenderedNotification reviewApproved(
            ReviewApprovedPayload payload,
            String projectName,
            com.tcms.project.ReviewSubjectDirectory.Subject subject,
            String approver,
            String submitter) {

        String who = approver == null ? NOBODY : approver;
        String artifact = subjectOf(subject, payload.subjectType());

        List<RenderedNotification.Fact> facts = new ArrayList<>();
        facts.add(new RenderedNotification.Fact("Project", projectName));
        facts.add(new RenderedNotification.Fact(
                capitalized(nounOf(payload.subjectType())), artifact));
        facts.add(new RenderedNotification.Fact("Approved by", who));
        facts.add(new RenderedNotification.Fact(
                "Version", "v" + payload.subjectVersionNo()));
        if (submitter != null) {
            facts.add(new RenderedNotification.Fact("Submitted by", submitter));
        }

        return new RenderedNotification(
                capitalized(nounOf(payload.subjectType())) + " approved — " + artifact,
                who + " signed off " + artifact,
                List.copyOf(facts));
    }

    /** A fact's label is a heading; a noun that starts a sentence in the middle of one is not. */
    private static String capitalized(String noun) {
        return Character.toUpperCase(noun.charAt(0)) + noun.substring(1);
    }
```

### Task 14: Write the four notifiers

**Files:**
- Create: `backend/src/main/java/com/tcms/integration/ReviewRequestedNotifier.java`
- Create: `backend/src/main/java/com/tcms/integration/ReviewEndorsedNotifier.java`
- Create: `backend/src/main/java/com/tcms/integration/ReviewChangesRequestedNotifier.java`
- Create: `backend/src/main/java/com/tcms/integration/ReviewApprovedNotifier.java`

- [ ] **Step 1: `ReviewRequestedNotifier`**

```java
package com.tcms.integration;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tcms.tenancy.OutboxEvents;
import com.tcms.tenancy.OutboxHandler;
import org.springframework.stereotype.Component;

/**
 * Tells this project's channels that somebody was asked to review an artifact.
 *
 * <p>{@link ItemAssignedNotifier}'s shape exactly, including its idempotency argument — the durable
 * write is a {@code notification_delivery} row keyed on {@code (company_id, channel_id, event_id)},
 * and nothing else here writes.
 *
 * <p><strong>Every name and the artifact itself are resolved here, not carried.</strong> See
 * {@link ReviewSubjects} and {@link ReviewNotices} for the reason, which is the same one
 * {@link NotificationProjects} states: a value frozen into a payload is the one it had when the
 * occurrence was recorded, and a retry announces it.
 */
@Component
class ReviewRequestedNotifier implements OutboxHandler {

    private final NotificationDispatchService dispatch;
    private final NotificationProjects projects;
    private final ReviewSubjects subjects;
    private final ReviewNotices notices;
    private final ObjectMapper json;

    ReviewRequestedNotifier(
            NotificationDispatchService dispatch,
            NotificationProjects projects,
            ReviewSubjects subjects,
            ReviewNotices notices,
            ObjectMapper json) {

        this.dispatch = dispatch;
        this.projects = projects;
        this.subjects = subjects;
        this.notices = notices;
        this.json = json;
    }

    @Override
    public String eventType() {
        return OutboxEvents.REVIEW_REQUESTED;
    }

    @Override
    public void handle(com.tcms.tenancy.OutboxMessage message) {
        ReviewRequestedPayload payload = parse(message.payload());

        // Exactly one of the two ids is set — ReviewAssigneeEdit refuses both and neither — so this
        // is one name and not a choice between two.
        String askedOf = payload.assigneeUserId() != null
                ? notices.nameOf(payload.assigneeUserId())
                : notices.teamNameOf(payload.assigneeTeamId());

        dispatch.dispatch(
                payload.projectId(),
                OutboxEvents.REVIEW_REQUESTED,
                payload.eventId(),
                // No verdicts to threshold. Null matches every subscription rather than none —
                // see NotificationSubscription#wants.
                null,
                NotificationTexts.reviewRequested(
                        payload,
                        projects.nameOf(payload.projectId()),
                        subjects.describe(
                                payload.subjectType(),
                                payload.projectId(),
                                payload.subjectId()).orElse(null),
                        askedOf,
                        notices.nameOf(payload.requestedBy())));
    }

    /** Throws for {@link RunCompletedNotifier#handle}'s reason — a retry cannot fix a bad shape. */
    private ReviewRequestedPayload parse(String payload) {
        try {
            return json.readValue(payload, ReviewRequestedPayload.class);
        } catch (JsonProcessingException unreadable) {
            throw new IllegalStateException(
                    "A " + OutboxEvents.REVIEW_REQUESTED + " payload could not be read as the "
                            + "shape this module declares — see ReviewRequestedPayload", unreadable);
        }
    }
}
```

- [ ] **Step 2: `ReviewEndorsedNotifier`**

```java
package com.tcms.integration;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tcms.tenancy.OutboxEvents;
import com.tcms.tenancy.OutboxHandler;
import com.tcms.tenancy.OutboxMessage;
import org.springframework.stereotype.Component;

/**
 * Tells this project's channels that a peer said an artifact looks good.
 *
 * <p><strong>Not the sign-off.</strong> {@code OutboxEvents#REVIEW_ENDORSED} carries the argument:
 * recording a peer verdict costs {@code *:review} and signing off costs {@code *:approve}, so this
 * message must not read as an approval. {@link ReviewApprovedNotifier} is that one.
 *
 * <p>The twin of {@link ReviewChangesRequestedNotifier}: one payload record, two event names, two
 * handlers, because {@link OutboxHandler#eventType()} claims exactly one name and a subscriber
 * picks between the two at the subscription.
 */
@Component
class ReviewEndorsedNotifier implements OutboxHandler {

    private final NotificationDispatchService dispatch;
    private final NotificationProjects projects;
    private final ReviewSubjects subjects;
    private final ReviewNotices notices;
    private final ObjectMapper json;

    ReviewEndorsedNotifier(
            NotificationDispatchService dispatch,
            NotificationProjects projects,
            ReviewSubjects subjects,
            ReviewNotices notices,
            ObjectMapper json) {

        this.dispatch = dispatch;
        this.projects = projects;
        this.subjects = subjects;
        this.notices = notices;
        this.json = json;
    }

    @Override
    public String eventType() {
        return OutboxEvents.REVIEW_ENDORSED;
    }

    @Override
    public void handle(OutboxMessage message) {
        ReviewVerdictPayload payload = ReviewVerdicts.parse(
                json, message.payload(), OutboxEvents.REVIEW_ENDORSED);

        dispatch.dispatch(
                payload.projectId(),
                OutboxEvents.REVIEW_ENDORSED,
                payload.eventId(),
                null,
                NotificationTexts.reviewEndorsed(
                        payload,
                        projects.nameOf(payload.projectId()),
                        subjects.describe(
                                payload.subjectType(),
                                payload.projectId(),
                                payload.subjectId()).orElse(null),
                        notices.nameOf(payload.reviewerId()),
                        notices.nameOf(payload.submittedBy())));
    }
}
```

- [ ] **Step 3: `ReviewVerdicts`, the one parse the twins share**

Create `backend/src/main/java/com/tcms/integration/ReviewVerdicts.java`:

```java
package com.tcms.integration;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The parse {@link ReviewEndorsedNotifier} and {@link ReviewChangesRequestedNotifier} share.
 *
 * <p>Two handlers read one payload record, so the failure sentence would otherwise be written twice
 * and the two copies would name different records the day somebody renames one.
 */
final class ReviewVerdicts {

    private ReviewVerdicts() {
    }

    /** Throws for {@code RunCompletedNotifier#handle}'s reason — a retry cannot fix a bad shape. */
    static ReviewVerdictPayload parse(ObjectMapper json, String payload, String eventType) {
        try {
            return json.readValue(payload, ReviewVerdictPayload.class);
        } catch (JsonProcessingException unreadable) {
            throw new IllegalStateException(
                    "A " + eventType + " payload could not be read as the shape this module "
                            + "declares — see ReviewVerdictPayload", unreadable);
        }
    }
}
```

- [ ] **Step 4: `ReviewChangesRequestedNotifier`**

Identical to `ReviewEndorsedNotifier` with three substitutions — the class name, both occurrences of
`OutboxEvents.REVIEW_ENDORSED` replaced by `OutboxEvents.REVIEW_CHANGES_REQUESTED`, and
`NotificationTexts.reviewEndorsed` replaced by `NotificationTexts.reviewChangesRequested`. Its
header:

```java
/**
 * Tells this project's channels that a peer asked for changes.
 *
 * <p>The twin of {@link ReviewEndorsedNotifier}; see its header. Advisory — a changes-requested
 * verdict does not move the artifact off {@code IN_REVIEW}, and the message says so by asking for
 * changes rather than announcing a rejection.
 */
```

- [ ] **Step 5: `ReviewApprovedNotifier`**

```java
package com.tcms.integration;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tcms.tenancy.OutboxEvents;
import com.tcms.tenancy.OutboxHandler;
import com.tcms.tenancy.OutboxMessage;
import org.springframework.stereotype.Component;

/**
 * Tells this project's channels that a review was signed off.
 *
 * <p><strong>One message per artifact, including inside a bulk approval.</strong>
 * {@code OutboxEvents#REVIEW_APPROVED} carries the whole argument: a bulk operation moves up to 500
 * cases and records one occurrence for each, so a channel subscribed to this in a project that
 * bulk-approves will be told 500 times. Each is true and names a different subject; the fix is a
 * digest on the subscriber's side and it does not exist yet.
 */
@Component
class ReviewApprovedNotifier implements OutboxHandler {

    private final NotificationDispatchService dispatch;
    private final NotificationProjects projects;
    private final ReviewSubjects subjects;
    private final ReviewNotices notices;
    private final ObjectMapper json;

    ReviewApprovedNotifier(
            NotificationDispatchService dispatch,
            NotificationProjects projects,
            ReviewSubjects subjects,
            ReviewNotices notices,
            ObjectMapper json) {

        this.dispatch = dispatch;
        this.projects = projects;
        this.subjects = subjects;
        this.notices = notices;
        this.json = json;
    }

    @Override
    public String eventType() {
        return OutboxEvents.REVIEW_APPROVED;
    }

    @Override
    public void handle(OutboxMessage message) {
        ReviewApprovedPayload payload = parse(message.payload());

        dispatch.dispatch(
                payload.projectId(),
                OutboxEvents.REVIEW_APPROVED,
                payload.eventId(),
                null,
                NotificationTexts.reviewApproved(
                        payload,
                        projects.nameOf(payload.projectId()),
                        subjects.describe(
                                payload.subjectType(),
                                payload.projectId(),
                                payload.subjectId()).orElse(null),
                        notices.nameOf(payload.approvedBy()),
                        notices.nameOf(payload.submittedBy())));
    }

    /** Throws for {@link RunCompletedNotifier#handle}'s reason — a retry cannot fix a bad shape. */
    private ReviewApprovedPayload parse(String payload) {
        try {
            return json.readValue(payload, ReviewApprovedPayload.class);
        } catch (JsonProcessingException unreadable) {
            throw new IllegalStateException(
                    "A " + OutboxEvents.REVIEW_APPROVED + " payload could not be read as the "
                            + "shape this module declares — see ReviewApprovedPayload", unreadable);
        }
    }
}
```

- [ ] **Step 6: Compile**

```bash
~/bin/tcms-remote backend ./gradlew compileJava
```

Expected: `BUILD SUCCESSFUL`.

### Task 15: Write the consumer pin

**Files:**
- Create: `backend/src/test/java/com/tcms/integration/ReviewPayloadTest.java`

- [ ] **Step 1: Write the test**

```java
package com.tcms.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tcms.project.ReviewSubjectType;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

/**
 * The consumer half of the three review payload pins — {@link ItemAssignedPayloadTest}'s shape,
 * three events over, and its header carries the argument.
 *
 * <p>{@code com.tcms.project.ReviewEventPayloadTest} is the producer half.
 *
 * <p>The asymmetry is deliberate and is the same one the other pins have: an <em>unknown</em> field
 * is tolerated, because a producer may widen before every consumer wants it, and an <em>absent</em>
 * one is not, because it deserializes to null or zero in silence and a renderer then announces a
 * sign-off "at v0".
 */
@DisplayName("review.* — the shapes this module requires (the consumer pin)")
class ReviewPayloadTest {

    private static final String REQUESTED = """
            {
              "eventId": "b8000000-0000-4000-8000-000000000001",
              "reviewId": "b8000000-0000-4000-8000-000000000002",
              "projectId": "b8000000-0000-4000-8000-000000000003",
              "subjectType": "CASE",
              "subjectId": "b8000000-0000-4000-8000-000000000004",
              "assigneeUserId": "b8000000-0000-4000-8000-000000000005",
              "assigneeTeamId": null,
              "requestedBy": "b8000000-0000-4000-8000-000000000006"
            }
            """;

    private static final String VERDICT = """
            {
              "eventId": "b8000000-0000-4000-8000-000000000011",
              "reviewId": "b8000000-0000-4000-8000-000000000012",
              "projectId": "b8000000-0000-4000-8000-000000000013",
              "subjectType": "SUITE",
              "subjectId": "b8000000-0000-4000-8000-000000000014",
              "reviewerId": "b8000000-0000-4000-8000-000000000015",
              "submittedBy": null,
              "comment": "Step 3 asserts nothing"
            }
            """;

    private static final String APPROVED = """
            {
              "eventId": "b8000000-0000-4000-8000-000000000021",
              "reviewId": "b8000000-0000-4000-8000-000000000022",
              "projectId": "b8000000-0000-4000-8000-000000000023",
              "subjectType": "PLAN",
              "subjectId": "b8000000-0000-4000-8000-000000000024",
              "subjectVersionNo": 4,
              "approvedBy": "b8000000-0000-4000-8000-000000000025",
              "submittedBy": "b8000000-0000-4000-8000-000000000026"
            }
            """;

    private final ObjectMapper json = Jackson2ObjectMapperBuilder.json().build();

    @Test
    @DisplayName("review.requested reads whole, with the subject type as an enum")
    void theRequestIsRead() throws Exception {
        ReviewRequestedPayload payload = json.readValue(REQUESTED, ReviewRequestedPayload.class);

        assertThat(payload.eventId())
                .isEqualTo(UUID.fromString("b8000000-0000-4000-8000-000000000001"));
        assertThat(payload.subjectType())
                .as("the index this module keys its subject directories on is built on this value")
                .isEqualTo(ReviewSubjectType.CASE);
        assertThat(payload.assigneeUserId())
                .isEqualTo(UUID.fromString("b8000000-0000-4000-8000-000000000005"));
        assertThat(payload.assigneeTeamId())
                .as("exactly one of the two arms is set on every row the product writes")
                .isNull();
        assertThat(payload.requestedBy())
                .isEqualTo(UUID.fromString("b8000000-0000-4000-8000-000000000006"));

        assertThat(components(ReviewRequestedPayload.class))
                .containsExactly("eventId", "reviewId", "projectId", "subjectType", "subjectId",
                        "assigneeUserId", "assigneeTeamId", "requestedBy");
    }

    @Test
    @DisplayName("a verdict reads whole, and a null submitter stays null rather than throwing")
    void theVerdictIsRead() throws Exception {
        ReviewVerdictPayload payload = json.readValue(VERDICT, ReviewVerdictPayload.class);

        assertThat(payload.subjectType()).isEqualTo(ReviewSubjectType.SUITE);
        assertThat(payload.reviewerId())
                .isEqualTo(UUID.fromString("b8000000-0000-4000-8000-000000000015"));
        assertThat(payload.submittedBy())
                .as("review.created_by is nullable and the renderer omits the fact rather than "
                        + "naming nobody")
                .isNull();
        assertThat(payload.comment()).isEqualTo("Step 3 asserts nothing");

        assertThat(components(ReviewVerdictPayload.class))
                .as("no verdict component — the event name is the verdict, and a second copy of it "
                        + "here is the one that can disagree")
                .containsExactly("eventId", "reviewId", "projectId", "subjectType", "subjectId",
                        "reviewerId", "submittedBy", "comment");
    }

    @Test
    @DisplayName("review.approved reads whole, version included")
    void theApprovalIsRead() throws Exception {
        ReviewApprovedPayload payload = json.readValue(APPROVED, ReviewApprovedPayload.class);

        assertThat(payload.subjectType()).isEqualTo(ReviewSubjectType.PLAN);
        assertThat(payload.subjectVersionNo())
                .as("an absent int deserializes to 0 in silence, and the message would announce a "
                        + "sign-off at v0")
                .isEqualTo(4);
        assertThat(payload.approvedBy())
                .isEqualTo(UUID.fromString("b8000000-0000-4000-8000-000000000025"));

        assertThat(components(ReviewApprovedPayload.class))
                .containsExactly("eventId", "reviewId", "projectId", "subjectType", "subjectId",
                        "subjectVersionNo", "approvedBy", "submittedBy");
    }

    private static java.util.List<String> components(Class<?> record) {
        return Arrays.stream(record.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();
    }
}
```

- [ ] **Step 2: Run it**

```bash
~/bin/tcms-remote backend ./gradlew test --tests 'com.tcms.integration.ReviewPayloadTest'
```

Expected: PASS, three tests.

### Task 16: Write the rendering test

**Files:**
- Create: `backend/src/test/java/com/tcms/integration/ReviewRenderingTest.java`

- [ ] **Step 1: Write the test**

```java
package com.tcms.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.tcms.project.ReviewSubjectDirectory;
import com.tcms.project.ReviewSubjectType;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What the four review messages actually say — {@link NotificationRenderingTest}'s companion, and
 * in particular every arm where a row no longer resolves.
 *
 * <p>No Spring context: {@link NotificationTexts} is a pure function of a payload and four resolved
 * strings, which is the property that lets the copy be checked at all.
 */
@DisplayName("Review notifications — what the message says, including when a row has gone")
class ReviewRenderingTest {

    private static final UUID ID = UUID.fromString("b9000000-0000-4000-8000-000000000001");
    private static final ReviewSubjectDirectory.Subject CASE_12 =
            new ReviewSubjectDirectory.Subject("CASE-12", "Login rejects a wrong password");

    @Test
    @DisplayName("a request names the reviewer in the title and the artifact in the summary")
    void theRequestNamesBoth() {
        RenderedNotification message = NotificationTexts.reviewRequested(
                new ReviewRequestedPayload(ID, ID, ID, ReviewSubjectType.CASE, ID, ID, null, ID),
                "Online Banking", CASE_12, "M. Reviewer", "L. Lead");

        assertThat(message.title()).isEqualTo("Review requested from M. Reviewer");
        assertThat(message.summary())
                .contains("L. Lead")
                .contains("CASE-12 — Login rejects a wrong password");
        assertThat(message.facts()).extracting(RenderedNotification.Fact::label)
                .containsExactly("Project", "Reviewer", "Asked by", "Test case");
    }

    @Test
    @DisplayName("a team request renders the team's name in the reviewer's place")
    void aTeamIsAReviewer() {
        RenderedNotification message = NotificationTexts.reviewRequested(
                new ReviewRequestedPayload(ID, ID, ID, ReviewSubjectType.CASE, ID, null, ID, ID),
                "Online Banking", CASE_12, "Payments QA", "L. Lead");

        assertThat(message.title())
                .as("the payload's two assignee arms are one name here — the handler picks")
                .isEqualTo("Review requested from Payments QA");
    }

    @Test
    @DisplayName("a deleted artifact is described by kind, never by id")
    void aDeletedSubjectIsNotAUuid() {
        RenderedNotification message = NotificationTexts.reviewApproved(
                new ReviewApprovedPayload(ID, ID, ID, ReviewSubjectType.PLAN, ID, 2, ID, null),
                "Online Banking", null, "L. Lead", null);

        assertThat(message.asPlainText())
                .as("a review is history and outlives its subject; a UUID in a sentence reads as a "
                        + "fault in the product")
                .contains("a test plan that has since been deleted")
                .doesNotContain(ID.toString());
    }

    @Test
    @DisplayName("an endorsement does not read as an approval")
    void theEndorsementIsNotASignOff() {
        RenderedNotification message = NotificationTexts.reviewEndorsed(
                new ReviewVerdictPayload(ID, ID, ID, ReviewSubjectType.CASE, ID, ID, ID, null),
                "Online Banking", CASE_12, "M. Reviewer", "A. Author");

        assertThat(message.title().toLowerCase(java.util.Locale.ROOT))
                .as("recording a peer verdict costs *:review and signing off costs *:approve — a "
                        + "message that conflated them would announce work as signed off that "
                        + "nobody with the approve key has looked at")
                .doesNotContain("approv");
        assertThat(message.facts()).extracting(RenderedNotification.Fact::label)
                .containsExactly("Project", "Test case", "Reviewer", "Submitted by");
    }

    @Test
    @DisplayName("a changes-requested verdict carries the peer's own words when there are any")
    void theCommentIsCarried() {
        RenderedNotification withComment = NotificationTexts.reviewChangesRequested(
                new ReviewVerdictPayload(ID, ID, ID, ReviewSubjectType.CASE, ID, ID, ID,
                        "Step 3 asserts nothing"),
                "Online Banking", CASE_12, "M. Reviewer", "A. Author");

        assertThat(withComment.facts()).extracting(RenderedNotification.Fact::value)
                .contains("Step 3 asserts nothing");

        RenderedNotification without = NotificationTexts.reviewChangesRequested(
                new ReviewVerdictPayload(ID, ID, ID, ReviewSubjectType.CASE, ID, ID, ID, null),
                "Online Banking", CASE_12, "M. Reviewer", "A. Author");

        assertThat(without.facts()).extracting(RenderedNotification.Fact::label)
                .as("an empty Comment fact reads as a remark that was lost")
                .doesNotContain("Comment");
    }

    @Test
    @DisplayName("an unattributable submitter is omitted rather than named as nobody")
    void nobodyIsNotAPerson() {
        RenderedNotification message = NotificationTexts.reviewChangesRequested(
                new ReviewVerdictPayload(ID, ID, ID, ReviewSubjectType.SUITE, ID, ID, null, null),
                "Online Banking", CASE_12, "M. Reviewer", null);

        assertThat(message.facts()).extracting(RenderedNotification.Fact::label)
                .as("a fact saying the artifact was submitted by nobody invites the reader to go "
                        + "looking for a person who was never recorded")
                .doesNotContain("Submitted by");
    }

    @Test
    @DisplayName("an approval names the version that was reviewed")
    void theVersionIsAFact() {
        RenderedNotification message = NotificationTexts.reviewApproved(
                new ReviewApprovedPayload(ID, ID, ID, ReviewSubjectType.CASE, ID, 3, ID, ID),
                "Online Banking", CASE_12, "L. Lead", "A. Author");

        assertThat(message.facts())
                .extracting(RenderedNotification.Fact::label, RenderedNotification.Fact::value)
                .contains(org.assertj.core.groups.Tuple.tuple("Version", "v3"));
    }
}
```

- [ ] **Step 2: Run it**

```bash
~/bin/tcms-remote backend ./gradlew test --tests 'com.tcms.integration.ReviewRenderingTest'
```

Expected: PASS, seven tests. Adjust the exact fact-label lists to what Task 13 produced — the labels
are the assertion's subject, so change the test only when the message is what you meant to change.

### Task 17: Write the third leg — producer to handler, through the real outbox

**Files:**
- Create: `backend/src/test/java/com/tcms/integration/ReviewNotificationTest.java`

- [ ] **Step 1: Read the model this test copies**

```bash
sed -n '1,150p' backend/src/test/java/com/tcms/integration/NotificationDeliveryTest.java
```

`ReviewNotificationTest` is that class's shape: an unreachable webhook on `127.0.0.1:1`, a seeded
company/project/lead, `dispatch()` driving the real `OutboxDispatcher`, and assertions against
`notification_delivery` over a real connection. **Reuse its private helpers by copying them** —
`IntegrationTestBase` is not the place for a second test's fixtures.

- [ ] **Step 2: Write the test**

```java
package com.tcms.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.tcms.IntegrationTestBase;
import com.tcms.tenancy.OutboxEvents;
import java.sql.SQLException;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * <strong>The third leg of the review payload pins: producer to handler, through the real
 * outbox.</strong>
 *
 * <p>{@code com.tcms.project.ReviewEventPayloadTest} says what the producer writes and
 * {@link ReviewPayloadTest} says what the consumer requires. Between them they catch a change on
 * either side — and they do <em>not</em> catch a change made on both at once, which is what
 * renaming a field looks like. This is the test that fails then, because it drives the real
 * service, writes real {@code outbox} rows, runs the real dispatcher and asserts a delivery attempt
 * against a real channel. {@link NotificationDeliveryTest}'s header carries the argument in full.
 *
 * <p>Ids are unique to this class — {@code ba…}.
 */
@DisplayName("Review notifications end to end — being asked, judged and signed off all arrive")
class ReviewNotificationTest extends IntegrationTestBase {

    @Autowired
    private NotificationChannelService channels;

    // ... seeding as NotificationDeliveryTest does: a company, a project, a lead with
    // TEST_LEAD_ID on it, a peer with the same, review switched ON for CASE in project settings,
    // and one case submitted for review so an open review exists.

    @Test
    @DisplayName("asking somebody to review reaches a subscribed channel")
    void theRequestIsDelivered() throws SQLException {
        UUID channelId = channel("Reviews", OutboxEvents.REVIEW_REQUESTED);

        askPeerToReview();
        dispatch();

        assertThat(deliveries(OutboxEvents.REVIEW_REQUESTED))
                .as("a delivery row can only exist if the payload parsed — its project id and "
                        + "event id came out of the document and nowhere else")
                .isOne();
        assertThat(deliveredChannel(OutboxEvents.REVIEW_REQUESTED)).isEqualTo(channelId);
    }

    @Test
    @DisplayName("a peer's two verdicts are two event types, and a channel may take one")
    void theVerdictsAreSeparatelySubscribable() throws SQLException {
        channel("Only when it needs work", OutboxEvents.REVIEW_CHANGES_REQUESTED);

        recordVerdict("LGTM");
        dispatch();

        assertThat(deliveries(OutboxEvents.REVIEW_ENDORSED))
                .as("the subscription names one event type — an LGTM must not arrive on a channel "
                        + "that asked only for changes-requested")
                .isZero();

        recordVerdict("CHANGES_REQUESTED");
        dispatch();

        assertThat(deliveries(OutboxEvents.REVIEW_CHANGES_REQUESTED)).isOne();
    }

    @Test
    @DisplayName("approving the case delivers the sign-off, naming the version reviewed")
    void theApprovalIsDelivered() throws SQLException {
        channel("Reviews", OutboxEvents.REVIEW_APPROVED);

        approveTheCase();
        dispatch();

        assertThat(deliveries(OutboxEvents.REVIEW_APPROVED)).isOne();
        assertThat(outboxPayload(OutboxEvents.REVIEW_APPROVED))
                .as("the payload IS the message — integration may not read review or test_case")
                .contains("\"subjectType\": \"CASE\"")
                .contains("\"subjectVersionNo\": 1");
    }

    @Test
    @DisplayName("with review switched off, approving the case records nothing at all")
    void aProjectWithoutReviewIsSilent() throws SQLException {
        switchReviewOffForCases();
        channel("Reviews", OutboxEvents.REVIEW_APPROVED);

        approveASecondCase();
        dispatch();

        assertThat(deliveries(OutboxEvents.REVIEW_APPROVED))
                .as("§6g's opt-in: no review is opened, so there is nothing to have been asked "
                        + "about or signed off, and an event here would announce a review that "
                        + "did not happen")
                .isZero();
    }

    @Test
    @DisplayName("a retry updates the attempt rather than sending a second message")
    void theDeliveryIsKeyedOnTheOccurrence() throws SQLException {
        channel("Reviews", OutboxEvents.REVIEW_REQUESTED);

        askPeerToReview();
        dispatch();
        makeTheOutboxRowDue();
        dispatch();

        assertThat(deliveries(OutboxEvents.REVIEW_REQUESTED))
                .as("one row per (channel, occurrence) — at-least-once delivery is what the key "
                        + "exists for, and a notifier without a stable eventId double-sends on "
                        + "every retry")
                .isOne();
    }

    @Test
    @DisplayName("the artifact is named by key, not by id — the port is what makes that possible")
    void theMessageNamesTheArtifact() throws SQLException {
        channel("Reviews", OutboxEvents.REVIEW_REQUESTED);

        askPeerToReview();
        dispatch();

        assertThat(text("SELECT last_error FROM outbox WHERE event_type = 'review.requested'"))
                .as("the delivery fails against an unreachable webhook, and the failure names the "
                        + "channel rather than the message — so this arm asserts the port instead "
                        + "through ReviewSubjects, which the rendering test covers directly")
                .contains("Reviews");
    }
}
```

**Write the helpers out** (`channel`, `dispatch`, `deliveries`, `deliveredChannel`,
`outboxPayload`, `text`, `makeTheOutboxRowDue`, `askPeerToReview`, `recordVerdict`,
`approveTheCase`, `approveASecondCase`, `switchReviewOffForCases`) by copying
`NotificationDeliveryTest`'s and adapting the SQL to take an `event_type` argument. Do not leave the
`// ...` seeding comment in the committed file — seed exactly as that class does, plus:
`ProjectSettingsService` to switch review on for `CASE`, a case created through `CaseCreateService`,
and `CaseStatusService.setStatus(..., "IN_REVIEW")` to open the review.

- [ ] **Step 3: Run it**

```bash
~/bin/tcms-remote backend ./gradlew test --tests 'com.tcms.integration.ReviewNotificationTest'
```

Expected: PASS, six tests. A `DEAD` outbox row means a handler is not registered — check the
`eventType()` string against the constant rather than the literal.

- [ ] **Step 4: Full backend gate**

```bash
~/bin/tcms-remote backend ./gradlew build
```

Expected: `BUILD SUCCESSFUL`, test count up by roughly 25 on §242's 3097.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/tcms/integration/ backend/src/test/java/com/tcms/integration/
git commit -m "feat: four handlers for the review workflow's occurrences

Names, artifacts and project resolved at delivery, never carried. The two peer
verdicts are two event types so a channel can take one and not the other."
```

---

## Phase 5 — the record

**One commit.** The docs a later reader needs, and the deferred-verification rows §65 requires.

### Task 18: Write the handoff section

**Files:**
- Create: `docs/handoff/243-review-workflow-notifications.md`
- Modify: `docs/HANDOFF.md` — **one index row, appended to the table. Never append prose to
  `HANDOFF.md` itself.**

- [ ] **Step 1: Write `docs/handoff/243-review-workflow-notifications.md`**

Cover, in the register the numbered sections use: the four names and why two of them are not the
master plan's; the port and why it had to exist; that all four are silent in a project with review
off; the bulk fan-out, stated as a known property with the digest recorded as owed; that
`ReviewService` is the first foundation module to record an occurrence; the three-test pin paid three
times; the measured gate numbers from every phase.

- [ ] **Step 2: Add exactly one index row to `docs/HANDOFF.md`**

```markdown
| [§243](handoff/243-review-workflow-notifications.md) | **Review-workflow notifications** — slice 2 of `docs/plans/2026-08-18-unplanned-gaps.md`… |
```

### Task 19: Update both orientation caches

**Files:**
- Modify: `docs/agent-context/be-java.md`
- Modify: `docs/agent-context/fe-react.md`

- [ ] **Step 1: `be-java.md`**

Replace the `Last verified:` header with this slice's, keeping §242's below it as the superseded one
(the file's own convention), and add a section before §218 covering: the four names, the port and the
rule it generalises (**a question that needs a table two modules away is a port whose implementations
live in the modules that own the tables, indexed by the caller — never a wider
`allowedDependencies`**), and the bulk fan-out.

- [ ] **Step 2: `fe-react.md`**

One short entry: the notification event vocabulary went from two members to six, the SPA's checkbox
list is derived from `notificationEventTypeLabels` so nothing rendered had to change, and the
`satisfies Record<NotificationEventType, string>` on both maps is what turned the contract change
into a compile error rather than a blank label.

### Task 20: Add the deferred verification rows

**Files:**
- Modify: `docs/verification/backend-mutation-checks.md`
- Modify: `docs/verification/frontend-browser-checks.md`

**§65 is standing policy: write the rows, run nothing.** A slice that lands without them has skipped
the check outright.

- [ ] **Step 1: Backend mutation rows**

Add rows with stable ids (`RN-1`…), each naming what to drive and what to look for:

| id | Drive | Expect |
|---|---|---|
| `RN-1` | Switch review on for cases, submit one, ask a colleague to review it, with a Slack channel subscribed to `review.requested` | One `outbox` row, one `notification_delivery` row, the message naming the case by key |
| `RN-2` | Ask the same colleague again | No second `outbox` row |
| `RN-3` | Record `LGTM`, then `CHANGES_REQUESTED`, on a channel subscribed only to the latter | One delivery, for `review.changes_requested` |
| `RN-4` | Approve the case | One `review.approved` delivery naming the version submitted |
| `RN-5` | Bulk-approve 20 cases in a review-enabled project | 20 occurrences and 20 deliveries — **the known fan-out, recorded so the number is observed rather than assumed** |
| `RN-6` | Delete the case, then re-dispatch its pending `review.approved` row | The message says "a test case that has since been deleted" and contains no UUID |
| `RN-7` | Approve a case in a project with review switched **off** | No `outbox` row at all |

- [ ] **Step 2: Frontend browser rows**

One row, in the lane whose state already has a notification channel configured: open the channel
dialog and confirm six checkboxes with the four new labels and their help text, and that saving a
subscription to `review.approved` round-trips.

- [ ] **Step 3: Commit**

```bash
git add docs/
git commit -m "docs: review-notification handoff, seams and verification rows"
git push
```

---

## Self-review

**Spec coverage.** The master plan's Slice 2 asks for: four notifiers (Tasks 14 — four), payload
pairs mirroring the existing two (Tasks 8 and 11 — three pairs, because the two verdicts share a
shape), `NotificationTexts` extended (Task 13), the subscribable vocabulary extended (Tasks 2–4 —
`DELIVERABLE_EVENTS`, the contract enum and the SPA's maps, which is what `NotificationSubscription`
actually reads), stable `eventId`s (minted per occurrence in Task 9, asserted in Task 17's
`theDeliveryIsKeyedOnTheOccurrence`), and `CompanyScopedEvent` context (nothing to do — the
dispatcher opens it from the envelope, which Task 14's handlers rely on and never touch). Its
acceptance gate — `./gradlew build` green, a test per notifier proving a second delivery is
suppressed, queue rows written — is Tasks 17 and 20.

**Type consistency.** `ReviewSubjectDirectory.Subject(key, title)` is the one subject shape and is
used by all three implementations (Task 6), the index (Task 12), the four renderings (Task 13) and
the rendering test (Task 16). `ReviewVerdictEvent`/`ReviewVerdictPayload` are used by both verdict
handlers. `store` returns `boolean` in Task 9 and is called that way in the same task.

**Two things this plan deliberately leaves to execution.** Task 17's helpers are named and their
source is named, but not written out — copying `NotificationDeliveryTest`'s seeding verbatim into
this document would be four hundred lines of duplication that will have drifted by the time it is
read. Task 18's handoff prose is scoped, not drafted, because it must state the gate numbers that
were actually measured.
