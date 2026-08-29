/**
 * The plan detail form's draft — everything the page holds between opening and Save.
 *
 * ## Why the state lives here and not in the tabs
 *
 * §97 makes the plan detail **one form with seven tabs**, not seven forms. A tab that owned its own
 * state would lose it the moment the reader clicked another tab (the old screen unmounted the
 * inactive one on purpose, arguing that a draft surviving a tab switch is a draft the author cannot
 * see they made) — and under one Save that argument inverts: unmounting discards what was typed.
 * So the draft is above the tabs and the **dirty banner names which tabs are dirty**, which is what
 * makes an off-screen edit visible. The old argument was right about the risk and wrong about the
 * remedy.
 *
 * This module is deliberately **pure and React-free**: the dirty computation and the payload
 * assembly are the two things that decide whether a save is correct, and both are testable without
 * rendering a tab.
 */
import type { CriterionInput, TestPlanUpdateInput } from '../../api/queries';
import { newIntentKey } from '../../api/client';
import type { PlanSectionInput, TestPlan } from '../../api/types';

/**
 * The seven fixed tabs (plan §3), in order.
 *
 * Fixed and typed rather than derived from content: a plan with no artefacts still has an artefacts
 * tab, because a tab that appears when a plan acquires its first artefact is a feature nobody
 * discovers.
 */
export type PlanTabId =
  | 'progress'
  | 'plan'
  | 'requirements'
  | 'artefacts'
  | 'scope'
  | 'outOfScope'
  | 'cases';

/**
 * `satisfies Record<PlanTabId, string>` for `lib/labels`' reason — an eighth tab is a compile error
 * here rather than a strip that silently omits it.
 */
export const planTabLabels = {
  progress: 'Progress',
  plan: 'Plan',
  requirements: 'Business requirements',
  artefacts: 'Test artefacts',
  scope: 'Scope',
  outOfScope: 'Out of scope',
  cases: 'Test cases',
} as const satisfies Record<PlanTabId, string>;

export const PLAN_TABS = Object.keys(planTabLabels) as PlanTabId[];

/**
 * A criterion as the form holds it — the threshold is a **string** while it is being typed.
 *
 * A `number` bound to a text input cannot represent "the user has cleared the box", and coercing an
 * empty box to 0 is exactly the gate the server refuses: "pass_rate >= 0" is met by every release
 * that ever runs and reads on screen like something somebody meant.
 *
 * Moved here from `PlanEditDialog` in §97 phase 4 rather than copied: the dialog is deleted in
 * phase 5 and the Plan tab inherits these rows, so a second implementation would be the one that
 * survives.
 */
export interface CriterionRow {
  /**
   * Identity for React's benefit only — never sent, never compared, never round-tripped.
   *
   * A criterion has no server id until the plan is saved, and the rows are edited in place and
   * removed by position. Keyed by array index, deleting the middle row of three makes React reuse
   * the deleted row's inputs for its successor, so the threshold the author typed reappears
   * against a metric they never chose.
   */
  rowId: string;
  metric: string;
  op: string;
  value: string;
  label: string;
}

let nextRowId = 0;

/** A fresh row identity. Monotonic per tab, which is all a React key has to be. */
export function criterionRowId(): string {
  nextRowId += 1;
  return `criterion-${nextRowId}`;
}

export function criterionRows(criteria: TestPlan['exitCriteria']): CriterionRow[] {
  return (criteria ?? []).map((criterion) => ({
    rowId: criterionRowId(),
    metric: criterion.metric,
    op: criterion.op,
    value: String(criterion.value),
    // The server DERIVES a label when none was authored ("pass_rate >= 95"), so a round trip would
    // otherwise turn every generated label into a stored one — and then editing the threshold would
    // leave the old number frozen in the text beside it.
    label:
      criterion.label === `${criterion.metric} ${criterion.op} ${criterion.value}`
        ? ''
        : (criterion.label ?? ''),
  }));
}

/** Drops rows the user started and left blank; `met`/`actual` are never sent — the server owns them. */
export function criterionPayload(rows: CriterionRow[]): CriterionInput[] {
  return rows
    .filter((row) => row.metric !== '' && row.value.trim() !== '')
    .map((row) => ({
      metric: row.metric,
      op: row.op,
      value: Number(row.value),
      label: row.label.trim() || null,
    }));
}

/**
 * A section as the form holds it — the references the author wrote, never the cases they resolve to.
 *
 * `caseCount` is served beside them and is **not** carried: it is what the references currently
 * catch, recomputed on every read, and a form that round-tripped it would be sending the server its
 * own arithmetic back as an instruction.
 */
