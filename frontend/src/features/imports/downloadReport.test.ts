import { describe, expect, it } from 'vitest'
import { anImportRun, aDroppedField } from '../../test/fixtures'
import { droppedFieldsCsv } from './downloadReport'

/**
 * The CSV, which is the half of the download that has a rule in it.
 *
 * The JSON half is `JSON.stringify` over a shape the contract owns and there is nothing to get
 * wrong; the CSV has two things that are, and both are on rows this product genuinely produces. A
 * reason sentence carries commas — every one in `DroppedFields` does — and the mockup's own row is
 * called `Custom: "Squad"`, which carries the quote character the format uses to escape.
 */
describe('droppedFieldsCsv', () => {
  it('quotes every field and doubles an embedded quote', () => {
    const csv = droppedFieldsCsv(
      anImportRun({
        droppedFields: [
          aDroppedField({
            name: 'Custom: "Squad"',
            fieldType: 'picker',
            valueCount: 137,
            reason: 'no custom fields in this product, and none is planned',
          }),
        ],
      }),
    )

    expect(csv.split('\r\n')).toEqual([
      '"Source field","Type","Values held","Why"',
      '"Custom: ""Squad""","picker","137","no custom fields in this product, and none is planned"',
    ])
  })

  it('keeps the server’s order and its sentences, and adds none of its own', () => {
    const csv = droppedFieldsCsv(
      anImportRun({
        droppedFields: [
          aDroppedField({ name: 'Story Points', reason: 'first' }),
          aDroppedField({ name: 'Sprint', reason: 'second' }),
        ],
      }),
    )

    // The order is the source's own field order, persisted with a `position` (D-f). A client that
    // sorted this would break an equality it cannot see.
    expect(csv).toMatch(/"Story Points".*\r\n.*"Sprint"/s)
  })

  it('writes a header and nothing else for a run that dropped nothing', () => {
    // A FAILED run has an empty dropped list because nothing was written. A headerless empty file
    // is one a spreadsheet opens as a single blank cell.
    expect(droppedFieldsCsv(anImportRun())).toBe('"Source field","Type","Values held","Why"')
  })
})
