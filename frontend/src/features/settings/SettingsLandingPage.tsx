import { Link as RouterLink, useParams } from 'react-router-dom';
import type { ReactNode } from 'react';
import {
  useBoardCoverage,
  useBoards,
  useClassification,
  useImportRuns,
  useNotificationChannels,
  useProject,
  useProjectAuditEvents,
  useProjectMembers,
  useProjectRepositories,
  useProjectSettings,
  useProjectWorkflow,
  useTrackerBindings,
} from '../../api/queries';
import { useCan } from '../../auth/useCan';
import { DataRegion, type DataRegionState } from '../../components/DataRegion';
import { PageHeader } from '../../components/PageHeader';
import { Skeleton } from '../../components/ui';
import { formatAge, joinMeta, plural } from '../../lib/format';
import { importProviderLabels, trackerProviderLabels } from '../../lib/labels';
import type { SettingsSection } from '../../app/nav';
import { SettingsSubnav } from './SettingsSubnav';
import { useSettingsSections } from './useSettingsSections';

/**
 * `/projects/:projectId/settings` — **one rail row where eleven stood** (021 §5).
 *
 * `General`, `Workflow`, `Boards`, `Repositories`, `Imports`, `Classification`, `Review`,
 * `Notifications`, `Issue tracker`, `Access & roles` and `Audit trail` were eleven siblings in the
 * rail's `Project` folder — a quarter of the whole rail spent on screens most people open twice a
 * quarter. They are one row now, and this is what it leads to.
 *
 * ## Each card carries one live fact, and that is the whole design
 *
 * A landing page that is eleven links is a worse rail: the same eleven names, one click further
 * away, answering nothing. What makes this pay is that every card says **what is configured**
 * before anybody clicks — `11 statuses · 22 legal moves`, `3 bound`, `last: Jira, 12 Aug`. The
 * question a person arrives with is almost never "take me to the workflow screen"; it is "does
 * this project have a review policy", and this page answers that class of question without a
 * navigation.
 *
 * **Every fact comes from a read that already exists.** No endpoint was added for this page and no
 * summary was invented: each card is a `DataRegion` over the same query its own screen uses, so
 * the number here and the number there cannot disagree, and a card whose read fails **shows that
 * failure** rather than a blank line — a settings page that silently omits a fact is one that says
 * "nothing configured" when it means "could not ask".
 *
 * The cost is stated rather than hidden: this page makes up to eleven requests on arrival. They
 * are all warm afterwards — every one is the read the section's own screen makes — and they are
 * independent, so a slow one delays its own card and nothing else.
 *
 * ## The gate is the rail's, not a new one
 *
 * Sections are filtered by `useSettingsSections`, which is the same predicate `SettingsSubnav`
 * reads: **can the page be read?** A Test Engineer sees seven cards, not eleven — `Imports`,
 * `Notifications`, `Issue tracker` and `Project audit` are absent, exactly as they were absent
 * from the rail before this page existed. The nine recorded hide-don't-disable exceptions are
 * unchanged; this page did not add a tenth.
 */
export function SettingsLandingPage() {
  const { projectId = '' } = useParams();
  const project = useProject(projectId);
  const sections = useSettingsSections(projectId);
  const gate = useCan(projectId);

  /*
    How many sections this caller cannot see. Rendered as a sentence at the foot rather than as
    greyed rows, because greyed rows are the thing these nine exceptions exist to avoid — and
    silence would leave a reader who has been told about `Imports` by a colleague with no way to
    tell "not in this product" from "not for you". T7's `Your access` page names each one and who
    holds its key; until it lands, the count and the reason are what can honestly be said.
  */
  const hiddenCount = 11 - sections.length;

  return (
    <div>
      <PageHeader
        testId="Settings.Header"
        title="Settings"
        documentTitle={project.data ? `Settings · ${project.data.name}` : 'Settings'}
        subtitle="What is configured in this project, and by whom."
      />

      <SettingsSubnav projectId={projectId} />

      <div
        data-testid="Settings.Cards"
        className="grid gap-3 [grid-template-columns:repeat(auto-fill,minmax(260px,1fr))]"
      >
        {sections.map((section) => (
          <SectionCard key={section.label} section={section} projectId={projectId} />
        ))}
      </div>

      {hiddenCount > 0 && !gate.pending && (
        <p
          data-testid="Settings.Hidden"
          className="mt-5 mb-0 max-w-[74ch] text-[12.5px] text-ink-3"
        >
          {plural(hiddenCount, 'section')} of this project&rsquo;s settings{' '}
          {hiddenCount === 1 ? 'is' : 'are'} not on this page for you. Each one costs a permission
          on the <em>read</em>, so the screen behind it would have nothing on it but a refusal.
        </p>
      )}
    </div>
  );
}

