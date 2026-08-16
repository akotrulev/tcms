import { describe, expect, it } from 'vitest'
import { fromCasePayload, toCasePayload, type CaseInput } from './caseSchema'

/**
 * `toCasePayload` and `fromCasePayload` — **the pair the draft buffer runs on** (§128).
 *
 * The autosave writes the form through `toCasePayload` and a restore reads it back through
 * `fromCasePayload`, so the two are a round trip whose failure mode is quiet and specific: a field
 * that survives the write and not the read comes back **empty**, which is indistinguishable from
 * the author never having typed it. Every request involved is individually valid, both mappings
 * type-check, and the only observer is somebody looking at their recovered draft wondering where a
 * paragraph went.
 *
 * So the load-bearing test here is `theRoundTripKeepsEveryField` — not the field-by-field ones
 * around it, which would all pass with a field missing from both halves.
 */

/** A fully-populated form value, so a field dropped by either mapping has somewhere to show up. */
const filled: CaseInput = {
  title: 'Refund a settled payment',
  suiteId: 'suite-1',
  objective: 'Prove a merchant can recover funds.',
  description: 'Refunds the whole captured amount.',
  expectedOutcome: 'The payment reaches REFUNDED.',
  priority: 'CRITICAL',
  automationStatus: 'AUTOMATED',
  automationKey: 'refunds.spec.ts:full',
  notAutomatableReason: null,
  estimatedDurationSec: '240',
  stepFormat: 'STRUCTURED',
  gherkinSource: null,
  steps: [
    {
      id: 'step-1',
      keyword: null,
      action: 'Open the refund screen',
      testData: '120.00 EUR',
      expectedResult: 'The form opens',
    },
    {
      id: null,
      keyword: null,
      action: 'Submit',
      testData: '',
      expectedResult: '',
    },
  ],
  classificationValueIds: ['cv-1', 'cv-2'],
  preconditionIds: ['cond-1', 'cond-2'],
  postconditionIds: ['cond-3'],
  // Attachment ids, both kinds in one array — §187. Populated here for this fixture's stated
  // reason: a field dropped by either mapping needs somewhere to show up.
  declinedConditionIds: ['attach-9'],
  tags: ['smoke', 'regression'],
  changeComment: 'Corrected the amount',
}

describe('the draft round trip', () => {
  it('keeps every field the form carries', () => {
    // THE test of the pair. A field wired into one mapping and forgotten in the other restores as
    // empty, and nothing anywhere fails — see the file header.
    const restored = fromCasePayload(toCasePayload(filled))

    // The trailing blank row is added back by the read, because the blank last row IS the add
    // control since §94. Compared without it, since the write drops it by design.
    expect(restored.steps.at(-1)).toEqual({
      id: null,
      keyword: null,
      action: '',
      testData: '',
      expectedResult: '',
    })
    expect({ ...restored, steps: restored.steps.slice(0, -1) }).toEqual({
      ...filled,
      steps: filled.steps,
    })
  })

  it('survives a second pass unchanged', () => {
    // Idempotence, which is what the editor actually does: restore a draft, type nothing, and the
    // next autosave writes it again. A mapping that normalized differently on the second pass
    // would make an untouched restored draft look like an edit for ever.
    const once = toCasePayload(filled)
    expect(toCasePayload(fromCasePayload(once))).toEqual(once)
  })
})

