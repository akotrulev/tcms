import { Link as RouterLink, useParams } from 'react-router-dom';
import {
  useProject,
  useProjectBudgetBurn,
  useProjectSettings,
  useProjectWorkflow,
  useSetProjectArchived,
  useProjectClientOptions,
  useSetProjectClient,
  useUpdateProjectSettings,
} from '../../api/queries';
import type { BudgetBurn, IssueTypeRef, Project, ProjectSettings } from '../../api/types';
import { DataRegion } from '../../components/DataRegion';
import { Identifier } from '../../components/Identifier';
import { PageHeader } from '../../components/PageHeader';
import { SettingsSubnav } from './SettingsSubnav';
import { PermissionGate } from '../../components/PermissionGate';
import { Badge, Banner } from '../../components/kit';
import { SettingRow } from '../../components/kit/SettingRow';
import { Button, Switch } from '../../components/ui';
import { useCan, useCanInCompany } from '../../auth/useCan';
import { formatHours } from '../../lib/format';
import { issueLevels } from '../../lib/labels';
import { fullSettingsUpdate } from './settingsUpdate';
import { TypeIcon } from '../tracker/TypeIcon';

const TEST_ID = 'GeneralSettings';

/**
 * `settings-general.html`, minus what M1 has no data for (011 D-f).
 *
 * **Absent rather than empty**, each with the module that lands it:
 *
 * | Mockup panel | Module | Why it is not here |
 * |---|---|---|
 * | ~~Client select~~ | ~~Mc~~ | → **landed 2026-09-05 (018 T4/T5)** as {@link ClientPanel}. The reason it was absent is now the reason it is CONDITIONAL: it renders only when the company has at least one client, so a company with none still sees no "Client: —" |
 * | ~~"Closing work" review toggles~~ | ~~M5~~ | → **landed in 015 T5.** `review_enabled_issue` exists and `TransitionService` reads it, so the toggle changes behaviour |
 * | ~~"Time" panel~~ | ~~M6~~ | → **landed 2026-09-04 (016 T5)** as {@link TimePanel}. ~~Its *Budget* row did **not**: that row reads `Edit in Billing`, and there is no hours ceiling, no rate and no Billing screen — M8's, all three~~ → **the row landed too, 2026-09-05 (019 T6)**, as {@link BudgetRow} |
 *
 * What lands is **Identity**, **Closing work** and the **Issue types** list.

 * ## Only one of the mockup's two "Closing work" rows is real
 *
 * `settings-general.html` draws a second, uneditable row — *"An assistant may never close. Fixed.
 * An assistant reaches In Review at most, whatever this project sets"* — and the product does not
 * work that way. There is **no token-specific rule** (015 D2/A6): an assistant acts as its owner,
 * so with the gate off the same close succeeds for it exactly as it would for them. That is the
 * criterion a one-person company depends on, and a row promising an unconditional block would be a
 * claim the server contradicts. Dropped rather than rendered, and recorded here.
 *
 * ## Identity is read-only, and that is the contract rather than a choice
 *
 * There is no `PATCH /projects/{projectId}` — the contract has a create, a detail read, an archive
 * and a settings document, and nothing that renames a project. The key is stronger still: the
 * schema calls it **immutable**, chosen once at creation and unique per company.
 *
 * So this panel renders the two values and says which of them can never change. It deliberately
 * does **not** render the mockup's two inputs and its Save button: a form that shows success and
 * discards the value is `ui-rules`' highest-severity form defect, and a Save with no endpoint
 * behind it is that defect with no server to blame. The mockup's key caption — *"Existing links
 * keep working if it changes"* — is kept only as far as it is true, and the half that promises a
 * rename is replaced by the fact: **the key is immutable.** Recorded as a disagreement with the
 * mockup in 011's close-out rather than silently resolved.
 *
 * ## Six issue types, not the mockup's five
 *
 * `settings-general.html` lists Epic, Story, Task, Bug and Spike — and `story.html` separately
 * offers *"Add sub-task"* on a story. Both cannot be true with one `Task` type once D29 puts Task
 * at the **story** level, so the product seeds six and this screen lists all six. The captions name
 * the level each sits at, because that is the difference a reader cannot see from the glyph: `Task`
 * and `Sub-task` are two different rows one level apart.
 *
 * **The captions are derived from `level` and `countsTowardCompletion`, never from the type's
 * name.** `TypeIcon` makes this argument for its colour and it is the same argument: a type is a
 * row a project may rename, so a sentence keyed on the English word `Spike` is a sentence that goes
 * quietly wrong on the first project that renames it. What the contract carries is the two fields
 * that answer the question, and they are what these rows read.
 */
