import { describe, expect, it } from 'vitest'
import type { TestPlan } from '../../api/types'
import {
  addReference,
  addSection,
  blankCaseRow,
  caseRowsIn,
  dirtyTabs,
  draftFromPlan,
  ensureTrailingBlank,
  liveCaseRows,
  moveSection,
  planPayload,
  planProblems,
  removeCaseRow,
  removeReference,
  removeSection,
  renameSection,
  typeInCaseRow,
  type PlanDraft,
} from './planDraft'

/**
 * The plan detail form's draft — **the two computations that decide whether a save is correct**,
 * tested without rendering a tab.
 *
 * What is locked here, none of it visible to `tsc` or oxlint:
 *
 *   1. **The trailing blank row does not make the form dirty.** The Test cases tab always ends in
 *      an empty row, so a dirty check written against `newCases.length` would show the banner on
 *      every plan detail page in the product from the moment it rendered — and `useUnsavedWork`
 *      would prompt on every navigation away from one. §94's "at least one step" refine defect,
 *      one slice and one screen later.
 *   2. **The payload carries only the dirty tabs' fields.** Every list on `PATCH /plans` is
 *      three-state — absent means *leave it alone*, `[]` means *clear it* — so a form that sent its
 *      whole draft would be a full replace dressed as a patch, and would empty whatever half of the
 *      scope it could not display. `PlanEditDialog` avoided this by hand, one field at a time;
 *      here it is computed, and this is the check behind it.
 *   3. **A created case lands in the group it was authored in.** A row typed under "Navigation"
 *      whose id arrives in the unsectioned list is a case in the plan and absent from the section
 *      the author put it in — which looks like nothing at all on screen.
 *   4. **Two prose tabs are two tabs.** Editing Scope must not mark Out of scope dirty; the banner
 *      names tabs, and a banner that names the wrong one sends the author looking for an edit they
 *      did not make.
 */

function planBody(overrides: Partial<TestPlan> = {}): TestPlan {
  return {
    id: 'tp1',
    key: 'TP-4',
    name: 'Release 4.4',
    status: 'DRAFT',
    milestoneName: null,
    milestoneDate: null,
    caseCount: 3,
    passRate: null,
    coverage: null,
    description: null,
    entryCriteria: [],
    exitCriteria: [],
    configurations: [],
    suiteIds: ['suite-1'],
    caseIds: ['case-9'],
    tags: [],
    sections: [],
    scopeText: null,
    outOfScopeText: null,
    currentVersionNo: 1,
    lockVersion: 7,
    ...overrides,
  } as unknown as TestPlan
}

/** A draft with one filled-in case row under `section`, plus the permanent trailing blank. */
function withNewCase(plan: TestPlan, title: string, section: string | null = null): PlanDraft {
  const draft = draftFromPlan(plan)
  const row = { ...blankCaseRow(section), title }
  return { ...draft, newCases: [row, blankCaseRow(section)] }
}

