import {
  Alert,
  Box,
  Chip,
  MenuItem,
  Select,
  Stack,
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableRow,
  Tooltip,
  Typography,
} from '@mui/material';
import CheckCircleOutlineIcon from '@mui/icons-material/CheckCircleOutlineOutlined';
import ErrorOutlineIcon from '@mui/icons-material/ErrorOutlineOutlined';
import HelpOutlineIcon from '@mui/icons-material/HelpOutlineOutlined';
import { useState } from 'react';
import { Link as RouterLink, useParams, useSearchParams } from 'react-router-dom';
import type { AggregationPolicy, Criterion, PlanStatistics } from '../../api/types';
import { usePlan, usePlanStatistics, useProject } from '../../api/queries';
import { DataRegion } from '../../components/DataRegion';
import { Identifier } from '../../components/Identifier';
import { PageHeader, Panel } from '../../components/PageHeader';
import { DenominatorToggle, PassRateSummary, ResultBar, ResultCountsCell } from '../../components/ResultBar';
import { criterionState, type Denominator } from '../../lib/results';
import { StatusChip } from '../../components/StatusChip';
import { formatCount, formatDateTime, formatPercent } from '../../lib/format';
import { aggregationHelp, aggregationLabels } from '../../lib/labels';

/**
 * Plan statistics (plan §6p).
 *
 * Three decisions drive this screen:
 *
 *   1. **Pass rate and coverage sit side by side.** 89% pass over 97% coverage is a very
 *      different release position from 89% over 40%, and a screen that shows only the first
 *      number invites the wrong call.
 *   2. **The aggregation policy is displayed, always.** A plan whose cases ran across three
 *      configurations has several defensible pass rates; without naming the arithmetic, two
 *      teams reading "89%" mean different things and neither knows it.
 *   3. **Blockers and unexecuted cases are listed, not counted.** "21 failed" is a number;
 *      the 21 named cases with their defect links are a work list.
 */
export function PlanStatisticsPage() {
  const { projectId = '', planId = '' } = useParams();
  const [params, setParams] = useSearchParams();

  // The policy lives in the URL: a plan report sent to a release meeting must arrive with
  // the arithmetic it was read under, not reset to the default for the next reader.
  const aggregation = (params.get('aggregation') ?? 'LATEST') as AggregationPolicy;

  const project = useProject(projectId);
  const plan = usePlan(projectId, planId);
  const statistics = usePlanStatistics(projectId, planId, aggregation);

  return (
    <Box>
      <PageHeader
        testId="PlanStats.Header"
        title={plan.data?.name ?? 'Test plan'}
        documentTitle={plan.data ? `${plan.data.key} ${plan.data.name}` : 'Test plan'}
        crumbs={[
          { label: project.data?.name ?? 'Project', to: `/projects/${projectId}` },
          { label: 'Test plans', to: `/projects/${projectId}/plans` },
          { label: plan.data?.key ?? 'Plan' },
        ]}
        meta={
          plan.data && (
            <>
              <Identifier variant="strong">{plan.data.key}</Identifier>
              <Chip size="small" variant="outlined" label={plan.data.status.toLowerCase()} />
              {plan.data.milestoneName && (
                <Typography variant="body2" sx={{ color: 'text.secondary' }}>
                  {plan.data.milestoneName}
                  {plan.data.milestoneDate && ` · due ${plan.data.milestoneDate}`}
                </Typography>
              )}
            </>
          )
        }
        subtitle={plan.data?.description}
        actions={
          <Box sx={{ minWidth: 260 }}>
            <Select
              size="small"
              fullWidth
              value={aggregation}
              onChange={(event) => {
                const next = new URLSearchParams(params);
                next.set('aggregation', event.target.value);
                setParams(next, { replace: true });
              }}
              data-testid="PlanStats.AggregationSelect"
              inputProps={{ 'aria-label': 'How results are aggregated' }}
            >
              {Object.entries(aggregationLabels).map(([value, label]) => (
                <MenuItem key={value} value={value}>
                  {label}
                </MenuItem>
              ))}
            </Select>
          </Box>
        }
      />

      <DataRegion testId="PlanStats.Content" state={{ ...statistics, refetch: () => void statistics.refetch() }}>
        {(data) => <StatisticsBody data={data} projectId={projectId} aggregation={aggregation} />}
      </DataRegion>
    </Box>
  );
}

