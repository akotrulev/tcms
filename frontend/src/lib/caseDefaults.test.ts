import { parse } from 'yaml'
import { describe, expect, it } from 'vitest'

import specText from '../../../docs/specs/openapi.yaml?raw'
import { NEW_CASE_DEFAULTS } from './caseDefaults'

/**
 * The invented defaults, checked against the contract that decides how many there have to be.
 *
 * The *values* are not the subject — that `priority` is `MEDIUM` is a product choice the module's
 * header argues, and asserting it here would restate the constant rather than test it. The subject
 * is the **arity**: `TestCaseInput` requires four fields, one of which the author supplies (the
 * title), and this constant is the SPA's answer for the other three. A fifth required field added
 * to the contract makes every case authored from a plan row — a title and nothing else, §97 D2 —
 * a 400 that no typecheck predicts, because the generated types would only mark the new property
 * required on a payload this constant is spread into rather than typed as.
 *
 * The spec is read directly rather than through the generated types, which is the point: the
 * generated copy is regenerated *from* it, so the two agree by construction and a comparison
 * between them proves nothing about the field this constant forgot. Through Vite's `?raw`, the
 * same seam `api/contractBodies.ts` uses — a `node:fs` read would need `tsconfig.node.json`, which
 * cannot compile a file that imports application source.
 */

interface Spec {
  components: { schemas: Record<string, { required?: string[] }> }
}

const spec = parse(specText) as Spec

describe('NEW_CASE_DEFAULTS answers everything the contract requires beside the title', () => {
  it('supplies every required field the author does not', () => {
    const required = spec.components.schemas.TestCaseInput?.required ?? []
    const authored = ['title']

    expect(required.length, 'TestCaseInput has no required array').toBeGreaterThan(0)
    expect(new Set([...Object.keys(NEW_CASE_DEFAULTS), ...authored])).toEqual(new Set(required))
  })

  it('invents nothing the contract does not ask for', () => {
    // The mirror of the check above, and the reason the assertion is a set equality rather than a
    // superset: a default the server does not require is the SPA deciding something on the author's
    // behalf that nobody asked it to decide.
    const required = new Set(spec.components.schemas.TestCaseInput?.required ?? [])

    for (const field of Object.keys(NEW_CASE_DEFAULTS)) {
      expect(required, `${field} is not required by TestCaseInput`).toContain(field)
    }
  })
})