export interface DraftSection {
  name: string;
  caseIds: string[];
  suiteIds: string[];
  tags: string[];
}

/**
 * A case the author is writing on the Test cases tab, before anything has been created.
 *
 * **`intentKey` is per ROW and that is the whole point.** `useCreateCase` mints one key per mounted
 * editor because one editor writes one case; this form writes N in a loop, so a single key would
 * make the server read cases 2…N as *retries of case 1* and the plan would come back holding one
 * case where the author typed five. Minted when the row appears rather than at submit, so pressing
 * Save again after a timeout re-sends the same key per row and creates no duplicates — which is the
 * only reason the key exists.
 */
export interface NewCaseRow {
  /** Client-side identity. Never sent; it is what pairs a created id back to the row that asked. */
  rowId: string;
  intentKey: string;
  title: string;
  /** The section this row was authored under, or null for the unsectioned group. */
  section: string | null;
}

export function blankCaseRow(section: string | null = null): NewCaseRow {
  return { rowId: newIntentKey(), intentKey: newIntentKey(), title: '', section };
}

/**
 * The rows that will actually be created — **trimmed, then dropped if empty**.
 *
 * Both halves matter and the order is the point, and this is §94's refine defect in its second
 * home: the list always ends in a blank row, so any "this section has cases" or "the plan is not
 * empty" check written against `newCases.length` is satisfied for ever by a row nobody typed in.
 * Every such question goes through this function.
 */
export function liveCaseRows(rows: NewCaseRow[]): NewCaseRow[] {
  return rows
    .map((row) => ({ ...row, title: row.title.trim() }))
    .filter((row) => row.title !== '');
}

export interface PlanDraft {
  name: string;
  description: string;
  milestoneName: string;
  milestoneDate: string;
  entryCriteria: CriterionRow[];
  exitCriteria: CriterionRow[];
  scopeText: string;
  outOfScopeText: string;
  suiteIds: string[];
  caseIds: string[];
  tags: string[];
  sections: DraftSection[];
  newCases: NewCaseRow[];
}

/**
 * Seeds the draft from the plan as served.
 *
 * Nulls become `''` because they are bound to text inputs, and `''` becomes null again on the way
 * out — the round trip is lossless in both directions, which is what stops a save from writing
 * `""` into a column whose "no value" is NULL.
 */
export function draftFromPlan(plan: TestPlan): PlanDraft {
  return {
    name: plan.name,
    description: plan.description ?? '',
    milestoneName: plan.milestoneName ?? '',
    milestoneDate: plan.milestoneDate ?? '',
    entryCriteria: criterionRows(plan.entryCriteria),
    exitCriteria: criterionRows(plan.exitCriteria),
    scopeText: plan.scopeText ?? '',
    outOfScopeText: plan.outOfScopeText ?? '',
    suiteIds: [...plan.suiteIds],
    caseIds: [...plan.caseIds],
    tags: [...plan.tags],
    sections: plan.sections.map((section) => ({
      name: section.name,
      caseIds: [...section.caseIds],
      suiteIds: [...section.suiteIds],
      tags: [...section.tags],
    })),
    newCases: [],
  };
}

/** Order-insensitive for id lists: reordering a set of suite ids is not an edit. */
function sameSet(a: readonly string[], b: readonly string[]): boolean {
  if (a.length !== b.length) return false;
  const left = [...a].sort();
  const right = [...b].sort();
  return left.every((value, index) => value === right[index]);
}

function sameCriteria(a: CriterionRow[], b: CriterionRow[]): boolean {
  return (
    a.length === b.length
    && a.every((row, index) =>
      row.metric === b[index].metric
      && row.op === b[index].op
      && row.value === b[index].value
      && row.label === b[index].label)
  );
}

/** Ordered, unlike the id lists: a section's position is authored and is part of what it means. */
function sameSections(a: DraftSection[], b: DraftSection[]): boolean {
  return (
    a.length === b.length
    && a.every((section, index) =>
      section.name === b[index].name
      && sameSet(section.caseIds, b[index].caseIds)
      && sameSet(section.suiteIds, b[index].suiteIds)
      && sameSet(section.tags, b[index].tags))
  );
}

/**
 * Which tabs hold unsaved work, in tab order.
 *
 * **Three tabs can never appear here and each for its own reason.** *Progress* is a report and holds
 * no draft. *Business requirements* is a linked page — adopting and releasing it are their own
 * requests against `doc:edit` (§97 phase 3c), and its content is edited at `/doc-pages/{pageId}`, so
 * nothing about it waits for this Save. *Test artefacts* is the same shape: the register writes
 * itself. A banner that named them would be telling the author to press a button that writes
 * nothing of theirs.
 */