describe('dirtyTabs', () => {
  it('reports nothing dirty for a draft straight off the plan', () => {
    const plan = planBody()
    expect(dirtyTabs(draftFromPlan(plan), plan)).toEqual([])
  })

  it('does not count the trailing blank row', () => {
    const plan = planBody()
    const draft: PlanDraft = { ...draftFromPlan(plan), newCases: [blankCaseRow(), blankCaseRow()] }

    // Two rows in the draft, no cases. Written as `newCases.length > 0` this reads dirty and the
    // banner never goes away.
    expect(dirtyTabs(draft, plan)).toEqual([])
    expect(liveCaseRows(draft.newCases)).toHaveLength(0)
  })

  it('counts a row whose title is only whitespace as blank', () => {
    const plan = planBody()
    const draft = withNewCase(plan, '   ')
    expect(dirtyTabs(draft, plan)).toEqual([])
  })

  it('names the tab the edit was made on, and only that tab', () => {
    const plan = planBody()
    const draft: PlanDraft = { ...draftFromPlan(plan), scopeText: 'Payments and refunds.' }

    expect(dirtyTabs(draft, plan)).toEqual(['scope'])
  })

  it('names several tabs at once, in tab order', () => {
    const plan = planBody()
    const draft: PlanDraft = {
      ...draftFromPlan(plan),
      name: 'Release 4.5',
      outOfScopeText: 'Nothing on mobile.',
      scopeText: 'Payments.',
    }

    expect(dirtyTabs(draft, plan)).toEqual(['plan', 'scope', 'outOfScope'])
  })

  it('ignores the order of an id list', () => {
    const plan = planBody({ suiteIds: ['suite-1', 'suite-2'] } as Partial<TestPlan>)
    const draft: PlanDraft = { ...draftFromPlan(plan), suiteIds: ['suite-2', 'suite-1'] }

    // Reordering a set of suite ids is not an edit, and reporting it as one would make Save write a
    // plan version whose diff says nothing.
    expect(dirtyTabs(draft, plan)).toEqual([])
  })

  it('never names Progress, Business requirements or Test artefacts', () => {
    const plan = planBody()
    const draft: PlanDraft = {
      ...draftFromPlan(plan),
      name: 'x',
      scopeText: 'y',
      outOfScopeText: 'z',
      suiteIds: [],
      newCases: [blankCaseRow()],
    }

    // Everything this form can edit, dirty at once. The three tabs whose writes are their own
    // requests must still be absent — a banner naming them would tell the author to press a button
    // that writes nothing of theirs.
    const dirty = dirtyTabs(draft, plan)
    expect(dirty).not.toContain('progress')
    expect(dirty).not.toContain('requirements')
    expect(dirty).not.toContain('artefacts')
  })
})