function StatisticsBody({
  data,
  projectId,
  aggregation,
}: {
  data: PlanStatistics;
  projectId: string;
  aggregation: AggregationPolicy;
}) {
  const [denominator, setDenominator] = useState<Denominator>('EXECUTED');
  const notExecuted = data.counts.total === 0 || data.counts.total === data.counts.notRun;

  return (
    <Stack sx={{ gap: 3 }}>
      {/*
        The policy statement is part of the report, not a control's side effect. Anyone
        reading a screenshot of this page can tell which arithmetic produced the numbers.
      */}
      <Alert severity="info" icon={false} data-testid="PlanStats.AggregationNotice">
        <Typography variant="body2">
          <strong>{aggregationLabels[aggregation]}.</strong> {aggregationHelp[aggregation]}
        </Typography>
      </Alert>

      {notExecuted ? (
        <Panel testId="PlanStats.NotExecuted">
          <Typography variant="h4" sx={{ mb: 0.5 }}>
            Not executed yet
          </Typography>
          <Typography variant="body2" sx={{ color: 'text.secondary' }}>
            This plan has {formatCount(data.counts.total)} cases in scope and no results. Start a run
            to begin recording them.
          </Typography>
        </Panel>
      ) : (
        <>
          <Panel testId="PlanStats.Summary">
            <Stack sx={{ gap: 2 }}>
              <PassRateSummary
                counts={data.counts}
                coveragePercent={data.coveragePercent}
                testId="PlanStats.PassRate"
              />
              <ResultBar counts={data.counts} height={14} showLegend testId="PlanStats.ResultBar" />
              {data.latestExecutionAt && (
                /*
                  This caption used to read "the read model is catching up, so counts may lag by
                  a few seconds" — the opposite warning to the one `stale` carries. The figures
                  are recomputed per request and never lag; `stale` means NOTHING HAS BEEN
                  EXECUTED IN TWO WEEKS, and plan §6p calls a page showing old numbers as current
                  the most dangerous screen in the product. Reassuring the reader was exactly the
                  wrong response, and it went unnoticed because until the endpoint went live the
                  mock never set the flag.
                */
                <Typography
                  variant="caption"
                  sx={{ color: data.stale ? 'var(--color-blocked)' : 'text.secondary' }}
                  data-testid="PlanStats.Freshness"
                >
                  Latest result {formatDateTime(data.latestExecutionAt)}
                  {data.stale
                    && ' — nothing has been executed against this plan in over two weeks. These'
                      + ' numbers describe the past, not the build you are about to ship.'}
                </Typography>
              )}
            </Stack>
          </Panel>

          <ExitCriteria criteria={data.exitCriteria ?? []} />

          <Stack direction={{ xs: 'column', lg: 'row' }} sx={{ gap: 3, alignItems: 'stretch' }}>
            <Box sx={{ flex: 1, minWidth: 0 }}>
              <Panel
                title="By configuration"
                testId="PlanStats.ByConfiguration"
                actions={
                  <DenominatorToggle
                    value={denominator}
                    onChange={setDenominator}
                    testId="PlanStats.Denominator"
                  />
                }
              >
                <BreakdownTable
                  rows={(data.byConfiguration ?? []).map((row) => ({
                    id: row.configuration,
                    label: row.configuration,
                    counts: row.counts,
                  }))}
                  denominator={denominator}
                  testId="PlanStats.ByConfiguration"
                  emptyBody="No results recorded per configuration yet."
                />
              </Panel>
            </Box>

            <Box sx={{ flex: 1, minWidth: 0 }}>
              <Panel title="By suite" testId="PlanStats.BySuite">
                <BreakdownTable
                  rows={(data.bySuite ?? []).map((row) => ({
                    id: row.suiteId,
                    label: row.suiteName,
                    identifier: row.suiteKey,
                    href: `/projects/${projectId}/cases?suiteId=${row.suiteId}`,
                    counts: row.counts,
                  }))}
                  denominator={denominator}
                  testId="PlanStats.BySuite"
                  emptyBody="No suites in scope."
                />
              </Panel>
            </Box>
          </Stack>

          <TrendChart trend={data.trend ?? []} />

          <Blockers blockers={data.blockers ?? []} projectId={projectId} />

          <NotRun cases={data.notRun ?? []} projectId={projectId} />
        </>
      )}
    </Stack>
  );
}

/**
 * Exit criteria, each stated with its target *and* its actual. A criterion showing only a
 * red cross tells the reader they can't ship but not how far off they are — and "89.1%
 * against a 95% target" is what a release conversation actually needs.
 */
