# Where the other eleven fields live — the rail decision

**Status:** proposed, nothing implemented · **Date:** 2026-08-28 · **Owner:** frontend
**Answers:** *The cost of D1 — retiring the Fields lens* in
[`docs/plans/003-case-authoring-redesign.md`](../plans/003-case-authoring-redesign.md)
**Against:** [`assets/case-authoring-redesign.png`](assets/case-authoring-redesign.png) and
[`assets/case-authoring-redesign.html`](assets/case-authoring-redesign.html)

D1 deletes the row editor. The Gherkin document expresses five of the sixteen case-level fields
(`title`, `objective`, `tags`, the steps, preconditions as `Background:`). This document decides
where the other eleven live. Design guidance is cited from the `ui-ux-pro-max` dataset
(`data/ux-guidelines.csv`, `data/ui-reasoning.csv`) — the same dataset that drove §258 — by its row
number, e.g. **UX#117**.

---

## Verdict table

| Field | Verdict | Reason, in one line |
|---|---|---|
| `suiteId` | **RAIL** (row 1) + non-editable `Feature:` chrome in the text | It is the only field that also appears in the document, and parsing the `Feature:` line back must never move the case (`GherkinScenarios.java:619`). |
| `priority` | **RAIL** (row 2) | Four-member enum, short label, already defaulted on create (`CaseEditorPage.tsx:678`) — the cheapest possible rail row. |
| `automationStatus` | **RAIL** (row 3) | Four-member enum the server requires on every save (`CaseEdit.java:352`); it also gates two child fields, so it must be a control, not a badge. |
| `automationKey` | **RAIL**, inside the automation control's disclosure — **not its own row** | Meaningful only under `AUTOMATED` (`caseSchema.ts:245`), and it is an unbounded identifier that cannot render whole in a 280px column (UX#111, UX#113). |
| `estimatedDurationSec` | **RAIL** (row 4) | Numeric, three characters wide, already validated in the schema (`caseSchema.ts:135`). |
| `classificationValueIds` | **RAIL** (row 5), **absent entirely when every vocabulary is empty** | Finding 3 of the redesign spec: a control the project cannot use is not a choice. |
| `ownerId` | **ELSEWHERE** — `CaseDetailPage` ownership panel + the list's `SET_OWNER` bulk action | The editor never had it, and the API refuses it: create rejects `ownerId` outright (`schema.d.ts:2130`), update refuses a *change* (`CaseUpdateRequest.java:173`). Putting it in the rail is new backend work, not a restoration. |
| `lifecycleStatus` | **ELSEWHERE** — the existing `LifecycleMenu` on `CaseDetailPage.tsx:568` | It is not in `CaseSchema` and the schema's own header says why: a `<select>` bypasses the transition guards (`caseSchema.ts:11`). |
| `notAutomatableReason` | **RAIL**, inside the automation disclosure, beside `automationKey` | Conditionally required (`caseSchema.ts:241`, `CaseEdit.java:356`); it must be on screen wherever the status that requires it is settable (UX#55). The dead-end is real — see below. |
| `postconditionIds` | **RAIL** (row 6) | Gherkin emits them as comments the parser reads back as nothing, by design (`GherkinScenarios.java:686`), so the text can never author them. |
| `severity` | **REMOVE from the UI, KEEP the mirror line** | Never authored, never displayed — `toCasePayload` sets `severity: data.priority` (`caseSchema.ts:434`) and no frontend file reads the field. Deleting the mirror is the defect; deleting the control is not. |

Rail rows, in order: **Suite · Priority · Automation · Duration · Preconditions · Postconditions ·
Classification** — seven at most, six when the project has no classification vocabulary, and the
render draws six of the seven already. The two the plan wanted to add to the rail (`ownerId`,
`lifecycleStatus`) are refused, and the two conditional automation children are disclosed inside
row 3 rather than given rows of their own. **The rail does not grow past what is drawn.**

---

## The rail decision

### It is an editing surface. The render's label is wrong.

The render calls it *"a read-only summary you click to change"*
(`assets/case-authoring-redesign.html:314`) and builds it as seven `div.kv` rows of plain text with
one chevron (`:212`–`:216`). With D1 taken, that is the only home for six required fields, and the
phrasing describes the exact pattern the dataset marks **Critical**:

- **UX#117 · Compact Control Semantics (Critical)** — *"Interactive chips need a native role,
  accessible name, state, keyboard operation and visible focus. Don't use a clickable div."* The
  render's `Priority: [Medium]` mini-chip is a clickable div.
- **UX#62 · Input Affordance** — *"Inputs should look interactive. Don't: inputs that look like
  plain text."* A summary that is secretly a form is that anti-pattern with a name on it.
- **UX#114 · Compact Label Semantics** — *"Badges communicate state; chips or tags represent values
  or actions. Choose static or interactive markup from the label's meaning and ownership."* Under
  D1 these labels are owned by the author, so they are chips, not badges.

**Decision: every rail row is a real, focusable, keyboard-operable control with a native role.** It
may be visually quiet — no filled input backgrounds, values right-aligned, borders appearing on
hover and focus — but it must never render as text that turns out to be a button. `ui-reasoning.csv`
row 16 (*Productivity Tool*) lists `constraint:keyboard-shortcuts` as a **must_have** for this
product class; a rail nobody can Tab into fails that outright.

**The failure mode this prevents:** an author sets Automation to *Not automatable*, Save is refused
with *"Say why this case cannot be automated"* (`CaseEdit.java:358`), and the field carrying the
error is inside something that reads as a summary. That is §258's rule restated — a refusal path
that runs no code is no refusal path — and it is why the rail cannot be decorative.

### What this does to the visual balance

Less than the render fears. Making the rows controls changes their *state* rendering (hover, focus,
a chevron per row), not their footprint. The document keeps the width it has in frame A. The one
concession: rows are visually quiet at rest, so the page still reads document-first; the rail
becomes obviously interactive on hover, focus and keyboard traversal (UX#28, UX#29, UX#30).

### How many rows is too many

**The dataset has no rule about row counts, and I am not inventing one.** What it has are rules
about whether a row can show its value at all, and those give the real ceiling:

- **UX#113 · Essential Text Truncation (Critical)** — distinguishing names need complete access.
- **UX#111 · Long Token Wrapping** — identifiers must not force overflow.
- **UX#115 · Chip Collection Reflow** — value collections must wrap or offer an operable `+n`.
- **UX#116 · Compact Label Overflow** — no hover-only tooltip as the sole full-value path.

So the rule is: **the face of the rail carries only values that are short and enumerable.**
Suite name, priority, automation status and duration qualify. `automationKey` does not — the
render's `RefundsIT#doubleRefund` already fills its column at the drawn width and a real one is
longer. Condition lists and classification chips do not either. Those render on the face as a
**count with a disclosure** (*"2 inherited"*, *"3 values"*, *"Automated · RefundsIT#doubleRefund"*
truncated with a full-value path) and their content lives in the opened control.

Seven rows is the number this verdict produces and it is one more than is drawn. It works because
no row is unbounded. A rail that needed ten would need grouping; this one does not, and the way to
keep it that way is to refuse rows for fields that belong elsewhere — which is what the `ownerId`
and `lifecycleStatus` verdicts do.

### A properties sheet instead of a rail — costed, and rejected

The alternative: no rail; a **Properties** button (⌘/Ctrl+I) opening a right-hand sheet, document at
full width.

| | Rail | Sheet |
|---|---|---|
| Width returned to the document | 0 | ~280px |
| Actions to change priority | 1 | 3 (open, change, close) |
| A conditionally-required field's error | on screen | **behind a closed surface** |
| Corpus panel (Phase 1) | already there | needs its own home — a second secondary surface |
| Focus/Escape/restore machinery | none | required (UX#28, app-interface#11) |
| Suite visible while writing | yes | no |

Two things decide it:

1. **The width is not needed.** At 1280px, frame A's document column is roughly 700px beside the
   rail. UX#21 and UX#112 want a measure of 65–75ch; at the render's monospace size that is
   ~640–700px. **The rail is not stealing width the document would use well** — a full-width
   document would exceed the readable measure, not reach it. *(Arithmetic off the render, not a
   browser observation — see Open questions.)*
2. **`notAutomatableReason` cannot be behind a closed door.** UX#55 puts the error on the field;
   UX#109 allows a summary only *in addition to* inline errors. A sheet forces the summary-only
   shape the guideline names as the anti-pattern, and Phase 1 puts the standing corpus panel in the
   rail anyway — so the sheet does not remove the second column, it adds a third surface.

**Rail. Not a sheet.** A sheet becomes worth revisiting only if the rail is ever asked to carry
something unbounded, which this verdict is designed to prevent.

---

## Per-field detail

### `notAutomatableReason` — is the unsavable-form fear real?

**Half real, and worth stating precisely because the mitigation is not the obvious one.**

- The refinement is real and it is the server's own rule: `caseSchema.ts:241` mirrors
  `CaseEdit.java:356`, and `automationStatus` is sent on every save unconditionally
  (`CaseUpdateRequest.java:201` takes `sent.automationStatus()` with no `body.has` guard).
- **But `PATCH` is a merge over present keys.** `notAutomatableReason` is inherited from the stored
  case when the body omits it (`CaseUpdateRequest.java:203`). A case that is already
  `NOT_AUTOMATABLE` with a reason on file therefore stays savable with no control at all — it
  round-trips its own stored value.
- **The dead-end is created by the control, not by its absence.** It appears the moment the author
  can *select* `NOT_AUTOMATABLE` without a reason box: `toCasePayload` then sends
  `notAutomatableReason: null` (`caseSchema.ts:439`) and both ends refuse the save with no field on
  screen to fix.

So the verdict is not "add a row because the form would be unsavable". It is: **the status control
and its two children are one control.** Today's editor already does this — the key box renders only
under `AUTOMATED` and the reason box only under `NOT_AUTOMATABLE` (`CaseEditorPage.tsx:1940`,
`:1960`), driven by `watch('automationStatus')` at `:874`. That progressive disclosure survives D1
unchanged; it just moves inside the rail's automation popover. The rule to carry forward: **a
control that can put the form into a state only a second control can resolve must render that
second control in the same surface.**

`automationKey` has a third refusal the rail must be able to show — the server rejects a key already
taken by another case (`CaseEdit.java:365`) — which is another server sentence that needs a visible
home.

### `ownerId` — REMOVE from the authoring screen (ELSEWHERE)

**What breaks: nothing.** It is not in `CaseSchema` and no editor control exists today. The plan's
*"Not in the render. Add to the rail"* would be a new capability.

**Who notices: nobody, and adding it would need backend work first.** The create endpoint refuses
`ownerId` because naming someone else needs a membership check (`schema.d.ts:2130`); the update path
lists it among the fields it refuses a *change* to (`CaseUpdateRequest.java:173`). Ownership is
already answered on two surfaces: read-only on the case (`CaseDetailPage.tsx:477`, under an
*Ownership* panel at `:470`) and assignable via `SET_OWNER` in the bulk dialog
(`BulkActionDialog.tsx:417`), which works on a selection of one.

**Code that refuses to work without it: none.** The editor has never sent it.

### `lifecycleStatus` — REMOVE from the authoring screen (ELSEWHERE)

**What breaks: nothing, and adding it would break something.** `caseSchema.ts:11` records the
decision: *"Status moves through explicit review and approval actions with their own side effects,
so it is not an editable dropdown in the editor — a `<select>` would bypass the transition guards
entirely."* The guarded surface exists: `caseMoves` at `CaseDetailPage.tsx:111`, rendered as the
permission-gated `LifecycleMenu` at `:568`, plus `SET_STATUS` / `SUBMIT_FOR_REVIEW` in the bulk
dialog (`BulkActionDialog.tsx:99`). Create always writes `DRAFT` regardless of input
(`schema.d.ts:2130`).

**Who notices:** nobody today. If it were added, the person who notices is whoever approves a case
they wrote by choosing *Approved* from a dropdown.

### `severity` — REMOVE the control, KEEP the mirror

**Confirmed.** `toCasePayload` writes `severity: data.priority` (`caseSchema.ts:434`). Nothing in
`frontend/src` reads a case's `severity` for display — the only matches are the fixture at
`test/fixtures.ts:170` and MUI `Alert severity`, which is an unrelated prop.

**One trap.** Do not "simplify" by dropping `severity` from the payload. `CaseUpdateRequest.java:200`
keeps the stored value when the key is absent, so an omitted `severity` would leave the old value
behind while `priority` changed — two fields that are supposed to agree, silently disagreeing, with
no screen showing either. The field is invisible; the mirror is load-bearing.

### `suiteId` — one field, two renderings, only one of them editable

The rail row is the editable one. The `Feature:` line is chrome: it renders the **suite name**
(`GherkinScenarios.java:619`), falling back to the case key for an unfiled case, and the plan's C3
already names retyping it as a trap. Render it outside the editable text region — a header strip
above the writing surface, visually part of the document, structurally not part of it. Changing the
suite from the rail updates that line; typing into the line is not possible.

Note the existing editor already treats a suite change as a move worth flagging
(`CaseEditorPage.tsx:895`, `:1048`), and that behaviour should survive into the rail control.

### `postconditionIds` — a rail row, and it is the one genuinely new row

Preconditions are `Background:` and round-trip. Postconditions leave as comments the parser reads
back as nothing, deliberately (`GherkinScenarios.java:686`). So the text is a *lossy but honest*
view of them: a human reading the `.feature` sees the cleanup, and a parse never invents steps from
it. The editable home is therefore the rail, mirroring the precondition row and reusing the picker
already wired at `CaseEditorPage.tsx:1381`. Face value is a count; the list opens.

---

## The blank state (frame B) has no rail — how a new case gets a suite

The render answers this with `@suite:refunds` as a tag chip above the title. That answer is
**Phase 3 and blocked**: `case_tag` is free text, `suiteId` is a foreign key, and the plan prices
the namespace problem in full. So the syntax goes, but the render's *intent* is right and is kept:
**the suite comes from context, not from an empty select.**

1. **The rail is present on the blank state too**, carrying the same seven rows with defaults —
   `NEW_CASE_DEFAULTS` already answers priority, automation status and step format
   (`CaseEditorPage.tsx:678`), and `suiteId` defaults to `''`, which means *unfiled*, not *missing*
   (`caseSchema.ts:124`, `CaseEditorPage.tsx:671`). Nothing is required to create a case but a title
   and a step.
2. **The Suite row is prefilled from where the author pressed New.** This does not work today:
   `CaseListPage.tsx:98` and `:247` navigate to `/projects/:projectId/cases/new` with no suite,
   even when a suite is selected in the list. Carrying it as a query parameter is a small change and
   it is the one that makes the blank state's rail correct rather than empty. **UX#106 (Redundant
   Entry)** is the rule: do not ask for what the process already knows.
3. **Rank, don't remove.** The three starts own the document column; the rail sits beside them,
   quiet, showing the defaults. An author who wants none of it types and saves.
4. Owner and lifecycle are absent from the blank state for the same reason they are absent
   everywhere in this editor — the API decides both on create (`schema.d.ts:2130`).

---

## What this costs

- **The rail stops being quiet furniture.** Seven interactive rows beside a writing surface is
  visual noise the render does not have, and the render is prettier for it. The mitigation — quiet
  at rest, obvious on hover and focus — is a claim about a screen nobody has opened.
- **Two fields move further from the author than the plan proposed.** Setting an owner now means
  leaving the editor for the case detail page or the list's bulk dialog. That is worse for anyone
  who expected to do it while writing, and the honest answer is that it was already worse and this
  document declines to fix it here.
- **Automation becomes a two-level control.** Status on the face, key or reason one level in. That
  is one more click for the only field on the screen with a conditional requirement, and a place a
  validation error can hide if the popover closes on refusal. It must not. *(Built in 007 T7 as an
  inline disclosure held open by its own refusal, so the hazard cannot occur — open question 2.)*
- **The `Feature:` line is chrome that looks like text.** An author will try to edit it. Whatever
  makes it non-editable has to also explain itself, or it reads as a bug.
- **Seven rows leaves no headroom.** The next field anyone adds to `test_case` has nowhere obvious
  to go, and the answer will have to be grouping or the sheet this document rejected.

---

## Open questions

1. ~~**The width arithmetic is arithmetic.**~~ **Answered by the build (007 T7), and the answer is
   the same one — with the arithmetic now done against shipped numbers rather than a drawing's
   proportions.**

   At 1280px the case editor is one of the three routes where the shell forces the collapsed rail
   (`AppShell.tsx`'s `COLLAPSED_RAIL_ROUTES`, 007 T5), so the row is **64px nav + 300px rail**,
   leaving **916px** for the document column; its own padding is 32px a side, so the text box is
   **852px**. The procedure is 15px IBM Plex Sans, whose average advance is ≈0.5em, so the 74ch
   measure UX#21/UX#112 want is ≈555px and its 75ch ceiling ≈565px. **The document reaches its
   measure with roughly 290px to spare, which is more than the rail costs.** A full-width document
   would not reach the measure, it would exceed it by 50%.

   So the rail is not stealing width the document would use well, the sheet does not come back, and
   the `max-w-[74ch]` on the document column is what holds the line — remove it and the column, not
   the rail, is what breaks the measure.

   **Still not a browser observation.** This is arithmetic over the widths the implementation
   actually uses, taken at 1280px with no case open; it is not a screenshot, and it says nothing
   about a fifty-step case, about a suite name long enough to wrap the Suite row, or about the
   `sm`/`md` stack where the rail sits under the document rather than beside it. Those stay open.
2. ~~**Does the automation popover stay open on a server refusal?**~~ **Answered by the build
   (007 T7): there is no popover.** The row opens an **inline disclosure** in the rail, and the
   question disappears with the surface that raised it — a popover is dismissed by a click outside
   it and an inline panel has nothing to dismiss it. The disclosure's open state is *derived*:
   `errors.automationKey || errors.notAutomatableReason` holds it open, so it cannot be closed
   while its own refusal stands. See `frontend/src/features/cases/CaseEditorPage.tsx`.
3. **Is the suite-from-context parameter in Phase 1 or Phase 2?** It is a `CaseListPage` change with
   no contract impact, so Phase 1 is cheap — but the render puts it on the blank state, which is
   Phase 1's deliverable anyway. Named, not assigned.
4. **Should the rail's classification row disappear or grey out when every vocabulary is empty?**
   Finding 3 says render no control; UX#31 (Disabled States) and the project's own
   *disable-don't-hide* leaning point the other way. This document takes the spec's side — a
   vocabulary that does not exist is not a disabled choice, it is not a choice — but it is a real
   disagreement and it is the second time it has come up.

---

## Does the render need redrawing?

**Yes, in four places. Everything else stands.**

1. **Delete the `Write | Fields` toggle** in both frames. D1 killed it and the plan already says so.
2. **Redraw the rail rows as controls.** They are `div.kv` spans today
   (`assets/case-authoring-redesign.html:212`–`:216`). Every row needs a visible affordance — the
   chevron currently drawn on Suite alone — and the caption at `:314` must stop saying *"read-only
   summary"*.
3. **Add a Postconditions row** beneath Preconditions, drawn as a count.
4. **Frame B needs the rail**, with defaults and a context-filled Suite — and its `@suite:refunds`
   chip must come out of the tag row, because that syntax is Phase 3 and blocked.

Not redrawn, and deliberately: the tag row (today's free-text tags, exactly as drawn), the corpus
panel, the empty-classification sentence, and the *Automation · RefundsIT#doubleRefund* row — which
stays as drawn on the face, with the caveat that it is the row most likely to truncate and needs a
full-value path (UX#113, UX#116).
