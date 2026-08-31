import { useParams } from 'react-router-dom';
import { useProject, useProjectWorkflow, useSetProjectArchived } from '../../api/queries';
import type { IssueTypeRef, Project } from '../../api/types';
import { DataRegion } from '../../components/DataRegion';
import { Identifier } from '../../components/Identifier';
import { PageHeader } from '../../components/PageHeader';
import { PermissionGate } from '../../components/PermissionGate';
import { Badge, Banner } from '../../components/kit';
import { Button } from '../../components/ui';
import { issueLevels } from '../../lib/labels';
import { TypeIcon } from '../tracker/TypeIcon';

const TEST_ID = 'GeneralSettings';

/**
 * `settings-general.html`, minus what M1 has no data for (011 D-f).
 *
 * **Absent rather than empty**, each with the module that lands it:
 *
 * | Mockup panel | Module | Why it is not here |
 * |---|---|---|
 * | Client select | Mc | `Project` carries no client, so the control could only ever say "None". An empty "Client: —" is exactly the nagging `solo-overview.html` rules out |
 * | "Closing work" review toggles | M5 | Nothing reads them, and a toggle that changes no behaviour is the no-op this repo has shipped once |
 * | "Time" panel | M6 | There is no hours ceiling and no logged time to show a burn against |
 *
 * What lands is **Identity** and the **Issue types** list.
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

  return (
    <div>
      <PageHeader
        testId={`${TEST_ID}.Header`}
        title="General"
        documentTitle={project.data ? `Settings · ${project.data.name}` : 'Project settings'}
        crumbs={[
          { label: project.data?.name ?? 'Project', to: `/projects/${projectId}` },
          { label: 'General' },
        ]}
        subtitle="What this project is called, what prefixes its issues, and the kinds of issue it holds."
      />

      <div className="grid max-w-[1180px] gap-3.5 lg:grid-cols-[minmax(0,1fr)_320px]">
        <div className="flex flex-col gap-3.5">
          <DataRegion
            testId={`${TEST_ID}.Identity`}
            state={{ ...project, refetch: () => void project.refetch() }}
          >
            {(current) => <IdentityPanel project={current} />}
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