export function GeneralSettingsPage() {
  const { projectId = '' } = useParams();
  const project = useProject(projectId);
  const workflow = useProjectWorkflow(projectId);
  const settings = useProjectSettings(projectId);
  const canEdit = useCan(projectId).can('project:settings');

  return (
    <div>
      <PageHeader
        testId={`${TEST_ID}.Header`}
        title="General"
        documentTitle={project.data ? `Settings · ${project.data.name}` : 'Project settings'}
        subtitle="What this project is called, what prefixes its issues, and the kinds of issue it holds."
      />

      <SettingsSubnav projectId={projectId} />

      <div className="grid max-w-[1180px] gap-3.5 lg:grid-cols-[minmax(0,1fr)_320px]">
        <div className="flex flex-col gap-3.5">
          <DataRegion
            testId={`${TEST_ID}.Identity`}
            state={{ ...project, refetch: () => void project.refetch() }}
          >
            {(current) => <IdentityPanel project={current} />}
          </DataRegion>

          <DataRegion
            testId={`${TEST_ID}.Client`}
            state={{ ...project, refetch: () => void project.refetch() }}
          >
            {(current) => <ClientPanel projectId={projectId} project={current} canEdit={canEdit} />}
          </DataRegion>

          <DataRegion
            testId={`${TEST_ID}.Closing`}
            state={{ ...settings, refetch: () => void settings.refetch() }}
          >
            {(current) => (
              <ClosingWorkPanel projectId={projectId} settings={current} canEdit={canEdit} />
            )}
          </DataRegion>

          <DataRegion
            testId={`${TEST_ID}.Time`}
            state={{ ...settings, refetch: () => void settings.refetch() }}
          >
            {(current) => (
              <TimePanel projectId={projectId} settings={current} canEdit={canEdit} />
            )}
          </DataRegion>

          <DataRegion
            testId={`${TEST_ID}.Types`}
            state={{ ...workflow, refetch: () => void workflow.refetch() }}
          >
            {(current) => <IssueTypesPanel types={current.types} />}
          </DataRegion>
        </div>

        <div className="flex flex-col gap-3.5">
          <DataRegion
            testId={`${TEST_ID}.Danger`}
            state={{ ...project, refetch: () => void project.refetch() }}
          >
            {(current) => <DangerZone projectId={projectId} archived={current.archived} />}
          </DataRegion>
        </div>
      </div>
    </div>
  );
}

/**
 * D33's gate, on the screen the mockup and the reader both look for it on.
 *
 * **It saves the whole settings document**, through `fullSettingsUpdate`, and that is the only
 * interesting thing about the panel: the endpoint is a `PUT` of the whole value and an absent
 * boolean reads as `false`, so a form here that sent one field would switch off every review
 * setting the Review screen owns. The two screens edit one document, and the field list is written
 * once, beside them.
 *
 * **Saved on the switch rather than behind a Save button**, unlike the Review form. There is one
 * control and no second decision to make with it — the Review page keeps a draft because six
 * controls only make sense together, and one control does not.
 */