export function dirtyTabs(draft: PlanDraft, plan: TestPlan): PlanTabId[] {
  const original = draftFromPlan(plan);
  const dirty: PlanTabId[] = [];

  if (
    draft.name !== original.name
    || draft.description !== original.description
    || draft.milestoneName !== original.milestoneName
    || draft.milestoneDate !== original.milestoneDate
    || !sameCriteria(draft.entryCriteria, original.entryCriteria)
    || !sameCriteria(draft.exitCriteria, original.exitCriteria)
  ) {
    dirty.push('plan');
  }

  if (draft.scopeText !== original.scopeText) dirty.push('scope');
  if (draft.outOfScopeText !== original.outOfScopeText) dirty.push('outOfScope');

  if (
    !sameSet(draft.suiteIds, original.suiteIds)
    || !sameSet(draft.caseIds, original.caseIds)
    || !sameSet(draft.tags, original.tags)
    || !sameSections(draft.sections, original.sections)
    // Non-blank rows only. The trailing blank row is permanent, so `newCases.length` would report
    // every plan detail page in the product as dirty from the moment it rendered.
    || liveCaseRows(draft.newCases).length > 0
  ) {
    dirty.push('cases');
  }

  return dirty;
}

/**
 * The **groups** a case reference can sit in: the unsectioned one, then the sections in author
 * order. `null` is not a section with an empty name — it is the absence of one, and it is what every
 * plan created before §97 holds.
 */
export type GroupId = string | null;

export function groupIds(draft: PlanDraft): GroupId[] {
  return [null, ...draft.sections.map((section) => section.name)];
}

/** The new-case rows authored under one group, in the order they were written. */
export function caseRowsIn(draft: PlanDraft, group: GroupId): NewCaseRow[] {
  return draft.newCases.filter((row) => row.section === group);
}

/**
 * Guarantees **every group ends in a blank row**, which is what makes "add a case" a place to type
 * rather than a button (§94's step list, and §97 §5's first fill mechanism).
 *
 * Returns the **same object** when the invariant already holds, so a component may call it from an
 * effect without looping. Idempotent by construction: a group whose last row is already blank is
 * left alone.
 *
 * It has to be re-run after a save reseeds the draft and after a section is added, which is exactly
 * why it is a function of the whole draft rather than a step inside one operation.
 */
export function ensureTrailingBlank(draft: PlanDraft): PlanDraft {
  const missing = groupIds(draft).filter((group) => {
    const rows = caseRowsIn(draft, group);
    return rows.length === 0 || rows[rows.length - 1].title.trim() !== '';
  });
  if (missing.length === 0) return draft;
  return { ...draft, newCases: [...draft.newCases, ...missing.map((group) => blankCaseRow(group))] };
}

/**
 * Types into one row, **appending the group's next blank row when this one was its last**.
 *
 * Guarded on being last rather than on "this row was empty a moment ago", which is §94's exact
 * wording and its reason: one keystroke makes the row non-last, so the second keystroke cannot
 * append a second time and a paste appends exactly once.
 */
export function typeInCaseRow(draft: PlanDraft, rowId: string, title: string): PlanDraft {
  const next = {
    ...draft,
    newCases: draft.newCases.map((row) => (row.rowId === rowId ? { ...row, title } : row)),
  };
  const row = draft.newCases.find((entry) => entry.rowId === rowId);
  if (!row) return next;

  const rows = caseRowsIn(draft, row.section);
  const isLast = rows[rows.length - 1]?.rowId === rowId;
  if (!isLast || title.trim() === '') return next;
  return { ...next, newCases: [...next.newCases, blankCaseRow(row.section)] };
}

/** Removes a row and puts the group's blank invitation back, so the last one cannot be deleted away. */
export function removeCaseRow(draft: PlanDraft, rowId: string): PlanDraft {
  return ensureTrailingBlank({
    ...draft,
    newCases: draft.newCases.filter((row) => row.rowId !== rowId),
  });
}

/** Appends an empty section, with its blank case row already in place. */
export function addSection(draft: PlanDraft, name: string): PlanDraft {
  return ensureTrailingBlank({
    ...draft,
    sections: [...draft.sections, { name, caseIds: [], suiteIds: [], tags: [] }],
  });
}

/**
 * Renames a section — **and moves its unsaved case rows with it**.
 *
 * `NewCaseRow.section` is the section's *name*, because that is what the server keys a section by
 * (there are no section ids to match a partial update against). So a rename that touched only the
 * section would strand every row authored under it: they would still name the old heading, and
 * `planPayload` would file the cases created from them in the **unsectioned** group — a case the
 * author put under "Navigation" quietly landing outside every section, with nothing on screen
 * saying so.
 */
