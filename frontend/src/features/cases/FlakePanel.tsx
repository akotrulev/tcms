import { Alert, Box, Button, Chip, Stack, Tooltip, Typography } from '@mui/material';
import ScienceOutlinedIcon from '@mui/icons-material/ScienceOutlined';
import { useState } from 'react';
import type { TestCase } from '../../api/types';
import { Identifier } from '../../components/Identifier';
import { Panel } from '../../components/PageHeader';
import { PermissionGate } from '../../components/PermissionGate';
import { describeFlake, flakeBandLabels, flakeEvidence, type FlakeBand } from '../../lib/flake';
import { formatComputedAt, formatDateTime, formatRatio } from '../../lib/format';
import { QuarantineDialog } from './QuarantineDialog';

/**
 * The flake panel — how unreliable this test is, and whether somebody has taken it out of the gate.
 *
 * ## The one thing this panel must not do
 *
 * **Render a null `flakeScore` as `0`.** They are opposite claims: `0` is *measured and evidently
 * stable*, null is *not enough evidence to say*, and `score ?? 0` type-checks. The distinction is
 * carried by `lib/flake.ts`'s three-state verdict rather than by a conditional here, so the branch
 * that gets it wrong is one a unit test can reach without mounting anything — and there is a third
 * state above both: `flake === null`, meaning no row exists and nobody has looked.
 *
 * That is why this panel never renders a bare em dash. Each of the three no-number states gets its
 * own sentence, and the two that have counts show them: *"3 results, 2 repeat runs under identical
 * conditions — too few to score"* is the shape §95's `usage_count` and §100's exit criteria both
 * needed and did not have.
 *
 * ## Quarantine is an action, not a field
 *
 * `ui-rules` §3: a state transition is an explicit action and never an editable control standing in
 * for one. So there is no switch here — two buttons in two directions, each behind a confirmation
 * that names the case, and the gate is **`case:edit`**, which is the whole security argument of the
 * slice. Automation Service does not hold it, so a leaked CI token can post results and cannot
 * quarantine the tests those results fail.
 *
 * Disable-don't-hide (§31): a caller without the permission sees the button disabled with the
 * reason, rather than a panel that quietly offers less than a colleague's.
 */