function ClosingWorkPanel({
  projectId,
  settings,
  canEdit,
}: {
  projectId: string;
  settings: ProjectSettings;
  canEdit: boolean;
}) {
  const save = useUpdateProjectSettings(projectId);

  return (
    <section
      className="rounded-[10px] border border-line bg-panel"
      data-testid={`${TEST_ID}.Closing.Panel`}
    >
      <div className="border-b border-line px-3.5 py-2.5">
        <h2 className="m-0 text-[13.5px] font-semibold text-ink">Closing work</h2>
      </div>

      <div className="px-3.5">
        <SettingRow
          label="Require review to close issues"
          description="A second person moves an issue into a done status. Whoever is assigned to it cannot close it themselves."
          consequence={
            settings.authorMayApproveOwnWork
              ? 'Currently inert: "the person who submits something cannot approve it" is off for this project, and this gate is built on that rule. Turn it on under Review for this switch to bite.'
              : 'It binds on the assignee alone, and an assistant is bound by it exactly as the person who owns it would be — there is no separate rule for one.'
          }
          control={
            <Switch
              checked={settings.reviewEnabledIssue}
              disabled={!canEdit || save.isPending}
              onCheckedChange={(checked) =>
                save.mutate(fullSettingsUpdate(settings, { reviewEnabledIssue: checked }))
              }
              aria-label="Require review to close issues"
              data-testid={`${TEST_ID}.reviewEnabledIssue`}
            />
          }
        />
      </div>

      {save.isError && (
        <div className="px-3.5 pb-3">
          <Banner tone="bad" data-testid={`${TEST_ID}.Closing.Failed`}>
            <span role="alert">The switch did not save. It shows what the server holds.</span>
          </Banner>
        </div>
      )}

      <div className="border-t border-line px-3.5 py-2.5">
        <span className="text-[11.5px] leading-[1.55] text-ink-4">
          A new project ships with this <strong>off</strong>, so a one-person company is not
          dead-ended by a rule written for a team.
        </span>
      </div>
    </section>
  );
}

/**
 * 016 D-g's switch — `settings-general.html`'s Time panel, minus its Budget row.
 *
 * **On by default**, and it is the only switch in this document that is. `work-table.html` says
 * *"Logged hours are here because this project has time tracking on"*, and a product whose
 * timesheet is empty on every project until somebody finds a switch is a feature nobody discovers.
 *
 * **Off hides and does not delete**, which is what the consequence line says on the screen rather
 * than only in a decision document: the `Logged` column, the story's Time panel and the epic's
 * estimate-vs-actual table all go, new entries against this project's issues are refused, and every
 * hour already logged, approved and rolled up stays exactly where it is. Somebody turning it off to
 * tidy a screen must be able to read, before they click, that they are not destroying a timesheet.
 *
 * ~~**The mockup's Budget row is deliberately not built.** It reads `600h` beside an `Edit in
 * Billing` button, and there is no hours ceiling on the contract, no rate anywhere in the product,
 * and no Billing screen to send anybody to — all three are M8's. A row rendering a number this
 * build cannot source, behind a link to a screen that does not exist, is two failures at once.~~ →
 * **superseded 2026-09-05 (019 T6):** all three exist, so the row is built — see
 * {@link BudgetRow}. It reads the same `budget-burn` endpoint the project overview does, which is
 * gated on `project:read` and carries **no amount and no currency** (019 D-h), so a project
 * administrator reads the ceiling without holding a money key. The `Edit in Billing` button is
 * **disabled with its reason** for a caller without `billing:read` rather than hidden: this is a
 * screen control and not a rail entry, so §31's disable-don't-hide applies to it in full.
 *
 * **The budget is not part of this document and must not join it.** `PUT /projects/{id}/settings`
 * is a whole-document replacement whose field list is written once in `settingsUpdate.ts`; the
 * ceiling is set by `PUT /projects/{id}/budget` on a money key. Two screens writing one budget
 * through two shapes is how a ceiling gets cleared by somebody flipping a switch.
 *
 * Saved on the switch, like {@link ClosingWorkPanel} beside it and for the same reason: one control
 * and no second decision to make with it. It goes through `fullSettingsUpdate` because the `PUT` is
 * a whole-document replacement — a form here that sent one field would switch off every review
 * setting the Review screen owns.
 */