function SectionCard({
  section,
  projectId,
}: {
  section: SettingsSection;
  projectId: string;
}) {
  const Icon = section.icon;
  const slug = section.label.replace(/[^A-Za-z]/g, '');

  return (
    <section
      data-testid={`Settings.Card.${slug}`}
      className="rounded-lg border border-line bg-panel p-3"
    >
      <h2 className="m-0 flex items-center gap-2 text-[13px] font-semibold">
        <Icon aria-hidden className="size-4 shrink-0 text-ink-3" />
        <RouterLink
          to={section.to(projectId)}
          data-testid={`Settings.Card.${slug}.Link`}
          className="text-ink no-underline hover:underline"
        >
          {section.label}
        </RouterLink>
        {/*
          The key a gated section is held by, on the card that is only there because the reader
          holds it. It is the same disclosure the mockup draws and it costs nothing to a reader
          who does not care — but it is the one thing that turns "why can my colleague see this"
          into an answerable question.
        */}
        {section.gate && (
          <span className="ml-auto shrink-0 font-mono text-[10px] text-ink-4">
            {section.gate.key}
          </span>
        )}
      </h2>
      <div className="mt-1.5 text-[12.5px] text-ink-2">
        <SectionFact label={section.label} projectId={projectId} />
      </div>
    </section>
  );
}

/**
 * One live fact per section, each from the read its own screen makes.
 *
 * A `switch` on the label rather than a `fact` function on the `SettingsSection` model: a fact is
 * a *hook call*, and hooks cannot live in a data literal. Keeping `nav.ts` free of them is what
 * lets the palette import it (T7) without dragging the query layer along.
 */
function SectionFact({ label, projectId }: { label: string; projectId: string }) {
  switch (label) {
    case 'General':
      return <GeneralFact projectId={projectId} />;
    case 'Workflow':
      return <WorkflowFact projectId={projectId} />;
    case 'Boards':
      return <BoardsFact projectId={projectId} />;
    case 'Code repositories':
      return <RepositoriesFact projectId={projectId} />;
    case 'Classification':
      return <ClassificationFact projectId={projectId} />;
    case 'Review policy':
      return <ReviewFact projectId={projectId} />;
    case 'Imports':
      return <ImportsFact projectId={projectId} />;
    case 'Notifications':
      return <NotificationsFact projectId={projectId} />;
    case 'Issue tracker':
      return <TrackerFact projectId={projectId} />;
    case 'Access & roles':
      return <AccessFact projectId={projectId} />;
    case 'Project audit':
      return <AuditFact projectId={projectId} />;
    default:
      return null;
  }
}

/**
 * The region every fact renders through — one line of text, one skeleton the width of a line.
 *
 * The skeleton approximates the real thing so a card does not resize when its number lands; eleven
 * cards each growing a row on arrival is a page that reflows under the reader's cursor.
 */
function Fact<T>({
  testId,
  state,
  children,
}: {
  testId: string;
  state: DataRegionState<T>;
  children: (data: T) => ReactNode;
}) {
  return (
    <DataRegion testId={testId} state={state} skeleton={<Skeleton className="h-4 w-40" />}>
      {children}
    </DataRegion>
  );
}

function GeneralFact({ projectId }: { projectId: string }) {
  const project = useProject(projectId);
  const workflow = useProjectWorkflow(projectId);
  return (
    <Fact
      testId="Settings.Fact.General"
      state={{ ...workflow, refetch: () => void workflow.refetch() }}
    >
      {(data) => joinMeta(project.data?.key, plural(data.types.length, 'issue type'))}
    </Fact>
  );
}

function WorkflowFact({ projectId }: { projectId: string }) {
  const workflow = useProjectWorkflow(projectId);
  return (
    <Fact
      testId="Settings.Fact.Workflow"
      state={{ ...workflow, refetch: () => void workflow.refetch() }}
    >
      {(data) =>
        joinMeta(
          plural(data.statuses.length, 'status'),
          plural(new Set(data.statuses.map((status) => status.kind)).size, 'kind'),
          plural(data.transitions.length, 'legal move'),
        )
      }
    </Fact>
  );
}

function BoardsFact({ projectId }: { projectId: string }) {
  const boards = useBoards(projectId);
  const coverage = useBoardCoverage(projectId);
  return (
    <Fact testId="Settings.Fact.Boards" state={{ ...boards, refetch: () => void boards.refetch() }}>
      {(rows) =>
        joinMeta(
          rows.map((board) => `${board.name} (${board.statusCount})`).join(' · ') || 'no boards',
          /*
            021 R7 traded the rule that refused an unclaimed status for a read that reports it, and
            this is the one place the report belongs on a summary: a status no board claims holds
            cards nobody sees. Silent while the coverage read is in flight — a card must not say
            "every status covered" before it has been told.
          */
          coverage.data
            ? coverage.data.uncovered.length === 0
              ? 'every status covered'
              : `${plural(coverage.data.uncovered.length, 'status')} on no board`
            : undefined,
        )
      }
    </Fact>
  );
}

