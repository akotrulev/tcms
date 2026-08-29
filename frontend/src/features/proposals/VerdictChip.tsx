import type { ProposalVerdict, VerdictSource } from '../../api/types';
import { Tooltip, TooltipContent, TooltipTrigger } from '../../components/ui';
import {
  proposalVerdictColors,
  proposalVerdictLabels,
  verdictSourceLabels,
} from '../../lib/labels';

/**
 * A proposal's verdict **and who claimed it**, in one component that cannot render one without the
 * other.
 *
 * ## Why this is a component rather than two chips at each call site
 *
 * §6w.1a spends its length on one rule: an agent's asserted verdict is recorded, shown, attributed
 * — and **never counted as verified**, because TCMS cannot see that agent's critic, its isolation
 * or its prompt. The server enforces its half (there is no request field for `verdictSource`, and
 * the column's `CHECK` has no `TCMS_VERIFIED` value yet). This is the client's half, and the way to
 * enforce it in a UI is to make the honest rendering the *only* rendering available: both props are
 * required, so a screen cannot show "Grounded" without showing who says so.
 *
 * The alternative — a `<Chip label={verdictLabels[v]} />` at three call sites and a note in a
 * review — is exactly the shape `lib/labels.ts` exists to prevent: a hundred consistent screens
 * becoming a hundred slightly different ones, except that here the difference is a claim promoted
 * to a finding.
 *
 * ## No green, ever
 *
 * {@link proposalVerdictColors} has no `--color-passed` in it. Green is what this product uses for
 * something it verified; a proposal's verdict is at best somebody else's critic's opinion, and
 * rendering the two in the same colour makes them look like the same kind of statement on the one
 * screen whose whole job is telling them apart.
 */
export function VerdictChip({
  verdict,
  source,
}: {
  verdict: ProposalVerdict;
  source: VerdictSource;
}) {
  return (
    <span className="inline-flex flex-wrap items-center gap-1.5">
      <Tooltip>
        <TooltipTrigger asChild>
          {/*
            An outline and no tint, and the colour comes straight from `proposalVerdictColors` —
            a CSS custom property per verdict. `kit/Badge`'s eight tones are deliberately not used
            here: `--color-draft` and `--color-info` are not among them, and mapping onto the
            nearest one would be this file quietly choosing a colour the labels module already
            chose. See the header on why none of them is green.
          */}
          <span
            data-testid="Proposals.Verdict"
            className="inline-flex items-center rounded-full border px-2 py-0.5 text-[11.5px] whitespace-nowrap"
            style={{
              borderColor: proposalVerdictColors[verdict],
              color: proposalVerdictColors[verdict],
            }}
          >
            {proposalVerdictLabels[verdict]}
          </span>
        </TooltipTrigger>
        <TooltipContent>{verdictSourceLabels[source]}</TooltipContent>
      </Tooltip>
      {/*
        The source is rendered as TEXT beside the chip, not only as the tooltip above it. A tooltip
        is not available to somebody skimming, and "who checked" is the half a skimmer most needs:
        the failure mode this component exists for is reading `Grounded` and stopping there.
      */}
      <span className="text-xs text-ink-2" data-testid="Proposals.VerdictSource">
        {verdictSourceLabels[source]}
      </span>
    </span>
  );
}
