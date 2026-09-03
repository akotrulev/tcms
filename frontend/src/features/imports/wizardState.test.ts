import { describe, expect, it } from 'vitest'
import { anImportPreview, aPreviewStatusRow } from '../../test/fixtures'
import {
  defaultPreserveBacklogOrder,
  initialWizardState,
  mappingFrom,
  mappingOrigin,
  needsAccountEmail,
  unmappedRows,
} from './wizardState'

/**
 * The wizard's own arithmetic, away from the screens.
 *
 * Two of these are claims the screens then depend on and could not state on their own: that a status
 * the preview could not match starts life as an **unmapped** row rather than as the first status in
 * the list, and that a row the server matched but a person then changed stops reading *name matched*.
 */

describe('the suggested mapping', () => {
  it('leaves an unmatched status unmapped rather than defaulting it', () => {
    const preview = anImportPreview({
      statuses: [
        aPreviewStatusRow({ sourceStatusName: 'To Do', matched: true, suggestedStatusId: 's1' }),
        aPreviewStatusRow({ sourceStatusName: 'Awaiting Deploy', count: 87 }),
      ],
    })

    const mapping = mappingFrom(preview)

    expect(mapping['To Do']).toBe('s1')
    /*
      The whole point of the disabled Confirm: an unmapped status that silently became the default
      would put those 87 issues in the wrong column, and nothing on screen would say so.
    */
    expect(mapping['Awaiting Deploy']).toBe('')
    expect(unmappedRows(preview, mapping).map((row) => row.sourceStatusName)).toEqual([
      'Awaiting Deploy',
    ])
  })
})

describe('how a row got its target', () => {
  const matched = aPreviewStatusRow({ matched: true, suggestedStatusId: 's1' })

  it('reads as chosen once a person overrides the suggestion', () => {
    expect(mappingOrigin(matched, 's1')).toBe('matched')
    // A badge that still said "name matched" here would describe the suggestion rather than what
    // will be applied.
    expect(mappingOrigin(matched, 's2')).toBe('chosen')
    expect(mappingOrigin(matched, '')).toBe('unmapped')
  })
})

describe('the provider’s two consequences', () => {
  it('asks for an account email only where it is half the credential', () => {
    expect(needsAccountEmail('JIRA_CLOUD')).toBe(true)
    // The other two send a Bearer PAT and the contract says the email is ignored, so requiring one
    // would be the form inventing a field the server throws away.
    expect(needsAccountEmail('JIRA_DATA_CENTER')).toBe(false)
    expect(needsAccountEmail('AZURE_DEVOPS')).toBe(false)
  })

  it('keeps the source backlog order for Jira by default (D-i)', () => {
    expect(defaultPreserveBacklogOrder('JIRA_CLOUD')).toBe(true)
    expect(defaultPreserveBacklogOrder('AZURE_DEVOPS')).toBe(false)
    expect(initialWizardState().options).toEqual({
      // Off, per D-h: sixty sprints become sixty labels nobody chose.
      labelPerSprint: false,
      preserveBacklogOrder: true,
    })
  })
})