describe('toCasePayload', () => {
  it('drops blank step rows and trims the survivors', () => {
    // Trim then drop, in that order. Trimming alone sends a step whose action is "  " as a real
    // step; dropping alone keeps the padding of every pasted step.
    const payload = toCasePayload({
      ...filled,
      steps: [
        { id: null, keyword: null, action: '  Pay  ', testData: ' x ', expectedResult: '' },
        { id: null, keyword: null, action: '   ', testData: '', expectedResult: '' },
      ],
    })

    expect(payload.steps).toEqual([
      { id: null, keyword: null, action: 'Pay', testData: 'x', expectedResult: null },
    ])
  })

  it('sends an unfiled case as null rather than an empty string', () => {
    // '' is a 400 on a uuid field, and '' is what the MUI Select holds for unfiled.
    expect(toCasePayload({ ...filled, suiteId: '' }).suiteId).toBeNull()
  })

  it('sends only the chosen step format, so the other cannot go stale', () => {
    const gherkin = toCasePayload({ ...filled, stepFormat: 'GHERKIN', gherkinSource: 'Feature: x' })
    expect(gherkin.steps).toEqual([])
    expect(gherkin.gherkinSource).toBe('Feature: x')

    expect(toCasePayload(filled).gherkinSource).toBeNull()
  })

  it('sends empty collections rather than omitting them', () => {
    // The server reads an absent field as "leave it alone", so omitting `tags` when the author has
    // just removed the last one would silently do nothing. The one place where the natural empty
    // value and the absent field are different instructions.
    const payload = toCasePayload({
      ...filled,
      tags: [],
      classificationValueIds: [],
      preconditionIds: [],
      postconditionIds: [],
      declinedConditionIds: [],
    })

    expect(payload.tags).toEqual([])
    expect(payload.classificationValueIds).toEqual([])
    expect(payload.preconditions).toEqual([])
    expect(payload.postconditions).toEqual([])
  })

  it('de-duplicates and trims tags', () => {
    expect(toCasePayload({ ...filled, tags: [' smoke ', 'smoke', '', 'slow'] }).tags).toEqual([
      'smoke',
      'slow',
    ])
  })
})

describe('fromCasePayload — it decodes a draft nobody validated', () => {
  it('survives an empty object without throwing', () => {
    // The buffer is unvalidated by design and may also have been written by an older build. A
    // TypeError here would take down the one screen whose job is not losing the user's work.
    const restored = fromCasePayload({})

    expect(restored.title).toBe('')
    expect(restored.priority).toBe('MEDIUM')
    expect(restored.automationStatus).toBe('MANUAL')
    expect(restored.stepFormat).toBe('STRUCTURED')
    expect(restored.steps).toHaveLength(1)
  })

  it.each([null, undefined, 'not an object', 42])('survives %s', (content) => {
    expect(() => fromCasePayload(content)).not.toThrow()
  })

  it('falls back to the default for an unrecognised enum value', () => {
    // Binding it would render a Select with no matching option — a control that looks empty and
    // silently submits whatever was there.
    const restored = fromCasePayload({ priority: 'URGENT', automationStatus: 'SOMEDAY' })

    expect(restored.priority).toBe('MEDIUM')
    expect(restored.automationStatus).toBe('MANUAL')
  })

  it('reads a wrong-typed field as absent rather than rendering it', () => {
    // The honest outcome: the alternative is [object Object] in a title field.
    expect(fromCasePayload({ title: { nope: true }, tags: 'smoke' })).toMatchObject({
      title: '',
      tags: [],
    })
  })

  it('turns the wire duration back into the string the input holds', () => {
    expect(fromCasePayload({ estimatedDurationSec: 240 }).estimatedDurationSec).toBe('240')
    expect(fromCasePayload({ estimatedDurationSec: null }).estimatedDurationSec).toBeNull()
  })

  it('unwraps condition references back to ids, skipping malformed entries', () => {
    expect(
      fromCasePayload({ preconditions: [{ conditionId: 'c1' }, { body: 'inline' }, null] })
        .preconditionIds,
    ).toEqual(['c1'])
  })

  it('always ends the step list with a blank row', () => {
    // Without it a restored draft has no way to add a step, because the blank last row is the add
    // control.
    const restored = fromCasePayload({
      steps: [{ action: 'Only step', testData: null, expectedResult: null }],
    })

    expect(restored.steps).toHaveLength(2)
    expect(restored.steps[1].action).toBe('')
  })
})