function ExitCriteria({ criteria }: { criteria: Criterion[] }) {
  if (criteria.length === 0) return null;
  // `met: null` is "not evaluated", which is neither met nor unmet. The server returns it for
  // metrics it cannot answer from a plan's own results — open_critical needs the issue tracker,
  // cases_approved the review workflow. Treating null as unmet (`!c.met`) put a red cross and a
  // release-blocking count against a gate nobody had checked; it only became reachable when the
  // statistics endpoint went live, because the mock had always supplied a boolean.
  const unmet = criteria.filter((c) => criterionState(c) === 'UNMET');
  const unevaluated = criteria.filter((c) => criterionState(c) === 'NOT_EVALUATED');

  return (
    <Panel
      title="Exit criteria"
      testId="PlanStats.ExitCriteria"
      actions={
        <Stack direction="row" sx={{ gap: 0.75, alignItems: 'center' }}>
          <Chip
            size="small"
            label={unmet.length === 0 ? 'All met' : `${unmet.length} unmet`}
            data-testid="PlanStats.ExitCriteria.Summary"
            sx={{
              bgcolor: 'transparent',
              color: unmet.length === 0 ? 'var(--color-passed)' : 'var(--color-failed)',
              border: `1px solid color-mix(in srgb, ${
                unmet.length === 0 ? 'var(--color-passed)' : 'var(--color-failed)'
              } 45%, transparent)`,
            }}
          />
          {unevaluated.length > 0 && (
            <Chip
              size="small"
              label={`${unevaluated.length} not evaluated`}
              data-testid="PlanStats.ExitCriteria.Unevaluated"
              sx={{
                bgcolor: 'transparent',
                color: 'text.secondary',
                border: '1px solid color-mix(in srgb, currentColor 30%, transparent)',
              }}
            />
          )}
        </Stack>
      }
    >
      <Stack sx={{ gap: 1 }}>
        {/*
          Without this line the panel contradicts the headline: a pass-rate criterion is
          evaluated against every case in scope, so it reads 89.1% while the summary above
          shows 91.1% "of executed". Both are right; the difference is the denominator, and
          leaving the reader to work that out is precisely the ambiguity this screen exists
          to remove.
        */}
        {criteria.some((c) => c.metric === 'pass_rate') && (
          <Typography
            variant="caption"
            sx={{ color: 'text.secondary', mb: 0.5 }}
            data-testid="PlanStats.ExitCriteria.DenominatorNotice"
          >
            Criteria are evaluated against every case in scope, not only those executed — so a
            pass-rate figure here is lower than the "of executed" rate above until coverage
            reaches 100%.
          </Typography>
        )}
        {criteria.map((criterion, index) => {
          // Three states, not two. A criterion the server could not evaluate is grey and says so,
          // rather than borrowing the failed colour and reading as a blocker.
          const state = criterionState(criterion);
          const color =
            state === 'NOT_EVALUATED'
              ? 'text.secondary'
              : state === 'MET'
                ? 'var(--color-passed)'
                : 'var(--color-failed)';

          return (
            <Stack
              // Metric is not unique — a plan may gate on pass_rate per configuration — so the
              // index is part of the key rather than the whole of it being the metric.
              key={`${criterion.metric}-${index}`}
              direction="row"
              sx={{ alignItems: 'center', gap: 1.5 }}
              data-testid="PlanStats.ExitCriteria.Item"
            >
              <Box sx={{ display: 'flex', color }}>
                {state === 'NOT_EVALUATED' ? (
                  <HelpOutlineIcon fontSize="small" />
                ) : state === 'MET' ? (
                  <CheckCircleOutlineIcon fontSize="small" />
                ) : (
                  <ErrorOutlineIcon fontSize="small" />
                )}
              </Box>
              <Typography variant="body2" sx={{ flex: 1, minWidth: 0 }}>
                {criterion.label}
                {state === 'NOT_EVALUATED' && (
                  <Typography
                    component="span"
                    variant="caption"
                    sx={{ color: 'text.secondary', ml: 1 }}
                    data-testid="PlanStats.ExitCriteria.Item.NotEvaluated"
                  >
                    — not evaluated: nothing in TCMS measures this yet
                  </Typography>
                )}
              </Typography>
              <Typography variant="caption" sx={{ color: 'text.secondary' }}>
                target <Identifier variant="muted">{criterion.op} {criterion.value}</Identifier>
              </Typography>
              <Identifier sx={{ minWidth: 64, textAlign: 'right', color, fontWeight: 600 }}>
                {criterion.actual ?? '—'}
              </Identifier>
            </Stack>
          );
        })}
      </Stack>
    </Panel>
  );
}

interface BreakdownRow {
  id: string;
  label: string;
  identifier?: string;
  href?: string;
  counts: PlanStatistics['counts'];
}

