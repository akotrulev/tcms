import { Box, Button, Chip, Stack, Tooltip, Typography } from '@mui/material';
import { Link as RouterLink, useParams } from 'react-router-dom';
import type {
  DashboardBreakdown,
  DashboardTile,
  LifecycleStatus,
  RepositoryDashboard,
} from '../../api/types';
import { useDashboard, useProject, useSetProjectArchived } from '../../api/queries';
import { DataRegion } from '../../components/DataRegion';
import { Identifier } from '../../components/Identifier';
import { PageHeader, Panel } from '../../components/PageHeader';
import { PermissionGate } from '../../components/PermissionGate';
import { formatCount, formatDateTime, formatPercent } from '../../lib/format';
import { automationLabels, healthLabels, labelForKey, lifecycleLabels } from '../../lib/labels';
import { lifecycleVar, severityVar } from '../../lib/statusColors';

/**
 * Repository status dashboard (plan §6o).
 *
 * The design rule that makes this useful rather than decorative: **every tile is a link that
 * emits a filter predicate**, and predicates compose. A tile is not a number to look at, it
 * is the entrance to the working list of cases behind that number — where the user can
 * filter further and act in bulk. A dashboard whose tiles are dead ends produces a report
 * nobody can do anything with.
 *
 * Because the case list keeps its filters in the URL, "78 cases whose spec changed" is one
 * `<Link>` away from being a shareable, bulk-actionable destination.
 */
export function DashboardPage() {
  const { projectId = '' } = useParams();
  const project = useProject(projectId);
  const dashboard = useDashboard(projectId);

  return (
    <Box>
      <PageHeader
        testId="Dashboard.Header"
        title={project.data?.name ?? 'Overview'}
        documentTitle={project.data ? `Overview · ${project.data.name}` : 'Overview'}
        meta={
          project.data && (
            <>
              <Identifier variant="strong">{project.data.key}</Identifier>
              <Typography variant="body2" sx={{ color: 'text.secondary' }}>
                <Identifier variant="muted">{formatCount(project.data.caseCount)}</Identifier> cases
              </Typography>
              {/* Omitted rather than empty when myRole is null — see ProjectsPage for why a
                  COMPANY-scope grant legitimately has no per-project role name. */}
              {project.data.myRole !== null && (
                <Typography variant="body2" sx={{ color: 'text.secondary' }}>
                  Your role: {project.data.myRole}
                </Typography>
              )}
              {/*
                Stated on the project's own landing page as well as on the picker card, because
                this is where somebody arrives from a bookmark — and a retired project that looks
                identical to a live one is how work gets filed into it for another six months.
              */}
              {project.data.archived && (
                <Chip
                  size="small"
                  variant="outlined"
                  label="Archived"
                  data-testid="Dashboard.Archived"
                />
              )}
            </>
          )
        }
        subtitle={project.data?.description}
        actions={
          <>
            {project.data && (
              <ArchiveButton projectId={projectId} archived={project.data.archived} />
            )}
            <Button
              component={RouterLink}
              to={`/projects/${projectId}/cases`}
              variant="contained"
              data-testid="Dashboard.BrowseCasesButton"
            >
              Browse test cases
            </Button>
          </>
        }
      />

      <DataRegion
        testId="Dashboard.Content"
        state={{ ...dashboard, refetch: () => void dashboard.refetch() }}
      >
        {(data) => <DashboardBody data={data} projectId={projectId} />}
      </DataRegion>
    </Box>
  );
}

/**
 * Retire this project, or bring it back (§159).
 *
 * Here rather than on a settings screen because this page is the project's front door and the two
 * settings screens are each about one subsystem — §6g's review policy, the classification
 * vocabulary — while this is about the project as a whole. It is the same shape the docs tree uses
 * for a page's publication state: **one button whose label is the move available from the state the
 * project is in**, never two of which one is always a no-op the server would answer 200 to.
 *
 * **No confirmation dialog, deliberately.** The action destroys nothing, hides nothing and is
 * undone by the button that replaces it. A dialog here would teach people to click through dialogs
 * on a screen that also has real ones.
 *
 * Disabled rather than hidden for a caller without `project:settings`, with the reason in the
 * tooltip — `PermissionGate`'s whole content, and the reason a Test Engineer learns the capability
 * exists and who to ask.
 */
