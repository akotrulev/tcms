import { describe, expect, it } from 'vitest'
import { CaseSchema } from './caseSchema'

/**
 * The four cross-field refinements on the case form.
 *
 * These were the one item §36 left on its own target list and §37 restated as an open follow-up,
 * and the reason they were worth coming back for is that **no field-level test can reach them**:
 * each is a rule about a *pair* of fields, so every individual field is valid in isolation and the
 * form only becomes wrong when the two are read together. Until now they were proved solely by a
 * person filling in the form, which is exactly the coverage §6 lesson 5 warns about.
 *
 * All four are enforced server-side too. That makes a divergence between the two a specific and
 * unpleasant defect — a form that passes its own validation, submits, and comes back rejected —
 * so the assertions below pin the *path* as well as the verdict: the message has to land on the
 * field that caused it, not in a banner.
 */

/** A minimally valid case: structured, one step, manual. Each test breaks exactly one thing. */
const valid = () => ({
  title: 'A payment cannot be refunded twice',
  suiteId: 'suite-1',
  objective: null,
  description: null,
  expectedOutcome: null,
  priority: 'MEDIUM' as const,
  automationStatus: 'MANUAL' as const,
  automationKey: null,
  notAutomatableReason: null,
  estimatedDurationSec: null,
  stepFormat: 'STRUCTURED' as const,
  gherkinSource: null,
  steps: [{ action: 'Refund the payment a second time' }],
  // Present and empty rather than omitted, because the two mean different things all the way down:
  // the server reads an absent field as "leave the classification alone" and an empty array as
  // "clear it", so a form value that could be undefined would make "unclassified" unexpressible.
  classificationValueIds: [],
  changeComment: null,
})

/** The `path` of every issue raised, so a test can assert *where* the message lands. */
const pathsOf = (input: unknown): string[] => {
  const result = CaseSchema.safeParse(input)
  return result.success ? [] : result.error.issues.map((issue) => issue.path.join('.'))
}

const messageOn = (input: unknown, path: string): string | undefined => {
  const result = CaseSchema.safeParse(input)
  if (result.success) return undefined
  return result.error.issues.find((issue) => issue.path.join('.') === path)?.message
}

describe('CaseSchema — the baseline', () => {
  it('accepts a minimally valid structured case', () => {
    expect(CaseSchema.safeParse(valid()).success).toBe(true)
  })
})

describe('CaseSchema — GHERKIN requires a scenario', () => {
  it('rejects GHERKIN with no source, on the gherkinSource field', () => {
    expect(pathsOf({ ...valid(), stepFormat: 'GHERKIN', gherkinSource: null })).toContain('gherkinSource')
    expect(messageOn({ ...valid(), stepFormat: 'GHERKIN', gherkinSource: null }, 'gherkinSource')).toMatch(
      /scenario/i,
    )
  })

  it('rejects whitespace as a scenario — a source of spaces is not a scenario', () => {
    expect(pathsOf({ ...valid(), stepFormat: 'GHERKIN', gherkinSource: '   \n  ' })).toContain('gherkinSource')
  })

  it('accepts GHERKIN with a scenario, and stops demanding steps', () => {
    const input = { ...valid(), stepFormat: 'GHERKIN', gherkinSource: 'Feature: x', steps: [] }
    expect(CaseSchema.safeParse(input).success).toBe(true)
  })
})

describe('CaseSchema — STRUCTURED requires at least one step', () => {
  it('rejects zero steps, on the steps field', () => {
    expect(pathsOf({ ...valid(), steps: [] })).toContain('steps')
    expect(messageOn({ ...valid(), steps: [] }, 'steps')).toMatch(/at least one step/i)
  })

  it('does not demand a scenario while structured', () => {
    expect(pathsOf({ ...valid(), gherkinSource: null })).not.toContain('gherkinSource')
  })

  it('still requires each step to describe an action', () => {
    expect(pathsOf({ ...valid(), steps: [{ action: '' }] })).toContain('steps.0.action')
  })
})