function BreakdownTable({
  rows,
  denominator,
  testId,
  emptyBody,
}: {
  rows: BreakdownRow[];
  denominator: Denominator;
  testId: string;
  emptyBody: string;
}) {
  if (rows.length === 0) {
    return (
      <Typography variant="body2" sx={{ color: 'text.secondary' }}>
        {emptyBody}
      </Typography>
    );
  }

  return (
    <Table size="small" data-testid={`${testId}.Grid`}>
      <TableBody>
        {rows.map((row) => (
          <TableRow key={row.id} data-testid={`${testId}.Row`}>
            <TableCell sx={{ border: 0, pl: 0, width: '40%' }}>
              <Stack direction="row" sx={{ alignItems: 'center', gap: 1, minWidth: 0 }}>
                {row.identifier && <Identifier variant="muted">{row.identifier}</Identifier>}
                {row.href ? (
                  <RouterLink to={row.href} data-testid={`${testId}.Row.Link`}>
                    <Typography variant="body2" component="span">
                      {row.label}
                    </Typography>
                  </RouterLink>
                ) : (
                  <Typography variant="body2">{row.label}</Typography>
                )}
              </Stack>
            </TableCell>
            <TableCell sx={{ border: 0, width: '35%' }}>
              <ResultBar counts={row.counts} />
            </TableCell>
            <TableCell sx={{ border: 0, pr: 0 }} align="right">
              <ResultCountsCell
                counts={row.counts}
                denominator={denominator}
                testId={`${testId}.Row.PassRate`}
              />
            </TableCell>
          </TableRow>
        ))}
      </TableBody>
    </Table>
  );
}

/**
 * Pass-rate trend across the plan's runs. A sparkline, drawn as an inline SVG polyline —
 * six points do not justify a charting dependency, and the run keys underneath make each
 * point identifiable, which is what turns "it dropped" into "it dropped at TR-415".
 */
function TrendChart({ trend }: { trend: NonNullable<PlanStatistics['trend']> }) {
  if (trend.length < 2) return null;

  const width = 100;
  const height = 32;
  const points = trend
    .map((point, i) => {
      const x = (i / (trend.length - 1)) * width;
      const y = height - (point.passRate / 100) * height;
      return `${x.toFixed(2)},${y.toFixed(2)}`;
    })
    .join(' ');

  const latest = trend[trend.length - 1];
  const previous = trend[trend.length - 2];
  const delta = latest.passRate - previous.passRate;

  return (
    <Panel title="Pass rate over the plan's runs" testId="PlanStats.Trend">
      <Stack direction={{ xs: 'column', sm: 'row' }} sx={{ gap: 3, alignItems: { sm: 'center' } }}>
        <Box sx={{ flex: 1, minWidth: 0 }}>
          <Box
            component="svg"
            viewBox={`0 0 ${width} ${height}`}
            preserveAspectRatio="none"
            role="img"
            aria-label={`Pass rate across ${trend.length} runs, most recently ${formatPercent(latest.passRate)}`}
            data-testid="PlanStats.Trend.Chart"
            sx={{ width: '100%', height: 64, overflow: 'visible' }}
          >
            <polyline
              points={points}
              fill="none"
              stroke="var(--color-action-primary)"
              strokeWidth={1.5}
              vectorEffect="non-scaling-stroke"
            />
            {trend.map((point, i) => (
              <circle
                key={point.runKey}
                cx={(i / (trend.length - 1)) * width}
                cy={height - (point.passRate / 100) * height}
                r={2}
                fill="var(--color-action-primary)"
                vectorEffect="non-scaling-stroke"
              />
            ))}
          </Box>
          <Stack direction="row" sx={{ justifyContent: 'space-between', mt: 0.5 }}>
            <Identifier variant="muted">{trend[0].runKey}</Identifier>
            <Identifier variant="muted">{latest.runKey}</Identifier>
          </Stack>
        </Box>

        <Box sx={{ flexShrink: 0 }}>
          <Typography className="identifier" sx={{ fontSize: '1.5rem', fontWeight: 600, lineHeight: 1 }}>
            {formatPercent(latest.passRate)}
          </Typography>
          <Typography
            variant="caption"
            sx={{ color: delta >= 0 ? 'var(--color-passed)' : 'var(--color-failed)' }}
          >
            {delta >= 0 ? '+' : ''}
            {formatPercent(delta)} vs {previous.runKey}
          </Typography>
        </Box>
      </Stack>
    </Panel>
  );
}