function ArchiveButton({ projectId, archived }: { projectId: string; archived: boolean }) {
  const setArchived = useSetProjectArchived(projectId);

  return (
    <PermissionGate projectId={projectId} permission="project:settings">
      {({ allowed }) => (
        <Button
          variant="outlined"
          disabled={!allowed || setArchived.isPending}
          // The target state, not a toggle of what this render happens to hold: a stale copy then
          // asks for the state it can already see rather than for the opposite of it.
          onClick={() => setArchived.mutate(!archived)}
          data-testid="Dashboard.ArchiveButton"
        >
          {archived ? 'Restore project' : 'Archive project'}
        </Button>
      )}
    </PermissionGate>
  );
}

function DashboardBody({ data, projectId }: { data: RepositoryDashboard; projectId: string }) {
  const casesPath = `/projects/${projectId}/cases`;

  return (
    <Stack sx={{ gap: 3 }}>
      <Typography variant="caption" sx={{ color: 'text.secondary' }} data-testid="Dashboard.GeneratedAt">
        Counted at {formatDateTime(data.generatedAt)}
      </Typography>

      <Panel title="Authoring status" testId="Dashboard.Lifecycle">
        <TileRow
          tiles={data.lifecycle}
          labels={lifecycleLabels}
          casesPath={casesPath}
          testId="Dashboard.Lifecycle"
        />
      </Panel>

      {/*
        Health is the section people act on, so it comes before the breakdowns and its tiles
        are ordered by severity rather than alphabetically — the two error-severity signals
        are the ones that mean something is actually wrong right now.
      */}
      <Panel
        title="Needs attention"
        testId="Dashboard.Health"
        actions={
          data.healthComputedAt !== null && (
            <Typography variant="caption" sx={{ color: 'text.secondary' }}>
              Each tile opens the cases behind it
            </Typography>
          )
        }
      >
        <HealthPanel data={data} casesPath={casesPath} />
      </Panel>

      <Stack direction={{ xs: 'column', lg: 'row' }} sx={{ gap: 3, alignItems: 'stretch' }}>
        <Box sx={{ flex: 1, minWidth: 0 }}>
          <Panel title="By feature" testId="Dashboard.ByFeature">
            <BreakdownList
              rows={data.byFeature}
              casesPath={casesPath}
              testId="Dashboard.ByFeature"
              emptyMessage="No case has been given a feature yet. Features are a per-project vocabulary you define, and cases are classified against it."
            />
          </Panel>
        </Box>
        <Box sx={{ flex: 1, minWidth: 0 }}>
          <Panel title="By owner" testId="Dashboard.ByOwner">
            <BreakdownList
              rows={data.byOwner}
              casesPath={casesPath}
              testId="Dashboard.ByOwner"
              emptyMessage="No case has an owner yet."
            />
          </Panel>
        </Box>
      </Stack>

      <Panel title="Automation coverage" testId="Dashboard.Automation">
        <AutomationCoverage coverage={data.automationCoverage} casesPath={casesPath} />
      </Panel>

      <Panel title="Activity, last 30 days" testId="Dashboard.Activity">
        <ActivityChart activity={data.recentActivity} />
      </Panel>
    </Stack>
  );
}

/**
 * The three states of "Needs attention", which are genuinely three and not two.
 *
 * An empty tile list is a **claim** that nothing is wrong. That claim is only true if somebody
 * looked, and `healthComputedAt` is the server saying whether anybody has. Rendering the empty
 * panel and the never-computed panel identically is the same defect as §46's `met: null` reading
 * as a failed gate — a reassuring sentence where the honest one is "we don't know yet".
 *
 * ~~`healthComputedAt` is `null` while `case_health` has no writer, which is every deployment
 * today.~~ → **superseded 2026-08-09 (§156).** It has three writers, the recompute is
 * `matchIfMissing = true` on an hourly schedule, so the timestamp is non-null within two minutes
 * of a deployment starting — and **five of the ten signals still have no computation behind them**
 * (`ArchitectureRulesTest.everyMappedColumnHasAWriter`). So the middle branch, which used to be
 * unreachable, is the one every deployment now lands in, and it was saying "Nothing needs
 * attention" about five checks that had never run.
 *
 * The copy is narrowed rather than the branch changed: this panel cannot know *which* signals the
 * server derives — that is a server fact, and a list of them here would be the second
 * implementation of it. What it can honestly say is what it actually knows, which is that no
 * signal was raised. Naming the gap needs the server to say which signals it computes, and that
 * is a contract change.
 *
 * Exported for its own test: two of these three branches are unreachable through a fixture that
 * always supplies both a timestamp and tiles, and §46's finding is that no test written before a
 * state is reachable can fail on it.
 */
