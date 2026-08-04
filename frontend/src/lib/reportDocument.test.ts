import { describe, expect, it } from 'vitest'
import { headerBlock, parseReportDocument, type ReportDocument } from './reportDocument'

/**
 * The boundary between a stored file and a rendered screen.
 *
 * This file exists because the report model is the **one shape in this client with no generated
 * type behind it** — the artifact route is declared binary, so `schema.d.ts` cannot describe it and
 * nothing mechanical checks that what the screen renders is what the server wrote. The specific
 * failures asserted here:
 *
 *  1. A document from a **newer** deployment still opens. An artifact outlives the release that
 *     wrote it, so a header field R6 adds must not make every report unopenable.
 *  2. A **broken** document fails loudly instead of rendering as a report with nothing in it. That
 *     is the failure that looks like a working screen — a run in which nothing happened renders
 *     identically — and `data-fetching`'s rule is that a parse failure is an error, not an
 *     emptiness.
 *  3. `headerBlock` prints **every** provenance line, in the server's order, with the selection
 *     prefixed. This screen is the fourth renderer of a block `ReportDocument.headerBlock()`
 *     assembles in Java, and nothing can fail if the two quietly disagree.
 */

const document = (): unknown => ({
  header: {
    reportType: 'run_summary',
    description: 'A single run: status breakdown, progress per tester, and results by suite.',
    company: 'Northwind Trading',
    projectScope: 'ACME — Acme Platform',
    scopeNote: 'This report covers ACME — Acme Platform in full and no other project.',
    generatedBy: 'Dana Lead',
    generatedAt: '2026-08-04 12:00:00 UTC',
    selection: [{ label: 'runId', value: 'a1b2' }],
  },
  report: {
    title: 'Run summary — TR-3',
    metadata: [{ label: 'Aggregation policy', value: 'Not applicable' }],
    sections: [
      {
        heading: 'Results',
        narrative: 'Shares are of the 2 executions recorded in this run.',
        table: { columns: ['Status', 'Executions'], rows: [['PASSED', '1']] },
        series: [{ name: 'Executions by status', points: [{ label: 'PASSED', value: 1 }] }],
      },
    ],
  },
})

describe('parseReportDocument', () => {
  it('reads the shape the server writes', () => {
    const parsed = parseReportDocument(document())

    expect(parsed.header.reportType).toBe('run_summary')
    expect(parsed.report.sections[0]?.table?.rows).toEqual([['PASSED', '1']])
    expect(parsed.report.sections[0]?.series[0]?.points[0]?.value).toBe(1)
  })

  /*
    The compatibility direction that matters. `JsonReportRenderer` writes an artifact that is read
    months later, so this client WILL be handed a document written by a later release — R6 adds
    header fields, R7 adds sections. Refusing it would make every report of that vintage unopenable,
    which is a far worse failure than not showing a field this version has no rendering for.
  */
  it('drops fields a later release added rather than refusing the document', () => {
    const forward = document() as Record<string, Record<string, unknown>>
    forward.header.aggregationPolicy = 'LATEST'
    forward.report.footnotes = ['Something this version knows nothing about']

    const parsed = parseReportDocument(forward)

    expect(parsed.header.company).toBe('Northwind Trading')
    expect(parsed.report.title).toBe('Run summary — TR-3')
    expect('aggregationPolicy' in parsed.header).toBe(false)
  })

  /*
    A section's `table` is `null` when the section has none — `JsonReportRenderer` writes the null
    explicitly rather than omitting the key, "so a consumer can tell 'this section has no table'
    from 'the producer did not know about tables'". A schema that accepted only a table would refuse
    every narrative-only section, which is what `ReportModel.Section.text` exists to produce.
  */
  it('accepts a section that is narrative alone, with an explicit null table', () => {
    const narrativeOnly = document() as { report: { sections: unknown[] } }
    narrativeOnly.report.sections = [
      { heading: 'Inconsistencies', narrative: 'Nothing to report.', table: null, series: [] },
    ]

    const parsed = parseReportDocument(narrativeOnly)

    expect(parsed.report.sections[0]?.table).toBeNull()
    expect(parsed.report.sections[0]?.narrative).toBe('Nothing to report.')
  })

  /*
    Each of these renders as an empty-but-plausible report if it is cast rather than parsed, which
    is the whole argument of the module. `sections: []` is deliberately NOT in this list: a report
    with no sections is a legal report, and refusing it would be this test asserting a rule the
    server does not have.
  */
  it.each([
    ['the report half is missing entirely', { header: (document() as { header: unknown }).header }],
    ['the header half is missing entirely', { report: (document() as { report: unknown }).report }],
    ['it is not an object at all', 'This is a Markdown report, not the model'],
    ['it is null', null],
  ])('throws when %s', (_case, value) => {
    expect(() => parseReportDocument(value)).toThrow(/not in a shape this version can display/)
  })

  /*
    The subtle one, and the reason the cells are typed as strings: a producer that sent a NUMBER
    where the model promises pre-formatted text would let this screen print `0.935` beside an export
    reading `93.5%` — `ReportModel`'s own "every cell is a string" decision, enforced at the only
    place this client can enforce it.
  */
  it('throws when a table cell is a number rather than formatted text', () => {
    const numeric = document() as { report: { sections: { table: { rows: unknown[][] } }[] } }
    numeric.report.sections[0]!.table.rows = [['PASSED', 1]]

    expect(() => parseReportDocument(numeric)).toThrow(/not in a shape this version can display/)
  })

  it('names where the document broke, so the report is identifiable as the broken thing', () => {
    const broken = document() as { header: Record<string, unknown> }
    delete broken.header.generatedAt

    expect(() => parseReportDocument(broken)).toThrow(/header\.generatedAt/)
  })
})

describe('headerBlock', () => {
  const block = () => headerBlock(parseReportDocument(document()) satisfies ReportDocument)

  /*
    Asserted as an EXACT ordered list of labels rather than with `toContain` per line. The failure
    this catches is a line silently going missing — `toContain` passes cleanly against a header that
    prints six of the seven provenance entries, and a report that does not say who generated it is
    exactly the artifact §6e's header requirement exists to prevent. The order is the server's
    (`ReportDocument.headerBlock`): provenance, then selection, then the report's own metadata.
  */
  it('prints every provenance line, then the selection, then the report metadata', () => {
    expect(block().map((entry) => entry.label)).toEqual([
      'Report',
      'Report type',
      'Company',
      'Project scope',
      'Scope',
      'Generated by',
      'Generated at',
      'Selection — runId',
      'Aggregation policy',
    ])
  })

  /*
    The prefix is not cosmetic: a parameter is named by the CLIENT and could be called "Company",
    and an unprefixed merge would let a caller's own key take the place of a provenance line — a
    document that lies about itself. This asserts the prefixed entry and the real one coexist.
  */
  it('prefixes selection entries so a parameter cannot displace a provenance line', () => {
    const hostile = document() as { header: { selection: unknown[] } }
    hostile.header.selection = [{ label: 'Company', value: 'Somebody Else Ltd' }]

    const entries = headerBlock(parseReportDocument(hostile))

    expect(entries.find((entry) => entry.label === 'Company')?.value).toBe('Northwind Trading')
    expect(entries.find((entry) => entry.label === 'Selection — Company')?.value).toBe(
      'Somebody Else Ltd',
    )
  })
})
