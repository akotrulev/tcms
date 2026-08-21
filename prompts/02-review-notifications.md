# Slice 2 — Review-workflow notifications

**Lane:** backend, small frontend · **Migration:** none · **Depends on:** nothing (can swap with 1)

---

## Prompt

```text
Implement **Slice 2 — Review-workflow notifications** from
`docs/plans/2026-08-18-unplanned-gaps.md`.

**First act: run `ep-writing-plans` on this slice alone** and save the result as
`docs/plans/2026-08-18-gap-2-review-notifications.md`. The master plan scopes this slice but does
not step it — that is deliberate and its Scope Check says why. Then execute your plan with
`ep-executing-plans`.

Read first: `docs/agent-context/be-java.md`, then the Slice 2 section of the master plan. Backend
work goes through **`/be-java`**.

**What this slice is:** the product has a full review workflow — submit, assign a reviewer,
approve, request changes — and **nobody is ever told they have been asked to review anything**.
Notifications currently fire for exactly two events, item-assigned and run-completed. Add four
notifiers: review-requested (to the named reviewer), review-approved and changes-requested (to the
submitter), and case-approved (to subscribers).

**The seam already exists and is proven twice.** Mirror
`backend/src/main/java/com/tcms/integration/ItemAssignedNotifier.java` +
`ItemAssignedPayload.java`, and `RunCompletedNotifier.java` + `RunCompletedPayload.java`. Extend
`NotificationTexts` for the copy and `NotificationSubscriptionView` for the new subscribable
events. No schema change.

**Handoff:** take the next free § from `docs/HANDOFF.md` — a new file in `docs/handoff/`, plus one
index row. Update `docs/agent-context/be-java.md` in this slice's own commit.
```

---

## The two traps this slice turns on

**1. Delivery is at-least-once, and idempotency is a unique constraint you must feed.**
`NotificationDeliveryRepository.findAttempt` looks up `(channelId, eventId)` and the unique
constraint names those columns. A new notifier that does not supply a **stable** `eventId` will
double-send on every retry — and a retry is ordinary here, not exceptional. Derive the id from the
domain event, never from a clock or a random.

**2. A handler must re-establish company context from the event envelope.**
`CompanyContextTaskDecorator` **clears** the context on every task so a handler that forgets fails
closed. The review workflow raises its events across a module boundary, so `integration` consumes
them as `CompanyScopedEvent` and opens context from the envelope's `companyId`. A handler that reads
`CompanyContext` directly gets nothing. `AsyncCompanyContextTest` covers this family — add to it.

## Standing rules that bind this slice

- **Tests run on `shanks`** — `~/bin/tcms-remote backend ./gradlew build`. Not locally.
- **A test per notifier proving a second delivery of the same event is suppressed.** That is the
  acceptance gate, not a nicety.
- **Backend mutation checks are deferred and batched (§65)** — append the rows to
  `docs/verification/backend-mutation-checks.md`, run nothing.
- **Work on the current branch**; stage only your files (`git add <paths>`, never `git add -A`).
- Notification copy is user-facing text. If Slice 9 (i18n) has already landed, route it through the
  translation layer rather than adding a bare literal.
