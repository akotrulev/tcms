import type {
  ImportConnection,
  ImportOptions,
  ImportPreview,
  ImportProvider,
  PreviewStatusRow,
  SourceProject,
} from '../../api/types';

/**
 * Everything the three steps share, in one object — **including the API token**.
 *
 * ## Why it is one object and not five `useState`s
 *
 * Because it has to survive a trip to `settings-workflow.html` and back. `import-mapping.html`
 * promises *"Add a status here if none of yours fits — the mapping picks it up on return"*, which
 * is a promise about state rather than about a link: a person who leaves to add *Awaiting Deploy*
 * and comes back to an empty connect form has been told to do something the screen then punished
 * them for. The wizard writes this object into its own history entry before navigating away and
 * restores it on return, and that is only writable as one value.
 *
 * ## Where it lives, and where it must never live
 *
 * In React state and in a `history.state` entry — both of which the tab discards. **Not**
 * `localStorage`, **not** a query key, **not** the URL. `connection.apiToken` is a live credential
 * and D-a's promise, printed under the token field, is that it is used for this import and stored
 * nowhere; a "helpful" persistence layer over this type is that promise becoming false with nothing
 * on screen changing.
 *
 * `history.state` is the one storage that keeps the promise: it is per-tab, per-entry, and it does
 * not survive a reload. The connect step says so in a sentence the mockup has no reason to carry.
 */
export type WizardStep = 'connect' | 'preview' | 'mapping';

export interface WizardState {
  step: WizardStep;
  connection: ImportConnection;
  /** What the connection can see, or `null` before anybody has asked it. */
  sourceProjects: SourceProject[] | null;
  sourceProjectKey: string;
  preview: ImportPreview | null;
  /**
   * Source status name → the target status id, `''` for a row nobody has mapped.
   *
   * `''` rather than the key being absent, so an unmapped row and a row the preview never listed
   * are different states: the first disables Confirm with a reason, the second is not on screen.
   */
  mapping: Record<string, string>;
  options: ImportOptions;
}

export function initialWizardState(): WizardState {
  return {
    step: 'connect',
    connection: {
      // D-k: the mockup is Jira-shaped and 009's backend line names three providers, so the
      // selector exists and Jira Cloud is preselected. The form keeps its shape either way.
      provider: 'JIRA_CLOUD',
      siteUrl: '',
      accountEmail: '',
      apiToken: '',
    },
    sourceProjects: null,
    sourceProjectKey: '',
    preview: null,
    mapping: {},
    options: { labelPerSprint: false, preserveBacklogOrder: true },
  };
}

/**
 * Whether this provider authenticates with an account email beside the token.
 *
 * Jira Cloud uses Basic — the email *is* half the credential. The other two send a Bearer PAT and
 * the contract says `accountEmail` is ignored, so requiring one there would be this form inventing
 * a field the server throws away.
 */
export function needsAccountEmail(provider: ImportProvider): boolean {
  return provider === 'JIRA_CLOUD';
}

/**
 * Whether the source's backlog order is worth keeping, by default (D-i).
 *
 * On for both Jira flavours, whose board ranking is a thing somebody maintained. Off for Azure
 * DevOps, whose backlog order is a different construct — and when it is off the report says the
 * order is insertion order rather than letting it read as imported.
 */
export function defaultPreserveBacklogOrder(provider: ImportProvider): boolean {
  return provider !== 'AZURE_DEVOPS';
}

/** The mapping the preview suggests — `''` wherever nothing matched, which is an unmapped row. */
export function mappingFrom(preview: ImportPreview): Record<string, string> {
  return Object.fromEntries(
    preview.statuses.map((row) => [row.sourceStatusName, row.suggestedStatusId ?? '']),
  );
}

/** The rows with no target. Confirm is disabled while this is non-empty, and says why. */
export function unmappedRows(
  preview: ImportPreview,
  mapping: Record<string, string>,
): PreviewStatusRow[] {
  return preview.statuses.filter((row) => !mapping[row.sourceStatusName]);
}

/**
 * How a mapped row got its target — the mockup's three badges.
 *
 * `matched` alone is not enough: a row the server matched by name and a person then changed reads
 * *"you chose this"*, and a badge that still said *name matched* would be describing the
 * suggestion rather than what will be applied.
 */
export function mappingOrigin(
  row: PreviewStatusRow,
  targetStatusId: string,
): 'unmapped' | 'matched' | 'chosen' {
  if (!targetStatusId) return 'unmapped';
  return row.matched && targetStatusId === row.suggestedStatusId ? 'matched' : 'chosen';
}