export function HealthPanel({ data, casesPath }: { data: RepositoryDashboard; casesPath: string }) {
  if (data.healthComputedAt === null) {
    return (
      <Typography variant="body2" sx={{ color: 'text.secondary' }} data-testid="Dashboard.Health.NotComputed">
        Health signals have not been computed for this project yet, so this panel is empty
        because nothing has been checked — not because nothing is wrong.
      </Typography>
    );
  }

  if (data.health.length === 0) {
    return (
      <Typography variant="body2" sx={{ color: 'text.secondary' }} data-testid="Dashboard.Health.Clear">
        No health signal was raised. Last checked {formatDateTime(data.healthComputedAt)}.
      </Typography>
    );
  }

  return (
    /* THE FIRST TILE IS THE WORST ONE, and that is the server's doing rather than this
       component's — `DashboardService.health` sorts by severity and then by count descending
       (§184). Do not sort here. This is §179's rule at the case grid, applied to the second of
       the two screens that show a health severity: the ranking is a property of the answer, and a
       client-side sort is the second copy of it.

       There WAS one, and it was `severityRank` — four severities in a ternary chain, right here,
       thirty lines under the colour table §183 had just deleted for being a second copy of the
       same enum. It was a no-op, and it was one by coincidence: `Array.prototype.sort` is stable
       and its key was the server comparator's FIRST key, so the server's count-descending
       tiebreak survived a sort that had never heard of it. It was also not exhaustive — a fifth
       `Severity` member would have ranked bottom silently, where `severityVar` beside it is a
       `satisfies Record<…>` and would have failed the build. */
    <TileRow
      tiles={data.health}
      labels={healthLabels}
      casesPath={casesPath}
      testId="Dashboard.Health"
    />
  );
}

/**
 * Tiles carry their filter predicate as query parameters. Serializing it here — rather than
 * hard-coding a path per tile — is what makes the set extensible: a new health signal on the
 * server produces a working, drillable tile with no frontend change.
 *
 * **That claim is about the *link*, and §180 is where it stopped being made about the words too.**
 * The tile used to render a server-sent `label`, defended by this same sentence — but a new signal
 * is a new member of a contract enum, so it reaches `lib/labels.ts` as a compile error long before
 * it reaches a tile, and the grid chip and filter bar need the label anyway. The words were
 * duplicated, not extensible: six of the ten had drifted apart from ours. What survives untouched
 * is this function, which never has to know what a key means.
 */
function tileHref(casesPath: string, filter: Record<string, unknown> | undefined): string {
  const params = new URLSearchParams();
  for (const [key, value] of Object.entries(filter ?? {})) {
    if (value === undefined || value === null || value === '') continue;
    if (Array.isArray(value)) value.forEach((v) => params.append(key, String(v)));
    else params.set(key, String(value));
  }
  const qs = params.toString();
  return qs ? `${casesPath}?${qs}` : casesPath;
}

/**
 * @param labels the catalogue this row's keys belong to — `lifecycleLabels` for the authoring
 *               panel, `healthLabels` for "Needs attention". The row is told which vocabulary it is
 *               rendering because `key` is a bare string on the wire and the two panels draw from
 *               different enums; a key with no entry falls back to itself (see `labelForKey`).
 */
function TileRow({
  tiles,
  labels,
  casesPath,
  testId,
}: {
  tiles: DashboardTile[];
  labels: Record<string, string>;
  casesPath: string;
  testId: string;
}) {
  return (
    <Box
      sx={{
        display: 'grid',
        gridTemplateColumns: 'repeat(auto-fit, minmax(min(100%, 11rem), 1fr))',
        gap: 1.5,
      }}
    >
      {tiles.map((tile) => (
        <RouterLink
          key={tile.key}
          to={tileHref(casesPath, tile.filter)}
          data-testid={`${testId}.Tile.${tile.key}`}
          style={{ textDecoration: 'none' }}
        >
          <Box
            sx={{
              p: 1.75,
              height: '100%',
              border: '1px solid',
              borderColor: 'divider',
              borderRadius: 1,
              borderLeft: '3px solid',
              // §183: the shared table, not a private one. This line read
              // `severityColor[tile.severity ?? 'NEUTRAL']` against a four-key map declared thirty
              // lines up — same keys as the case chip's, one differing value, and INFO was the one
              // that differed. The `??` went with it: `DashboardTile.severity` is required on the
              // wire, so the fallback answered a question the contract does not ask.
              borderLeftColor: severityVar[tile.severity],
              transition: 'border-color 120ms ease, background-color 120ms ease',
              '&:hover': { bgcolor: 'action.hover' },
            }}
          >
            <Typography
              className="identifier"
              sx={{ fontSize: '1.5rem', fontWeight: 600, lineHeight: 1.1, color: 'text.primary' }}
            >
              {formatCount(tile.count)}
            </Typography>
            <Typography variant="body2" sx={{ color: 'text.secondary', mt: 0.25 }}>
              {labelForKey(labels, tile.key)}
            </Typography>
          </Box>
        </RouterLink>
      ))}
    </Box>
  );
}

