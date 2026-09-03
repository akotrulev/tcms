import { afterEach, beforeAll, describe, expect, it } from 'vitest'
import { installFaultConsole, verdictFor } from './devFaults'

/**
 * The import arm of the fault switch, and the one thing about it that is easy to get wrong.
 *
 * `/imports/preview`, `/imports/source-projects` and `/imports/{runId}` are the **same path shape**.
 * A run-read arm anchored on `/^\/imports\/[^/]+$/` alone would answer the preview with an
 * `ImportRun`, which is the drifted-body failure injected by accident — three arms away from the one
 * being tested and indistinguishable, on screen, from a real contract drift. The method is what
 * separates them, and this is the check that it does.
 */

interface FaultConsole {
  importing: (fault: string | null) => void
  off: () => void
}

function faults(): FaultConsole {
  return (window as unknown as { tcmsFaults: FaultConsole }).tcmsFaults
}

beforeAll(() => {
  installFaultConsole()
})

afterEach(() => {
  faults().off()
})

describe('midway — the arm the module was built for', () => {
  it('accepts the run and then answers the poll FAILED, with nothing imported', () => {
    faults().importing('midway')

    const accepted = verdictFor('/imports', 'POST')
    expect(accepted).toMatchObject({ kind: 'body', status: 202 })

    const polled = verdictFor('/imports/00000000-0000-4000-8000-0000000000f1', 'GET')
    expect(polled).toMatchObject({ kind: 'body', status: 200 })
    const run = (polled as { body: { status: string; issuesImported: number; failureReason: string } })
      .body
    expect(run.status).toBe('FAILED')
    // The claim the whole module rests on: the write is one all-or-nothing transaction after the
    // read pass, so a source that stopped answering leaves nothing behind. A fabricated partial
    // count here would make the fault agree with the defect.
    expect(run.issuesImported).toBe(0)
    expect(run.failureReason).toMatch(/exactly as it was/)
  })

  it('does not answer the preview, whose path is the same shape as a run id', () => {
    faults().importing('midway')

    expect(verdictFor('/imports/preview', 'POST')).toEqual({ kind: 'none' })
    expect(verdictFor('/imports/source-projects', 'POST')).toEqual({ kind: 'none' })
  })
})

describe('the three refusals', () => {
  it('answers the two credential-carrying POSTs with the source’s own sentence', () => {
    faults().importing('unauthorized')

    const refused = verdictFor('/imports/preview', 'POST')
    expect(refused).toMatchObject({ kind: 'body', status: 502 })
    expect((refused as { body: { detail: string } }).body.detail).toMatch(/revoked/)
  })

  it('leaves the report reads alone — they reach no source system', () => {
    faults().importing('down')

    /*
      `GET /imports` and `GET /imports/{id}` are served from our own database, which is the promise
      the report makes about outliving the system it describes. A fault that broke them would assert
      the opposite of what the module claims.
    */
    expect(verdictFor('/imports?projectId=p1', 'GET')).toEqual({ kind: 'none' })
    expect(verdictFor('/imports/ir1', 'GET')).toEqual({ kind: 'none' })
  })
})

describe('collision — a 200, not a failure', () => {
  it('answers the preview with a KEY_COLLISION blocker and the offending keys', () => {
    faults().importing('collision')

    const answered = verdictFor('/imports/preview', 'POST')
    expect(answered).toMatchObject({ kind: 'body', status: 200 })
    const blockers = (answered as { body: { blockers: { kind: string; keys: string[] }[] } }).body
      .blockers
    expect(blockers[0]?.kind).toBe('KEY_COLLISION')
    expect(blockers[0]?.keys).toContain('PROJ-4812')
  })
})

describe('slow', () => {
  it('hangs the source calls and not the poll', () => {
    faults().importing('slow')

    expect(verdictFor('/imports/preview', 'POST')).toEqual({ kind: 'hang' })
    // A hung poll would be the spinner with no end that the server-side reaper exists to prevent,
    // reproduced on the client — a defect to avoid, not a budget to prove.
    expect(verdictFor('/imports/ir1', 'GET')).toEqual({ kind: 'none' })
  })
})