function TimePanel({
  projectId,
  settings,
  canEdit,
}: {
  projectId: string;
  settings: ProjectSettings;
  canEdit: boolean;
}) {
  const save = useUpdateProjectSettings(projectId);

  return (
    <section
      className="rounded-[10px] border border-line bg-panel"
      data-testid={`${TEST_ID}.Time.Panel`}
    >
      <div className="border-b border-line px-3.5 py-2.5">
        <h2 className="m-0 text-[13.5px] font-semibold text-ink">Time</h2>
      </div>

      <div className="px-3.5">
        <SettingRow
          label="Track time on this project"
          description="Engineers log hours against its issues, and the Logged figures appear on the work table, the story and the epic."
          consequence={
            settings.trackTime
              ? 'Turning it off hides those figures and refuses new entries against this project. It deletes nothing — every hour already logged and approved is kept, and turning it back on shows them again.'
              : 'Off. Hours recorded before it was switched off are still there and still approved; nothing on this project can be logged against until it is switched back on.'
          }
          control={
            <Switch
              checked={settings.trackTime}
              disabled={!canEdit || save.isPending}
              onCheckedChange={(checked) =>
                save.mutate(fullSettingsUpdate(settings, { trackTime: checked }))
              }
              aria-label="Track time on this project"
              data-testid={`${TEST_ID}.trackTime`}
            />
          }
        />

        <BudgetRow projectId={projectId} />
      </div>

      {save.isError && (
        <div className="px-3.5 pb-3">
          <Banner tone="bad" data-testid={`${TEST_ID}.Time.Failed`}>
            <span role="alert">The switch did not save. It shows what the server holds.</span>
          </Banner>
        </div>
      )}

      <div className="border-t border-line px-3.5 py-2.5">
        <span className="text-[11.5px] leading-[1.55] text-ink-4">
          A budget is a ceiling, not a switch: hours are recorded against this project whether it
          has one or not, and a project with none is in an ordinary state rather than an unfinished
          one.
        </span>
      </div>
    </section>
  );
}

/**
 * `settings-general.html`'s **Budget** row — the ceiling, and the button that goes where it is set.
 *
 * <h2>It reads the burn endpoint, which is the one billing read with no money on it</h2>
 *
 * `GET /projects/{projectId}/budget-burn` is gated on `project:read` and its schema has no `amount`
 * and no `currency` member at all (019 D-h), so a project administrator sees their own ceiling
 * without holding one of the seven money keys. That is the whole reason this row can exist on a
 * project screen at all.
 *
 * <h2>A money ceiling has no number here, and an em dash would be the wrong one</h2>
 *
 * `budgetHours` is null for a `MONEY`-basis budget, because what it costs is Finance's. The row
 * says *set in money* rather than drawing `—`: the dash this app uses means *absent*, and the
 * ceiling is not absent — it is not this reader's.
 *
 * <h2>The button is disabled with its reason, never hidden</h2>
 *
 * `billing:read` is a COMPANY-scope key that a Project Manager does not hold, so most callers who
 * can open this screen cannot open the one the button points at. §31: an absent control teaches
 * them the product cannot do this; a disabled one with the gate's own sentence tells them who to
 * ask. The rail entry into `/billing` is the deliberate exception (019 D-o) and it is a rail entry,
 * not a control on a screen somebody is already reading.
 */
