import { Box, Button, Stack, Tooltip, Typography } from '@mui/material';
import { Link as RouterLink, useParams } from 'react-router-dom';
import type { DashboardBreakdown, DashboardTile, RepositoryDashboard } from '../../api/types';
import { useDashboard, useProject } from '../../api/queries';
import { DataRegion } from '../../components/DataRegion';
import { Identifier } from '../../components/Identifier';
import { PageHeader, Panel } from '../../components/PageHeader';
import { formatCount, formatDateTime, formatPercent } from '../../lib/format';
import { automationLabels } from '../../lib/labels';

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
            </>
          )
        }
        subtitle={project.data?.description}
        actions={
          <Button
            component={RouterLink}
            to={`/projects/${projectId}/cases`}
            variant="contained"
            data-testid="Dashboard.BrowseCasesButton"
          >
            Browse test cases
          </Button>
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

function DashboardBody({ data, projectId }: { data: RepositoryDashboard; projectId: string }) {
  const casesPath = `/projects/${projectId}/cases`;

  return (
    <Stack sx={{ gap: 3 }}>
      <Typography variant="caption" sx={{ color: 'text.secondary' }} data-testid="Dashboard.GeneratedAt">
        Counted at {formatDateTime(data.generatedAt)}
      </Typography>

      <Panel title="Authoring status" testId="Dashboard.Lifecycle">
        <TileRow tiles={data.lifecycle} casesPath={casesPath} testId="Dashboard.Lifecycle" />
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
    <TileRow
      tiles={[...data.health].sort((a, b) => severityRank(a.severity) - severityRank(b.severity))}
      casesPath={casesPath}
      testId="Dashboard.Health"
    />
  );
}

function severityRank(severity: DashboardTile['severity']): number {
  return severity === 'ERROR' ? 0 : severity === 'WARNING' ? 1 : severity === 'INFO' ? 2 : 3;
}

/**
 * Tiles carry their filter predicate as query parameters. Serializing it here — rather than
 * hard-coding a path per tile — is what makes the set extensible: a new health signal on the
 * server produces a working, drillable tile with no frontend change.
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

const severityColor: Record<NonNullable<DashboardTile['severity']>, string> = {
  ERROR: 'var(--color-failed)',
  WARNING: 'var(--color-blocked)',
  INFO: 'var(--color-info)',
  NEUTRAL: 'var(--color-text-muted)',
};

function TileRow({
  tiles,
  casesPath,
  testId,
}: {
  tiles: DashboardTile[];
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
              borderLeftColor: severityColor[tile.severity ?? 'NEUTRAL'],
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
              {tile.label}
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
              {row.segments.map((segment) => (
                <Tooltip
                  key={segment.key}
                  title={`${segment.label}: ${formatCount(segment.count)} — open these cases`}
                >
                  <Box
                    component={RouterLink}
                    to={tileHref(casesPath, segment.filter)}
                    data-testid={`${testId}.Segment.${segment.key}`}
                    aria-label={`${row.label}, ${segment.label}: ${segment.count} cases`}
                    sx={{
                      flex: segment.count,
                      bgcolor: segment.color,
                      display: 'block',
                      minWidth: 2,
                      '&:hover': { filter: 'brightness(1.15)' },
                    }}
                  />
                </Tooltip>
              ))}
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
                  bgcolor: row.key === 'AUTOMATED' ? 'var(--color-passed)' : 'var(--color-skipped)',
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