/**
 * Stacked bars, drawn in CSS. Each *segment* is its own link, so a user can go straight to
 * "the 78 drafts in Checkout" rather than to Checkout and then filtering by hand.
 */
function BreakdownList({
  rows,
  casesPath,
  testId,
  emptyMessage,
}: {
  rows: DashboardBreakdown[];
  casesPath: string;
  testId: string;
  emptyMessage: string;
}) {
  /*
    A breakdown with no rows renders an empty bordered box — a panel with a heading and nothing
    under it, which reads as a failed load rather than as "nobody has classified anything yet".
    Live data reaches this immediately: a project's Feature vocabulary starts empty by design
    (inventing a client's taxonomy is how a controlled vocabulary gets worked around), so this is
    the FIRST state of the panel on every real project, not an edge case.
  */
  if (rows.length === 0) {
    return (
      <Typography variant="body2" sx={{ color: 'text.secondary' }} data-testid={`${testId}.Empty`}>
        {emptyMessage}
      </Typography>
    );
  }

  const max = Math.max(
    1,
    ...rows.map((row) => row.segments.reduce((sum, segment) => sum + segment.count, 0)),
  );

  return (
    <Stack sx={{ gap: 1.5 }}>
      {rows.map((row) => {
        const total = row.segments.reduce((sum, segment) => sum + segment.count, 0);
        return (
          <Box key={row.id}>
            <Stack direction="row" sx={{ justifyContent: 'space-between', mb: 0.5 }}>
              <Typography variant="body2" sx={{ fontWeight: 500 }}>
                {row.label}
              </Typography>
              <Identifier variant="muted">{formatCount(total)}</Identifier>
            </Stack>
            <Stack
              direction="row"
              sx={{ height: 14, width: `${(total / max) * 100}%`, minWidth: 40, borderRadius: 0.5, overflow: 'hidden' }}
            >
              {row.segments.map((segment) => {
                // §180: a segment's key is a LifecycleStatus, and both its words and its colour
                // come from this side now. `row.label` above is the opposite case and stays as it
                // arrives — a feature path or a person's name is data only the server can resolve.
                const segmentLabel = labelForKey(lifecycleLabels, segment.key);
                return (
                <Tooltip
                  key={segment.key}
                  title={`${segmentLabel}: ${formatCount(segment.count)} — open these cases`}
                >
                  <Box
                    component={RouterLink}
                    to={tileHref(casesPath, segment.filter)}
                    data-testid={`${testId}.Segment.${segment.key}`}
                    aria-label={`${row.label}, ${segmentLabel}: ${segment.count} cases`}
                    sx={{
                      flex: segment.count,
                      // A status this build has never heard of gets the muted grey rather than
                      // `undefined`, which paints a transparent — invisible, still clickable —
                      // slice of a bar.
                      bgcolor: lifecycleVar[segment.key as LifecycleStatus] ?? 'var(--color-text-muted)',
                      display: 'block',
                      minWidth: 2,
                      '&:hover': { filter: 'brightness(1.15)' },
                    }}
                  />
                </Tooltip>
                );
              })}
            </Stack>
          </Box>
        );
      })}
    </Stack>
  );
}