export function renameSection(draft: PlanDraft, index: number, name: string): PlanDraft {
  const previous = draft.sections[index]?.name;
  if (previous === undefined) return draft;
  return {
    ...draft,
    sections: draft.sections.map((section, i) => (i === index ? { ...section, name } : section)),
    newCases: draft.newCases.map((row) =>
      row.section === previous ? { ...row, section: name } : row),
  };
}

/** Moves a section one place up or down. Out-of-range is a no-op, so the end buttons need no guard. */
export function moveSection(draft: PlanDraft, index: number, delta: -1 | 1): PlanDraft {
  const target = index + delta;
  if (index < 0 || target < 0 || index >= draft.sections.length || target >= draft.sections.length) {
    return draft;
  }
  const sections = [...draft.sections];
  [sections[index], sections[target]] = [sections[target], sections[index]];
  return { ...draft, sections };
}

/**
 * What happens to a deleted section's references. **Asked, never guessed** (§97 §5): the two answers
 * differ by whether the cases stay in the plan at all, and a delete that silently chose one of them
 * would either lose scope or leave it behind, with the same confirmation either way.
 */
export type SectionDeletion = 'unsection' | 'remove';

export function removeSection(
  draft: PlanDraft,
  index: number,
  disposition: SectionDeletion,
): PlanDraft {
  const section = draft.sections[index];
  if (!section) return draft;

  const sections = draft.sections.filter((_, i) => i !== index);
  const rows =
    disposition === 'remove'
      ? draft.newCases.filter((row) => row.section !== section.name)
      : draft.newCases.map((row) => (row.section === section.name ? { ...row, section: null } : row));

  if (disposition === 'remove') {
    return ensureTrailingBlank({ ...draft, sections, newCases: rows });
  }
  return ensureTrailingBlank({
    ...draft,
    sections,
    newCases: rows,
    // Merged as SETS: a case named in the section and also named unsectioned is one reference, and
    // a duplicate id in the payload is a row the server would have to dedupe on our behalf.
    suiteIds: [...new Set([...draft.suiteIds, ...section.suiteIds])],
    caseIds: [...new Set([...draft.caseIds, ...section.caseIds])],
    tags: [...new Set([...draft.tags, ...section.tags])],
  });
}

/** One reference, in the one shape every add and every remove goes through. */
export type Reference =
  | { kind: 'case'; id: string }
  | { kind: 'suite'; id: string }
  | { kind: 'tag'; value: string };

function withReferences(
  draft: PlanDraft,
  group: GroupId,
  change: (holder: DraftSection) => DraftSection,
): PlanDraft {
  if (group === null) {
    const updated = change({
      name: '',
      caseIds: draft.caseIds,
      suiteIds: draft.suiteIds,
      tags: draft.tags,
    });
    return {
      ...draft,
      caseIds: updated.caseIds,
      suiteIds: updated.suiteIds,
      tags: updated.tags,
    };
  }
  return {
    ...draft,
    sections: draft.sections.map((section) =>
      section.name === group ? { ...change(section), name: section.name } : section),
  };
}

/**
 * Adds a reference to one group.
 *
 * **Duplicates within a group are dropped and duplicates across groups are kept** — the second half
 * is the design, not an oversight: a case may be named directly in *Create* and arrive through a
 * suite in *Navigation*, which is why the sections' counts do not sum to the plan's total (§96's
 * lesson, one slice later).
 */
export function addReference(draft: PlanDraft, group: GroupId, reference: Reference): PlanDraft {
  return withReferences(draft, group, (holder) => {
    if (reference.kind === 'case') {
      return { ...holder, caseIds: [...new Set([...holder.caseIds, reference.id])] };
    }
    if (reference.kind === 'suite') {
      return { ...holder, suiteIds: [...new Set([...holder.suiteIds, reference.id])] };
    }
    return { ...holder, tags: [...new Set([...holder.tags, reference.value])] };
  });
}

export function removeReference(draft: PlanDraft, group: GroupId, reference: Reference): PlanDraft {
  return withReferences(draft, group, (holder) => {
    if (reference.kind === 'case') {
      return { ...holder, caseIds: holder.caseIds.filter((id) => id !== reference.id) };
    }
    if (reference.kind === 'suite') {
      return { ...holder, suiteIds: holder.suiteIds.filter((id) => id !== reference.id) };
    }
    return { ...holder, tags: holder.tags.filter((tag) => tag !== reference.value) };
  });
}