function RepositoriesFact({ projectId }: { projectId: string }) {
  const repos = useProjectRepositories(projectId);
  return (
    <Fact
      testId="Settings.Fact.Coderepositories"
      state={{ ...repos, refetch: () => void repos.refetch() }}
    >
      {(rows) => (rows.length === 0 ? 'none bound' : `${plural(rows.length, 'repository')} bound`)}
    </Fact>
  );
}

function ClassificationFact({ projectId }: { projectId: string }) {
  const dimensions = useClassification(projectId);
  return (
    <Fact
      testId="Settings.Fact.Classification"
      state={{ ...dimensions, refetch: () => void dimensions.refetch() }}
    >
      {(rows) => plural(rows.length, 'vocabulary')}
    </Fact>
  );
}

function ReviewFact({ projectId }: { projectId: string }) {
  const settings = useProjectSettings(projectId);
  return (
    <Fact
      testId="Settings.Fact.Reviewpolicy"
      state={{ ...settings, refetch: () => void settings.refetch() }}
    >
      {(data) => {
        const on = [
          data.reviewEnabledCase && 'cases',
          data.reviewEnabledSuite && 'suites',
          data.reviewEnabledPlan && 'plans',
          data.reviewEnabledDoc && 'pages',
        ].filter((label): label is string => !!label);
        return joinMeta(
          on.length ? `review on for ${on.join(', ')}` : 'review off everywhere',
          data.requiredPeerReviews > 0
            ? `${plural(data.requiredPeerReviews, 'reviewer')} required on approve`
            : undefined,
        );
      }}
    </Fact>
  );
}

function ImportsFact({ projectId }: { projectId: string }) {
  const runs = useImportRuns(projectId);
  return (
    <Fact testId="Settings.Fact.Imports" state={{ ...runs, refetch: () => void runs.refetch() }}>
      {(rows) => {
        const last = rows[0];
        return last
          ? `last: ${importProviderLabels[last.provider]}, ${formatAge(last.startedAt)}`
          : 'no imports yet';
      }}
    </Fact>
  );
}

function NotificationsFact({ projectId }: { projectId: string }) {
  /*
    Gated on `integration:manage`, and the hook takes the verdict rather than fetching regardless:
    this card only renders for a caller who holds the key, so `true` here is not an assumption —
    `useSettingsSections` has already answered it.
  */
  const channels = useNotificationChannels(projectId, true);
  return (
    <Fact
      testId="Settings.Fact.Notifications"
      state={{ ...channels, refetch: () => void channels.refetch() }}
    >
      {(rows) =>
        rows.length === 0
          ? 'no channels'
          : joinMeta(
              plural(rows.length, 'channel'),
              rows.some((row) => !row.enabled)
                ? `${rows.filter((row) => !row.enabled).length} switched off`
                : undefined,
            )
      }
    </Fact>
  );
}

function TrackerFact({ projectId }: { projectId: string }) {
  const bindings = useTrackerBindings(projectId, true);
  return (
    <Fact
      testId="Settings.Fact.Issuetracker"
      state={{ ...bindings, refetch: () => void bindings.refetch() }}
    >
      {(rows) =>
        rows.length === 0
          ? 'nothing bound'
          : rows
              .map((row) => `${trackerProviderLabels[row.provider]} · ${row.externalProject}`)
              .join(' · ')
      }
    </Fact>
  );
}

function AccessFact({ projectId }: { projectId: string }) {
  const members = useProjectMembers(projectId, true);
  return (
    <Fact
      testId="Settings.Fact.Accessroles"
      state={{ ...members, refetch: () => void members.refetch() }}
    >
      {(rows) => plural(rows.length, 'member')}
    </Fact>
  );
}

function AuditFact({ projectId }: { projectId: string }) {
  /*
    One page of one row: the fact is *when the last thing happened*, and asking for the default
    page size to render a date would fetch fifty events to read one. `page: 0` because this trail's
    paging is zero-based — `useAuditFilters` reads `Number(params.get('page') ?? 0)`.
  */
  const events = useProjectAuditEvents(projectId, { page: 0, size: 1 });
  return (
    <Fact testId="Settings.Fact.Projectaudit" state={{ ...events, refetch: () => void events.refetch() }}>
      {(page) => {
        const last = page.items[0];
        return last
          ? `last event ${formatAge(last.occurredAt)} · ${plural(page.total, 'event')} recorded`
          : 'nothing recorded yet';
      }}
    </Fact>
  );
}