function BudgetRow({ projectId }: { projectId: string }) {
  const burn = useProjectBudgetBurn(projectId);
  const gate = useCanInCompany();
  const canOpenBilling = gate.can('billing:read');

  return (
    <SettingRow
      label="Budget"
      description="Hours ceiling — the PM sees the burn, never the rate."
      consequence="Set in Billing, on a money key. Approved hours are counted against it here; what they are worth is not on this screen."
      control={
        <>
          <span
            className="font-mono text-[13px] tabular-nums text-ink"
            data-testid={`${TEST_ID}.Budget.Ceiling`}
          >
            {burn.data === undefined ? '' : ceilingOf(burn.data)}
          </span>
          {canOpenBilling ?
            <Button asChild size="sm" data-testid={`${TEST_ID}.Budget.Edit`}>
              <RouterLink to="/billing">Edit in Billing</RouterLink>
            </Button>
          : <span title={gate.reason('billing:read')}>
              <Button size="sm" disabled data-testid={`${TEST_ID}.Budget.Edit`}>
                Edit in Billing
              </Button>
            </span>
          }
        </>
      }
    />
  );
}

/** The ceiling as this row shows it — never a currency, and never an em dash for a money one. */
function ceilingOf(burn: BudgetBurn): string {
  if (burn.basis === null) return 'no budget set';
  if (burn.budgetHours === null) return 'set in money';
  return formatHours(burn.budgetHours);
}

/**
 * `settings-general.html`'s Client row (018 Mc) — story C2's picker.
 *
 * <h2>It costs `project:settings`, not `client:read`</h2>
 *
 * Plan 010 D-M0-9: Project Manager is PROJECT-scope, so a company-wide `client:read` would be an
 * inert row for them, and granting it to the `Company Member` baseline would break the one sentence
 * that role is defined by. So the options come from
 * `GET /projects/{id}/settings/client-options` — a separate path from the settings document, which
 * anyone who can see the project may read, because a list of every client the company has is not
 * that kind of fact.
 *
 * <h2>Saved on change, and NOT through the settings document</h2>
 *
 * `PUT /projects/{projectId}/client`, one field, the shape `…/archive` already uses. It was briefly
 * a field on `ProjectSettingsUpdate` and 018 D-d records the reversal: an absent nullable id and an
 * explicit `null` are the same Java value, so a save from the *Review* screen — which does not
 * offer this control — would have silently detached the project.
 *
 * <h2>Absent, not empty, for a company with no clients</h2>
 *
 * The whole panel is conditional on the options being non-empty. This file's own header table has
 * said why since M1: *"an empty 'Client: —' is exactly the nagging `solo-overview.html` rules out."*
 */
function ClientPanel({
  projectId,
  project,
  canEdit,
}: {
  projectId: string;
  project: Project;
  canEdit: boolean;
}) {
  const options = useProjectClientOptions(projectId, canEdit);
  const save = useSetProjectClient(projectId);

  if ((options.data?.length ?? 0) === 0) return null;

  return (
    <section className="rounded-[10px] border border-line bg-panel" data-testid={`${TEST_ID}.Client.Panel`}>
      <div className="border-b border-line px-3.5 py-2.5">
        <h2 className="m-0 text-[13.5px] font-semibold text-ink">Client</h2>
      </div>
      <div className="px-3.5 py-3">
        <select
          className="w-full max-w-sm rounded-[6px] border border-line bg-panel px-2.5 py-1.5 text-[13px] text-ink disabled:opacity-60"
          value={project.client?.id ?? ''}
          disabled={!canEdit || save.isPending}
          onChange={(event) =>
            void save.mutateAsync(event.target.value === '' ? null : event.target.value)
          }
          data-testid={`${TEST_ID}.Client.Select`}
        >
          <option value="">None (internal)</option>
          {(options.data ?? []).map((client) => (
            <option key={client.id} value={client.id}>
              {client.name}
            </option>
          ))}
        </select>
        <p className="mt-1.5 mb-0 max-w-[62ch] text-[12px] leading-[1.55] text-ink-4">
          Who this project is for. <strong>“None (internal)” is a value, not a gap</strong> — a
          project with no client is not marked as missing one anywhere in the product.
        </p>
      </div>
    </section>
  );
}

