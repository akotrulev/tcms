import { Badge, Banner } from '../../components/kit';
import {
  Button,
  Tooltip,
  TooltipContent,
  TooltipTrigger,
} from '../../components/ui';
import { useState, type CSSProperties } from 'react';
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
    <Panel title="Reliability" testId="CaseDetail.Flake" plain>
      <div className="flex flex-col gap-3">
        {quarantined && flake && (
          <div data-testid="CaseDetail.Flake.Quarantined">
<Banner tone="warn">
            <p className="m-0 text-[13px] font-semibold mb-0.5">
              Quarantined
            </p>
            {/*
              The reason is a sentence somebody typed, rendered verbatim — never split, never
              looked up. §121 phase 3 made `QUARANTINE` its own work-item kind for exactly this:
              filed under `CASE_MAINTENANCE` the board splits a description on commas and labels
              each fragment as a health code, so "Flaky on Firefox, races the login redirect"
              renders as two fragments joined by a middot with nothing failing anywhere.
            */}
            <p className="m-0 text-[13px] whitespace-pre-wrap">
              {flake.quarantineReason}
            </p>
            <span className="m-0 text-[12px] block mt-1 text-ink-2">
              {/*
                `quarantinedBy` is a UserRef since this slice — it was the only bare-uuid actor
                field on the API. It can still be null when the id resolves to no account (an
                offboarded colleague), and "somebody" is the honest answer there: inventing a name
                is worse, and hiding the line loses the date too.
              */}
              by {flake.quarantinedBy?.displayName ?? 'somebody no longer in this company'} ·{' '}
              {formatDateTime(flake.quarantinedAt)}
            </span>
          </Banner>
</div>
        )}

        <div className="flex gap-2 items-baseline flex-wrap">
          {verdict.kind === 'SCORED' ? (
            <>
              <Badge
                tone="plain"
                data-testid="CaseDetail.Flake.Band"
                style={bandStyle(verdict.band)}
              >
                {flakeBandLabels[verdict.band]}
              </Badge>
              <Tooltip>
<TooltipTrigger asChild>
                <span>
                  <Identifier variant="muted">{formatRatio(verdict.score)}</Identifier>
                </span>
              </TooltipTrigger>
<TooltipContent>Alternations divided by repeat runs under identical conditions — not by the number of results.</TooltipContent>
</Tooltip>
            </>
          ) : (
            <p className="m-0 text-[13px] text-ink-2" data-testid="CaseDetail.Flake.NoScore">
              {verdict.kind === 'UNMEASURED'
                ? 'Not assessed yet.'
                : 'Not enough evidence to score.'}
            </p>
          )}
        </div>

        {evidence && (
          <p className="m-0 text-[13px] text-ink-2" data-testid="CaseDetail.Flake.Evidence">
            {evidence}
          </p>
        )}

        {verdict.kind === 'UNMEASURED' && (
          <p className="m-0 text-[13px] text-ink-2">
            Flakiness is measured from results recorded against one version on one configuration.
            Nothing has been computed for this case.
          </p>
        )}

        {/*
          `formatComputedAt` renders a null as "Computed now" — a positive statement rather than the
          absence of a warning (§101). Only shown when a row exists: with none there is nothing to
          be current or stale about, and the line would claim a measurement that was never made.
        */}
        {flake && (
          <span className="m-0 text-[12px] text-ink-2" data-testid="CaseDetail.Flake.ComputedAt">
            {formatComputedAt(flake.computedAt)}
          </span>
        )}

        <PermissionGate projectId={projectId} permission="case:edit">
          {({ allowed }) => (
            <Tooltip>
<TooltipTrigger asChild>
              <span className="self-start">
                <Button
                  size="sm"
                  variant="ghost"
                  color={quarantined ? 'primary' : 'warning'}
                  disabled={!allowed}
                  onClick={() => setConfirming(quarantined ? 'RELEASE' : 'QUARANTINE')}
                  data-testid="CaseDetail.Flake.QuarantineButton"
                >
                  {quarantined ? 'Return to the gate' : 'Quarantine test'}
                </Button>
              </span>
            </TooltipTrigger>
<TooltipContent>{
                allowed
                  ? ''
                  : 'You need permission to edit test cases in this project to change a quarantine.'
              }</TooltipContent>
</Tooltip>
          )}
        </PermissionGate>
      </div>

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
/** Real CSS properties since T16 — ~~an `sx` object~~; there is no engine to interpret one. */
function bandStyle(band: FlakeBand): CSSProperties {
  const token =
    band === 'STABLE' ? null : band === 'HIGH' ? 'var(--color-failed)' : 'var(--color-blocked)';
  if (token === null) {
    return { backgroundColor: 'transparent', borderColor: 'rgb(255 255 255 / 8%)' };
  }
  return {
    backgroundColor: 'transparent',
    color: token,
    borderColor: `color-mix(in srgb, ${token} 45%, transparent)`,
  };
}