describe('CaseSchema — NOT_AUTOMATABLE requires a reason', () => {
  it('rejects a missing reason, on the notAutomatableReason field', () => {
    const input = { ...valid(), automationStatus: 'NOT_AUTOMATABLE', notAutomatableReason: null }
    expect(pathsOf(input)).toContain('notAutomatableReason')
    expect(messageOn(input, 'notAutomatableReason')).toMatch(/cannot be automated/i)
  })

  it('rejects a whitespace-only reason', () => {
    expect(
      pathsOf({ ...valid(), automationStatus: 'NOT_AUTOMATABLE', notAutomatableReason: '  ' }),
    ).toContain('notAutomatableReason')
  })

  it('accepts a real reason', () => {
    const input = {
      ...valid(),
      automationStatus: 'NOT_AUTOMATABLE',
      notAutomatableReason: 'Needs a physical card terminal.',
    }
    expect(CaseSchema.safeParse(input).success).toBe(true)
  })

  it('does not demand a reason for any other status — the coverage figure only stays honest if the rule is narrow', () => {
    for (const status of ['MANUAL', 'TO_BE_AUTOMATED'] as const) {
      expect(pathsOf({ ...valid(), automationStatus: status })).not.toContain('notAutomatableReason')
    }
  })
})

describe('CaseSchema — AUTOMATED requires the automated test key', () => {
  it('rejects a missing key, on the automationKey field', () => {
    const input = { ...valid(), automationStatus: 'AUTOMATED', automationKey: null }
    expect(pathsOf(input)).toContain('automationKey')
    // The message has to say *why* the key is needed — results are matched on it.
    expect(messageOn(input, 'automationKey')).toMatch(/results can be matched/i)
  })

  it('rejects a whitespace-only key', () => {
    expect(pathsOf({ ...valid(), automationStatus: 'AUTOMATED', automationKey: '\t' })).toContain(
      'automationKey',
    )
  })

  it('accepts a fully-qualified test method', () => {
    const input = {
      ...valid(),
      automationStatus: 'AUTOMATED',
      automationKey: 'com.acme.PaymentsApiTest#shouldRejectDoubleRefund',
    }
    expect(CaseSchema.safeParse(input).success).toBe(true)
  })

  it('does not demand a key while the case is manual', () => {
    expect(pathsOf(valid())).not.toContain('automationKey')
  })
})

describe('CaseSchema — the refinements compose', () => {
  /**
   * Zod stops at the first failing `.refine()` in a chain by default only for `superRefine`
   * short-circuits; a plain chain evaluates each. This pins which of the two it is, because a
   * form that reports one problem at a time when two exist makes the user submit twice.
   */
  it('reports GHERKIN-without-a-scenario and AUTOMATED-without-a-key together', () => {
    const paths = pathsOf({
      ...valid(),
      stepFormat: 'GHERKIN',
      gherkinSource: null,
      automationStatus: 'AUTOMATED',
      automationKey: null,
    })
    expect(paths).toContain('gherkinSource')
    expect(paths).toContain('automationKey')
  })

  it('reports a field-level failure alongside a cross-field one', () => {
    const paths = pathsOf({ ...valid(), title: '', steps: [] })
    expect(paths).toContain('title')
    expect(paths).toContain('steps')
  })
})

describe('CaseSchema — estimatedDurationSec is a string the form produces', () => {
  it('accepts an empty value, because the field is optional', () => {
    expect(CaseSchema.safeParse({ ...valid(), estimatedDurationSec: '' }).success).toBe(true)
  })

  it('accepts a whole number of seconds', () => {
    expect(CaseSchema.safeParse({ ...valid(), estimatedDurationSec: '90' }).success).toBe(true)
  })

  it('rejects anything that is not a whole number', () => {
    for (const bad of ['1.5', '-3', 'ten', '9 ']) {
      expect(pathsOf({ ...valid(), estimatedDurationSec: bad })).toContain('estimatedDurationSec')
    }
  })
})