function AutomationCoverage({
  coverage,
  casesPath,
}: {
  coverage: RepositoryDashboard['automationCoverage'];
  casesPath: string;
}) {
  const rows = [
    { key: 'AUTOMATED' as const, count: coverage.automated },
    { key: 'TO_BE_AUTOMATED' as const, count: coverage.toBeAutomated },
    { key: 'MANUAL' as const, count: coverage.manual },
    { key: 'NOT_AUTOMATABLE' as const, count: coverage.notAutomatable },
  ];
  const total = rows.reduce((sum, row) => sum + row.count, 0);

  return (
    <Stack direction={{ xs: 'column', sm: 'row' }} sx={{ gap: 4, alignItems: { sm: 'center' } }}>
      <Box>
        <Typography className="identifier" sx={{ fontSize: '2rem', fontWeight: 600, lineHeight: 1 }}>
          {formatPercent(coverage.coveragePercent)}
        </Typography>
        {/*
          The denominator is stated, not implied. "57% automated" is meaningless unless the
          reader knows whether the 64 not-automatable cases are in the divisor — and honest
          coverage excludes them, because you cannot automate them.
        */}
        <Typography variant="caption" sx={{ color: 'text.secondary', display: 'block', maxWidth: '32ch' }}>
          of cases that can be automated. Cases marked not automatable are excluded from the
          denominator.
        </Typography>
      </Box>

      <Stack sx={{ gap: 0.75, flex: 1, minWidth: 0 }}>
        {rows.map((row) => (
          <Stack key={row.key} direction="row" sx={{ alignItems: 'center', gap: 1.5 }}>
            <Box
              component={RouterLink}
              to={`${casesPath}?automationStatus=${row.key}`}
              data-testid={`Dashboard.Automation.${row.key}`}
              sx={{ width: 150, flexShrink: 0, fontSize: '0.8125rem', color: 'text.primary' }}
            >
              {automationLabels[row.key]}
            </Box>
            <Box sx={{ flex: 1, height: 10, bgcolor: 'action.hover', borderRadius: 0.5, overflow: 'hidden' }}>
              <Box
                sx={{
                  width: `${(row.count / Math.max(total, 1)) * 100}%`,
                  height: '100%',
                  bgcolor: row.key === 'AUTOMATED' ? 'var(--color-success)' : 'var(--color-skipped)',
                }}
              />
            </Box>
            <Identifier variant="muted" sx={{ width: 48, textAlign: 'right' }}>
              {formatCount(row.count)}
            </Identifier>
          </Stack>
        ))}
      </Stack>
    </Stack>
  );
}

/**
 * A 30-day activity chart, in CSS grid. No chart library: two series over thirty points
 * does not justify the bundle, and a hand-drawn bar column can carry a real accessible
 * label per day, which most chart wrappers make awkward.
 *
 * **There used to be an "Approved" series and it was removed with the contract's field.**
 * Nothing in the schema records *when* a case was approved — `lifecycleStatus` is current state,
 * and the audit trail deliberately stores the version number rather than a field diff — so the
 * server can only ever have sent zeros. A flat zero line labelled "Approved" states that nobody
 * approved anything this month, which is a claim about the client's work rather than an admission
 * that it is not measured. It comes back when the review workflow (§6g) gives approval a
 * timestamp.
 */
function ActivityChart({ activity }: { activity: RepositoryDashboard['recentActivity'] }) {
  const max = Math.max(1, ...activity.map((d) => Math.max(d.created, d.modified)));

  return (
    <Box>
      <Stack direction="row" sx={{ gap: 2, mb: 1.5 }}>
        {[
          { label: 'Created', color: 'var(--color-info)' },
          { label: 'Modified', color: 'var(--color-skipped)' },
        ].map((series) => (
          <Stack key={series.label} direction="row" sx={{ alignItems: 'center', gap: 0.75 }}>
            <Box aria-hidden="true" sx={{ width: 10, height: 10, borderRadius: 0.25, bgcolor: series.color }} />
            <Typography variant="caption">{series.label}</Typography>
          </Stack>
        ))}
      </Stack>

      <Stack direction="row" sx={{ gap: 0.5, height: 96, alignItems: 'flex-end' }} data-testid="Dashboard.Activity.Chart">
        {activity.map((day) => (
          <Tooltip
            key={day.date}
            title={`${day.date} — ${day.created} created, ${day.modified} modified`}
          >
            <Stack
              direction="row"
              sx={{ flex: 1, gap: '1px', alignItems: 'flex-end', height: '100%', minWidth: 0 }}
              aria-label={`${day.date}: ${day.created} created, ${day.modified} modified`}
            >
              {/*
                `minHeight: '1px'` as a STRING, and that is not a style preference. MUI's sizing
                system transforms a unitless number ≤ 1 into a percentage, so `minHeight: 1` means
                `minHeight: 100%` — which forced EVERY bar to full height and drew a month of
                uniform activity for a project that had one busy day. It survived three browser
                passes because the mock fixture has activity on most days, so a chart that was
                always wrong looked like a chart with data in it.
              */}
              <Box sx={{ flex: 1, height: `${(day.created / max) * 100}%`, bgcolor: 'var(--color-info)', minHeight: '1px' }} />
              <Box sx={{ flex: 1, height: `${(day.modified / max) * 100}%`, bgcolor: 'var(--color-skipped)', minHeight: '1px' }} />
            </Stack>
          </Tooltip>
        ))}
      </Stack>
      <Stack direction="row" sx={{ justifyContent: 'space-between', mt: 0.5 }}>
        <Typography variant="caption" sx={{ color: 'text.secondary' }}>
          {activity[0]?.date}
        </Typography>
        <Typography variant="caption" sx={{ color: 'text.secondary' }}>
          {activity[activity.length - 1]?.date}
        </Typography>
      </Stack>
    </Box>
  );
}