function IdentityPanel({ project }: { project: Project }) {
  return (
    <section className="rounded-[10px] border border-line bg-panel" data-testid={`${TEST_ID}.Identity.Panel`}>
      <div className="border-b border-line px-3.5 py-2.5">
        <h2 className="m-0 text-[13.5px] font-semibold text-ink">Identity</h2>
      </div>

      <dl className="m-0 grid gap-4 p-3.5 sm:grid-cols-[120px_minmax(0,1fr)]">
        <dt className="text-[12.5px] text-ink-3">Name</dt>
        <dd className="m-0 text-[13.5px] text-ink" data-testid={`${TEST_ID}.Identity.Name`}>
          {project.name}
        </dd>

        <dt className="text-[12.5px] text-ink-3">Key</dt>
        <dd className="m-0" data-testid={`${TEST_ID}.Identity.Key`}>
          <Identifier variant="strong">{project.key}</Identifier>
          <p className="mt-1.5 mb-0 max-w-[62ch] text-[12px] leading-[1.55] text-ink-4">
            Prefixes every issue in this project — <Identifier variant="muted">{`${project.key}-124`}</Identifier>.
            It is <strong>immutable</strong>: chosen when the project was created and unique in this
            company, so every link anybody has ever saved keeps resolving.
          </p>
        </dd>
      </dl>

      <div className="border-t border-line px-3.5 py-2.5">
        <span className="text-[11.5px] leading-[1.55] text-ink-4" data-testid={`${TEST_ID}.Identity.ReadOnly`}>
          Neither field can be changed from here — the API has no rename, so this panel shows what
          the project is rather than a form that would discard what you typed.
        </span>
      </div>
    </section>
  );
}

/**
 * What a type is, said from the two fields the contract carries.
 *
 * The order is `level` first because that is the structural fact — where a type sits decides what
 * it may parent to and what may parent to it — and `countsTowardCompletion` second because that is
 * what it does to a percentage.
 */
function describeType(type: IssueTypeRef): string {
  if (type.level === 'EPIC') {
    return 'The top level. Holds story-level issues, and its completion is computed from them rather than counted as one of them.';
  }
  if (type.level === 'TASK') {
    return type.countsTowardCompletion
      ? 'A breakdown of a story-level issue. Always has a parent, and counts toward that epic’s completion.'
      : 'A breakdown of a story-level issue. Always has a parent, and is left out of every percentage.';
  }
  return type.countsTowardCompletion
    ? 'What a person picks up. Sits under an epic or stands alone, and holds sub-tasks.'
    : 'Time-boxed investigation. Holds sub-tasks, and is left out of both halves of every percentage.';
}

/** The badge on the right of each row — the same two fields, as three words. */
function completionBadge(type: IssueTypeRef): string {
  if (type.level === 'EPIC') return 'counts by children';
  return type.countsTowardCompletion ? 'in completion' : 'excluded from %';
}