export function FlakePanel({ projectId, testCase }: { projectId: string; testCase: TestCase }) {
  const [confirming, setConfirming] = useState<'QUARANTINE' | 'RELEASE' | null>(null);
  const flake = testCase.flake;
  const verdict = describeFlake(flake);
  const evidence = flakeEvidence(verdict);
  const quarantined = flake?.quarantined ?? false;

  return (
    <Panel title="Reliability" testId="CaseDetail.Flake">
      <Stack sx={{ gap: 1.5 }}>
        {quarantined && flake && (
          <Alert
            severity="warning"
            icon={<ScienceOutlinedIcon fontSize="small" />}
            data-testid="CaseDetail.Flake.Quarantined"
            sx={{ py: 0.5 }}
          >
            <Typography variant="body2" sx={{ fontWeight: 600, mb: 0.25 }}>
              Quarantined
            </Typography>
            {/*
              The reason is a sentence somebody typed, rendered verbatim — never split, never
              looked up. §121 phase 3 made `QUARANTINE` its own work-item kind for exactly this:
              filed under `CASE_MAINTENANCE` the board splits a description on commas and labels
              each fragment as a health code, so "Flaky on Firefox, races the login redirect"
              renders as two fragments joined by a middot with nothing failing anywhere.
            */}
            <Typography variant="body2" sx={{ whiteSpace: 'pre-wrap' }}>
              {flake.quarantineReason}
            </Typography>
            <Typography variant="caption" sx={{ display: 'block', mt: 0.5, color: 'text.secondary' }}>
              {/*
                `quarantinedBy` is a UserRef since this slice — it was the only bare-uuid actor
                field on the API. It can still be null when the id resolves to no account (an
                offboarded colleague), and "somebody" is the honest answer there: inventing a name
                is worse, and hiding the line loses the date too.
              */}
              by {flake.quarantinedBy?.displayName ?? 'somebody no longer in this company'} ·{' '}
              {formatDateTime(flake.quarantinedAt)}
            </Typography>
          </Alert>
        )}

        <Stack direction="row" sx={{ gap: 1, alignItems: 'baseline', flexWrap: 'wrap' }}>
          {verdict.kind === 'SCORED' ? (
            <>
              <Chip
                size="small"
                label={flakeBandLabels[verdict.band]}
                data-testid="CaseDetail.Flake.Band"
                sx={bandStyle(verdict.band)}
              />
              <Tooltip title="Alternations divided by repeat runs under identical conditions — not by the number of results.">
                <Box component="span">
                  <Identifier variant="muted">{formatRatio(verdict.score)}</Identifier>
                </Box>
              </Tooltip>
            </>
          ) : (
            <Typography
              variant="body2"
              sx={{ color: 'text.secondary' }}
              data-testid="CaseDetail.Flake.NoScore"
            >
              {verdict.kind === 'UNMEASURED'
                ? 'Not assessed yet.'
                : 'Not enough evidence to score.'}
            </Typography>
          )}
        </Stack>

        {evidence && (
          <Typography variant="body2" sx={{ color: 'text.secondary' }} data-testid="CaseDetail.Flake.Evidence">
            {evidence}
          </Typography>
        )}

        {verdict.kind === 'UNMEASURED' && (
          <Typography variant="body2" sx={{ color: 'text.secondary' }}>
            Flakiness is measured from results recorded against one version on one configuration.
            Nothing has been computed for this case.
          </Typography>
        )}

        {/*
          `formatComputedAt` renders a null as "Computed now" — a positive statement rather than the
          absence of a warning (§101). Only shown when a row exists: with none there is nothing to
          be current or stale about, and the line would claim a measurement that was never made.
        */}
        {flake && (
          <Typography variant="caption" sx={{ color: 'text.secondary' }} data-testid="CaseDetail.Flake.ComputedAt">
            {formatComputedAt(flake.computedAt)}
          </Typography>
        )}

        <PermissionGate projectId={projectId} permission="case:edit">
          {({ allowed }) => (
            <Tooltip
              title={
                allowed
                  ? ''
                  : 'You need permission to edit test cases in this project to change a quarantine.'
              }
            >
              <Box component="span" sx={{ alignSelf: 'flex-start' }}>
                <Button
                  size="small"
                  variant="outlined"
                  color={quarantined ? 'primary' : 'warning'}
                  disabled={!allowed}
                  onClick={() => setConfirming(quarantined ? 'RELEASE' : 'QUARANTINE')}
                  data-testid="CaseDetail.Flake.QuarantineButton"
                >
                  {quarantined ? 'Return to the gate' : 'Quarantine test'}
                </Button>
              </Box>
            </Tooltip>
          )}
        </PermissionGate>
      </Stack>

      {/*
        Mounted only while confirming, so the dialog's reset-on-open effect starts from a clean
        mutation each time — `CaseDeleteDialog`'s rule, one panel over. `quarantining` is passed
        from the row's state at the moment the button was pressed and is never re-derived inside the
        dialog, so a background refetch cannot turn a release into a quarantine mid-confirmation.
      */}
      {confirming !== null && (
        <QuarantineDialog
          open
          onClose={() => setConfirming(null)}
          projectId={projectId}
          testCase={testCase}
          quarantining={confirming === 'QUARANTINE'}
        />
      )}
    </Panel>
  );
}

/**
 * Colour by band, and **`STABLE` is deliberately not green.**
 *
 * A green badge reads as a passing test, and this measures reliability rather than outcome — a test
 * that never flakes and fails every time would wear it. Neutral for stable, warning for the two
 * middle bands, failure for the top one.
 */
function bandStyle(band: FlakeBand) {
  const token =
    band === 'STABLE' ? null : band === 'HIGH' ? 'var(--color-failed)' : 'var(--color-blocked)';
  if (token === null) return { bgcolor: 'transparent', border: '1px solid', borderColor: 'divider' };
  return {
    bgcolor: 'transparent',
    color: token,
    border: `1px solid color-mix(in srgb, ${token} 45%, transparent)`,
  };
}