/**
 * What this draft cannot be saved as, and which tab it is on.
 *
 * **Only refusals the client can know on its own.** The server is the authority on everything else
 * and answers with a field error that lands on its control — a mirrored copy of its vocabulary here
 * would be a second rule that drifts. What is here is the one the client is certain about, because
 * `name` is the only required field on `TestPlanUpdateInput` and the payload always carries it: a
 * blank name is a 400 on every press of Save.
 *
 * Returned rather than thrown, and rendered as **the disabled Save's reason** (§31): an author who
 * cleared the name on the Plan tab and typed prose on Scope must be told which tab is stopping them,
 * not met with a button that does nothing.
 *
 * `trim()` for §94's reason, its third home in this file — whitespace is not a name.
 */
export function planProblems(draft: PlanDraft): { tab: PlanTabId; message: string }[] {
  const problems: { tab: PlanTabId; message: string }[] = [];
  if (draft.name.trim() === '') {
    problems.push({ tab: 'plan', message: 'The plan needs a name.' });
  }

  /*
    A section's NAME is its identity — the contract has no section id, so the whole list is replaced
    on every save and the server keys each one by what it is called. Two sections sharing a name are
    therefore one section on the way back, silently holding the union of both, and a nameless one is
    indistinguishable from the unsectioned group it would swallow. Both are refusals the client is
    certain about for the same reason the blank plan name is: the shape cannot be sent at all.
  */
  const names = draft.sections.map((section) => section.name.trim());
  if (names.some((name) => name === '')) {
    problems.push({ tab: 'cases', message: 'Every section needs a name.' });
  }
  const duplicate = names.find((name, index) => name !== '' && names.indexOf(name) !== index);
  if (duplicate) {
    problems.push({ tab: 'cases', message: `Two sections are called “${duplicate}”.` });
  }

  return problems;
}

/** A case this save created, paired back to the draft row that asked for it. */
export interface CreatedCase {
  rowId: string;
  id: string;
  key: string;
}

/**
 * The `PATCH /plans/{planId}` body — **only the fields whose tab is dirty**.
 *
 * Every list on that endpoint is optional and the server reads an omitted one as *leave it alone*
 * and `[]` as *clear it*. Sending the whole draft every time would therefore be a full replace
 * dressed as a patch: correct while the form can show everything, and silently destructive the
 * moment it cannot — which is the defect `PlanEditDialog` avoided by hand, one field at a time, and
 * is the reason it is computed here instead.
 *
 * `name` is on every payload because the contract requires it, not because it is always dirty.
 */
export function planPayload(
  draft: PlanDraft,
  plan: TestPlan,
  created: CreatedCase[] = [],
): TestPlanUpdateInput {
  const dirty = new Set(dirtyTabs(draft, plan));
  const payload: TestPlanUpdateInput = { name: draft.name.trim() };

  if (dirty.has('plan')) {
    payload.description = draft.description.trim() || null;
    payload.milestoneName = draft.milestoneName.trim() || null;
    // An empty date input submits '', which is not a date. Null is what "no milestone" means in the
    // column and in the contract.
    payload.milestoneDate = draft.milestoneDate.trim() || null;
    payload.entryCriteria = criterionPayload(draft.entryCriteria);
    payload.exitCriteria = criterionPayload(draft.exitCriteria);
  }

  if (dirty.has('scope')) payload.scopeText = draft.scopeText.trim() || null;
  if (dirty.has('outOfScope')) payload.outOfScopeText = draft.outOfScopeText.trim() || null;

  if (dirty.has('cases')) {
    const bySection = (section: string | null) =>
      created.filter((row) => sectionOf(draft, row.rowId) === section).map((row) => row.id);

    payload.suiteIds = draft.suiteIds;
    // The ids just created join the group they were authored in — dedup because a row's id could
    // otherwise land twice if the same case were also added explicitly.
    payload.caseIds = [...new Set([...draft.caseIds, ...bySection(null)])];
    payload.tags = draft.tags;
    payload.sections = draft.sections.map(
      (section): PlanSectionInput => ({
        // Trimmed on the way out, because `planProblems` refuses an empty and a duplicate name on
        // the TRIMMED value — sending the untrimmed one would let "Create " and "Create" through as
        // two sections after the check said they were one.
        name: section.name.trim(),
        caseIds: [...new Set([...section.caseIds, ...bySection(section.name)])],
        suiteIds: section.suiteIds,
        tags: section.tags,
      }),
    );
  }

  return payload;
}

function sectionOf(draft: PlanDraft, rowId: string): string | null {
  return draft.newCases.find((row) => row.rowId === rowId)?.section ?? null;
}