function IssueTypesPanel({ types }: { types: readonly IssueTypeRef[] }) {
  return (
    <section className="rounded-[10px] border border-line bg-panel" data-testid={`${TEST_ID}.Types.Panel`}>
      <div className="flex flex-wrap items-center gap-2.5 border-b border-line px-3.5 py-2.5">
        <h2 className="m-0 text-[13.5px] font-semibold text-ink">Issue types</h2>
        <Identifier variant="muted">{String(types.length)}</Identifier>
      </div>

      <div>
        {types.map((type) => (
          <div
            key={type.id}
            className="flex flex-wrap items-start gap-2.5 border-b border-line px-3.5 py-3 last:border-b-0"
            data-testid={`${TEST_ID}.Types.Row`}
          >
            <TypeIcon type={type} className="mt-0.5" data-testid={`${TEST_ID}.Types.Row.Icon`} />
            <div className="min-w-0 flex-1">
              <div className="flex flex-wrap items-center gap-2">
                <strong className="text-[13px] text-ink" data-testid={`${TEST_ID}.Types.Row.Name`}>
                  {type.name}
                </strong>
                {/*
                  The level in words, from `lib/labels.ts`. This is the whole reason six types are
                  not confusing: `Task` and `Sub-task` are one level apart, and without this row a
                  reader sees two similar names and no way to tell which one a story holds.
                */}
                <Badge tone="plain" data-testid={`${TEST_ID}.Types.Row.Level`}>
                  {issueLevels[type.level]}
                </Badge>
              </div>
              <p className="mt-1 mb-0 max-w-[64ch] text-[12.5px] leading-[1.55] text-ink-3">
                {describeType(type)}
              </p>
            </div>
            <Badge tone="plain" data-testid={`${TEST_ID}.Types.Row.Completion`}>
              {completionBadge(type)}
            </Badge>
          </div>
        ))}
      </div>

      {/*
        Verbatim, and it is the promise D12 makes to every user who had a task before the upgrade:
        their rows were relabelled to the STORY-level `Task` type, so the word on their screen means
        what it meant before. This sentence is the only place the product says so.
      */}
      <div className="border-t border-line px-3.5 py-2.5">
        <span className="text-[11.5px] leading-[1.55] text-ink-4" data-testid={`${TEST_ID}.Types.Foot`}>
          Tasks created before the upgrade are still called <strong>Task</strong>. Nothing you typed
          changed meaning underneath you.
        </span>
      </div>
    </section>
  );
}

/**
 * Retire this project, or bring it back — `settings-general.html`'s Danger zone.
 *
 * ~~It stood in the project's front-door header (`DashboardPage`), on the argument that the front
 * door is about the project as a whole while each settings screen is about one subsystem.~~ →
 * **superseded by 011 T7.** The mockups put it here and the overview's header carries three actions
 * without it; more to the point, the overview is now a screen somebody opens all day to read
 * numbers, and a retire button on it is a destructive action beside a percentage.
 *
 * **One button whose label is the move available from the state the project is in**, never two of
 * which one is always a no-op the server would answer 200 to — the same shape a doc page's
 * publication state uses.
 *
 * **No confirmation dialog, deliberately.** The action destroys nothing, hides nothing and is
 * undone by the button that replaces it. A dialog here would teach people to click through dialogs
 * on a screen that also has real ones.
 */
function DangerZone({ projectId, archived }: { projectId: string; archived: boolean }) {
  const setArchived = useSetProjectArchived(projectId);

  return (
    <section className="rounded-[10px] border border-line bg-panel" data-testid={`${TEST_ID}.Danger.Panel`}>
      <div className="border-b border-line px-3.5 py-2.5">
        <h2 className="m-0 text-[13.5px] font-semibold text-ink">Danger zone</h2>
      </div>

      <div className="flex flex-col gap-2.5 p-3.5">
        <p className="m-0 max-w-[62ch] text-[12.5px] leading-[1.55] text-ink-3">
          Archiving hides the project from lists and marks it retired. Nothing is deleted, its keys
          stay resolvable, and this button brings it back.
        </p>

        {archived && (
          <Banner tone="neutral" data-testid={`${TEST_ID}.Danger.Archived`}>
            <span>This project is archived.</span>
          </Banner>
        )}

        <PermissionGate projectId={projectId} permission="project:settings">
          {({ allowed }) => (
            <div className="flex items-center gap-2.5">
              <Button
                variant="danger"
                disabled={!allowed || setArchived.isPending}
                // The target state, not a toggle of what this render happens to hold: a stale copy
                // then asks for the state it can already see rather than for the opposite of it.
                onClick={() => setArchived.mutate(!archived)}
                data-testid={`${TEST_ID}.Danger.Archive`}
              >
                {archived ? 'Restore project' : 'Archive project'}
              </Button>
              {!allowed && (
                <span className="text-[12px] text-ink-4">
                  Needs <Identifier variant="muted">project:settings</Identifier> on this project.
                </span>
              )}
            </div>
          )}
        </PermissionGate>
      </div>
    </section>
  );
}