describe('planPayload', () => {
  it('sends only the name when nothing is dirty', () => {
    const plan = planBody()
    // `name` is required by the contract on every PATCH; everything else absent means "leave alone".
    expect(planPayload(draftFromPlan(plan), plan)).toEqual({ name: 'Release 4.4' })
  })

  it('omits the scope lists when only the prose changed', () => {
    const plan = planBody()
    const body = planPayload({ ...draftFromPlan(plan), scopeText: 'Payments.' }, plan)

    expect(body.scopeText).toBe('Payments.')
    // The load-bearing assertion. `suiteIds: []` here would clear a scope this edit never touched,
    // and the request succeeds either way — the loss is only visible in the database.
    expect('suiteIds' in body).toBe(false)
    expect('caseIds' in body).toBe(false)
    expect('sections' in body).toBe(false)
    expect('outOfScopeText' in body).toBe(false)
  })

  it('sends null rather than an empty string when prose is cleared', () => {
    const plan = planBody({ scopeText: 'Payments.' } as Partial<TestPlan>)
    const body = planPayload({ ...draftFromPlan(plan), scopeText: '  ' }, plan)

    // '' is a value in a text column and NULL is the absence of one; the round trip has to be
    // lossless in both directions or a cleared field comes back as an empty paragraph.
    expect(body.scopeText).toBeNull()
  })

  it('adds a created case to the unsectioned group it was authored in', () => {
    const plan = planBody()
    const draft = withNewCase(plan, 'Refund a captured payment')
    const created = [{ rowId: draft.newCases[0].rowId, id: 'case-new', key: 'TC-40' }]

    const body = planPayload(draft, plan, created)

    expect(body.caseIds).toEqual(['case-9', 'case-new'])
    expect(body.sections).toEqual([])
  })

  it('adds a created case to the section it was authored under, not to the plan', () => {
    const plan = planBody({
      sections: [{ name: 'Navigation', position: 0, caseIds: [], suiteIds: [], tags: [], caseCount: 0 }],
    } as Partial<TestPlan>)
    const draft = withNewCase(plan, 'Open the ledger', 'Navigation')
    const created = [{ rowId: draft.newCases[0].rowId, id: 'case-new', key: 'TC-41' }]

    const body = planPayload(draft, plan, created)

    expect(body.sections).toEqual([
      { name: 'Navigation', caseIds: ['case-new'], suiteIds: [], tags: [] },
    ])
    // And NOT in the unsectioned group as well: a case in both is one the section owns and the
    // plan also names, which is legal and is not what the author asked for.
    expect(body.caseIds).toEqual(['case-9'])
  })

  it('never sends a section’s caseCount back', () => {
    const plan = planBody({
      sections: [
        { name: 'Create', position: 0, caseIds: ['case-1'], suiteIds: [], tags: ['smoke'], caseCount: 12 },
      ],
    } as Partial<TestPlan>)
    const draft = draftFromPlan(plan)
    const body = planPayload({ ...draft, sections: [{ ...draft.sections[0], name: 'Create an object' }] }, plan)

    // `caseCount` is what the references currently catch, recomputed on every read. Sending it back
    // would be handing the server its own arithmetic as an instruction.
    expect(body.sections?.[0]).not.toHaveProperty('caseCount')
    expect(body.sections?.[0].tags).toEqual(['smoke'])
  })

  /**
   * The one refusal the client is certain about on its own (§97 phase 5).
   *
   * `name` is the only required field on `TestPlanUpdateInput` and the payload always carries it,
   * so a blank one is a 400 on every press of Save — which makes it the one thing worth disabling
   * the button for. Everything else is the server's, and a mirrored copy of its vocabulary here
   * would be a second rule that drifts.
   */
  describe('planProblems', () => {
    it('finds nothing wrong with an ordinary draft', () => {
      expect(planProblems(draftFromPlan(planBody()))).toEqual([])
    })

    it('names the Plan tab when the name has been cleared', () => {
      const draft = { ...draftFromPlan(planBody()), name: '' }
      expect(planProblems(draft)).toEqual([{ tab: 'plan', message: 'The plan needs a name.' }])
    })

    it('does not accept whitespace as a name', () => {
      // §94's trim defect, third home in this file: without the trim the server answers 400 for
      // something the client could see, and the author is told by a round trip.
      const draft = { ...draftFromPlan(planBody()), name: '   ' }
      expect(planProblems(draft)).toHaveLength(1)
    })
  })

  /**
   * The Test cases tab's operations (§97 phase 6), tested where they are pure.
   *
   * The two that carry a defect nothing else would catch are the rename and the trailing blank.
   */
  describe('sections and their rows', () => {
    it('gives every group a trailing blank row, and is idempotent', () => {
      const draft = ensureTrailingBlank(addSection(draftFromPlan(planBody()), 'Navigation'))

      expect(caseRowsIn(draft, null)).toHaveLength(1)
      expect(caseRowsIn(draft, 'Navigation')).toHaveLength(1)
      // The SAME object when nothing is missing — the effect that calls this from a component
      // depends on it, and a fresh object every time is an infinite render loop.
      expect(ensureTrailingBlank(draft)).toBe(draft)
    })

    it('appends the next blank row only when the LAST one is typed in', () => {
      const seeded = ensureTrailingBlank(draftFromPlan(planBody()))
      const first = caseRowsIn(seeded, null)[0]

      const typed = typeInCaseRow(seeded, first.rowId, 'Open the ledger')
      expect(caseRowsIn(typed, null)).toHaveLength(2)

      // A second keystroke in the same row must NOT append again: it is no longer the last row,
      // which is the guard §94 wrote and the reason a paste appends exactly once.
      const again = typeInCaseRow(typed, first.rowId, 'Open the ledger again')
      expect(caseRowsIn(again, null)).toHaveLength(2)
    })

    it('puts the blank invitation back when the last row is removed', () => {
      const seeded = ensureTrailingBlank(draftFromPlan(planBody()))
      const typed = typeInCaseRow(seeded, caseRowsIn(seeded, null)[0].rowId, 'A case')
      const rows = caseRowsIn(typed, null)

      const removed = removeCaseRow(removeCaseRow(typed, rows[0].rowId), rows[1].rowId)

      // Without this the blank row IS the way to add a case and an author who removed it has no
      // control on screen that says so.
      expect(caseRowsIn(removed, null)).toHaveLength(1)
      expect(liveCaseRows(removed.newCases)).toEqual([])
    })

    it('MOVES a section’s unsaved rows when the section is renamed', () => {
      const withSection = addSection(draftFromPlan(planBody()), 'Navigatoin')
      const row = caseRowsIn(withSection, 'Navigatoin')[0]
      const typed = typeInCaseRow(withSection, row.rowId, 'Open the ledger')

      const renamed = renameSection(typed, 0, 'Navigation')

      // The row's `section` is the section's NAME, because that is what the server keys a section
      // by. A rename that touched only the section would strand the row under a heading that no
      // longer exists, and `planPayload` would file its case in the UNSECTIONED group — the case
      // the author put under Navigation landing outside every section, silently.
      expect(caseRowsIn(renamed, 'Navigation')).toHaveLength(2)
      expect(caseRowsIn(renamed, 'Navigatoin')).toHaveLength(0)
    })

    it('keeps a deleted section’s references when asked to, and drops them when asked to', () => {
      const plan = planBody({
        sections: [
          { name: 'Create', position: 0, caseIds: ['case-1'], suiteIds: ['suite-2'], tags: ['smoke'], caseCount: 4 },
        ],
      } as Partial<TestPlan>)

      const kept = removeSection(draftFromPlan(plan), 0, 'unsection')
      expect(kept.sections).toEqual([])
      expect(kept.caseIds).toEqual(['case-9', 'case-1'])
      expect(kept.suiteIds).toEqual(['suite-1', 'suite-2'])
      expect(kept.tags).toEqual(['smoke'])

      const dropped = removeSection(draftFromPlan(plan), 0, 'remove')
      expect(dropped.sections).toEqual([])
      // Untouched: the plan's own references are not the section's, and a delete that swallowed
      // both would cut scope the author never named.
      expect(dropped.caseIds).toEqual(['case-9'])
      expect(dropped.suiteIds).toEqual(['suite-1'])
    })

    it('reorders sections, and does nothing at the ends', () => {
      const draft = addSection(addSection(draftFromPlan(planBody()), 'A'), 'B')

      expect(moveSection(draft, 1, -1).sections.map((s) => s.name)).toEqual(['B', 'A'])
      // No guard needed at the call site: the end buttons are disabled, and a stray call is a
      // no-op rather than a swap with a section that is not there.
      expect(moveSection(draft, 0, -1)).toBe(draft)
      expect(moveSection(draft, 1, 1)).toBe(draft)
    })

    it('dedupes a reference within a group and keeps it across two', () => {
      const draft = addSection(draftFromPlan(planBody()), 'Create')

      const twice = addReference(
        addReference(draft, 'Create', { kind: 'tag', value: 'smoke' }),
        'Create',
        { kind: 'tag', value: 'smoke' },
      )
      expect(twice.sections[0].tags).toEqual(['smoke'])

      // Across groups it is KEPT — that is the design, and it is why the counts do not sum.
      const both = addReference(twice, null, { kind: 'tag', value: 'smoke' })
      expect(both.tags).toEqual(['smoke'])
      expect(both.sections[0].tags).toEqual(['smoke'])

      const gone = removeReference(both, 'Create', { kind: 'tag', value: 'smoke' })
      expect(gone.sections[0].tags).toEqual([])
      // Removing from one group leaves the other alone, or "add it to both" would be unusable.
      expect(gone.tags).toEqual(['smoke'])
    })

    it('refuses a nameless section and two sections with one name', () => {
      const nameless = addSection(draftFromPlan(planBody()), '  ')
      expect(planProblems(nameless)).toContainEqual({
        tab: 'cases',
        message: 'Every section needs a name.',
      })

      const twins = addSection(addSection(draftFromPlan(planBody()), 'Create'), 'Create')
      // A section has no id: the list is replaced whole and the server keys each one by name, so
      // two called the same thing are ONE section holding the union of both on the way back.
      expect(planProblems(twins).some((p) => p.message.includes('Two sections'))).toBe(true)
    })

    it('trims a section name on the way out, since the check trims too', () => {
      const plan = planBody()
      const draft = addSection(draftFromPlan(plan), 'Create ')
      expect(planPayload(draft, plan).sections?.[0].name).toBe('Create')
    })
  })
})