function Blockers({
  blockers,
  projectId,
}: {
  blockers: NonNullable<PlanStatistics['blockers']>;
  projectId: string;
}) {
  return (
    <Panel
      title="What's blocking this plan"
      testId="PlanStats.Blockers"
      actions={
        <Typography variant="caption" sx={{ color: 'text.secondary' }}>
          {formatCount(blockers.length)} failed or blocked
        </Typography>
      }
    >
      {blockers.length === 0 ? (
        <Typography variant="body2" sx={{ color: 'text.secondary' }}>
          Nothing failed or blocked. Anything unmet in the exit criteria above is about coverage,
          not defects.
        </Typography>
      ) : (
        <Table size="small" data-testid="PlanStats.Blockers.Grid">
          <TableHead>
            <TableRow>
              <TableCell sx={{ color: 'text.secondary' }}>Case</TableCell>
              <TableCell sx={{ color: 'text.secondary', width: 110 }}>Result</TableCell>
              <TableCell sx={{ color: 'text.secondary', width: 130 }}>Defects</TableCell>
              <TableCell sx={{ color: 'text.secondary', width: 150 }}>Executed</TableCell>
            </TableRow>
          </TableHead>
          <TableBody>
            {blockers.map((blocker) => (
              <TableRow key={blocker.id} data-testid="PlanStats.Blockers.Row">
                <TableCell>
                  <Stack direction="row" sx={{ alignItems: 'center', gap: 1, minWidth: 0 }}>
                    <RouterLink
                      to={`/projects/${projectId}/cases/${blocker.caseId}`}
                      data-testid="PlanStats.Blockers.Row.CaseLink"
                    >
                      <Identifier>{blocker.caseKey}</Identifier>
                    </RouterLink>
                    <Tooltip title={blocker.caseTitle}>
                      <Box sx={{ overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                        {blocker.caseTitle}
                      </Box>
                    </Tooltip>
                    {/* The version that failed, not the current one — a case edited since
                        would otherwise silently rewrite what this row means. */}
                    <Tooltip title="The case version this run executed">
                      <Box>
                        <Identifier variant="muted">v{blocker.caseVersionNo}</Identifier>
                      </Box>
                    </Tooltip>
                  </Stack>
                </TableCell>
                <TableCell>
                  {blocker.status?.kind && <StatusChip kind={blocker.status.kind} />}
                </TableCell>
                <TableCell>
                  {blocker.defects.length === 0 ? (
                    <Typography variant="caption" sx={{ color: 'var(--color-blocked)' }}>
                      No defect linked
                    </Typography>
                  ) : (
                    <Stack sx={{ gap: 0.25 }}>
                      {blocker.defects.map((defect) => (
                        <Box
                          key={defect.id}
                          component="a"
                          href={defect.url}
                          target="_blank"
                          rel="noopener"
                          data-testid="PlanStats.Blockers.Row.DefectLink"
                          sx={{ fontSize: '0.75rem' }}
                        >
                          <Identifier>{defect.externalKey}</Identifier>
                          {defect.statusCache && (
                            <Box component="span" sx={{ color: 'text.secondary', ml: 0.5 }}>
                              {defect.statusCache}
                            </Box>
                          )}
                          <Box component="span" sx={{ ml: 0.5, color: 'text.secondary' }} aria-hidden="true">
                            ↗
                          </Box>
                          <Box component="span" className="sr-only">
                            (opens in a new tab)
                          </Box>
                        </Box>
                      ))}
                    </Stack>
                  )}
                </TableCell>
                <TableCell sx={{ color: 'text.secondary', fontSize: '0.75rem' }}>
                  {formatDateTime(blocker.executedAt)}
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      )}
    </Panel>
  );
}

function NotRun({
  cases,
  projectId,
}: {
  cases: NonNullable<PlanStatistics['notRun']>;
  projectId: string;
}) {
  if (cases.length === 0) return null;

  return (
    <Panel title="Still to execute" testId="PlanStats.NotRun">
      <Stack sx={{ gap: 0.75 }}>
        {cases.map((testCase) => (
          <Stack
            key={testCase.id}
            direction="row"
            sx={{ alignItems: 'center', gap: 1, minWidth: 0 }}
            data-testid="PlanStats.NotRun.Row"
          >
            <RouterLink to={`/projects/${projectId}/cases/${testCase.id}`} data-testid="PlanStats.NotRun.Row.CaseLink">
              <Identifier>{testCase.key}</Identifier>
            </RouterLink>
            <Box sx={{ overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap', fontSize: '0.8125rem' }}>
              {testCase.title}
            </Box>
            <Typography variant="caption" sx={{ color: 'text.secondary', ml: 'auto', flexShrink: 0 }}>
              {testCase.suiteName}
            </Typography>
          </Stack>
        ))}
      </Stack>
    </Panel>
  );
}
