/**
 * Plan §6g's authoring ladder, mirroring the server's `LifecycleLadder` — one table, read by both
 * the suite status handler and the case bulk handler.
 *
 * Two mock handlers needing the same rule is exactly how a mock starts teaching a refusal the
 * server does not give (§52), and this file exists so the wording and the transition table are
 * written once on this side too. The server's class is the source; this is the copy, and the copy
 * is deliberately ONE copy rather than two.
 *
 * The permission half is here as well, because the mock has to refuse the same edges: a Test
 * Engineer must be unable to rescind an approval in mock mode, or the screen that lets them is only
 * discovered against a real backend.
 */
import type { LifecycleStatus } from '../api/types';
import type { ReviewStanding } from './db/reviews';

export type LadderDecision =
  | { kind: 'move'; target: LifecycleStatus }
  | { kind: 'unchanged' }
  | { kind: 'refused'; message: string };

/**
 * How far along the ladder a status sits — drafted, reviewed, approved, retired.
 *
 * The domain's own order, written out rather than read off an array index, for the reason the
 * server's `rank` gives: this decides an authorization question, so re-ordering a list somewhere
 * else must not be able to move a gate.
 */
const RANK: Record<LifecycleStatus, number> = {
  DRAFT: 0,
  IN_REVIEW: 1,
  APPROVED: 2,
  DEPRECATED: 3,
};

/** Said to anyone taking a retired artifact back up the ladder without passing DRAFT. */
function revived(noun: string): string {
  return `This ${noun} has been deprecated. Return it to draft before taking it through review again.`;
}

export function decide(
  current: LifecycleStatus,
  requested: LifecycleStatus,
  noun: string,
): LadderDecision {
  if (current === requested) return { kind: 'unchanged' };

  // DRAFT and DEPRECATED are reachable from anywhere: withdrawing, rescinding and reviving all
  // land on the first, and retiring must stay available or a folder that cannot be deleted
  // (the delete refuses a non-empty suite) can never be taken out of circulation at all.
  if (requested === 'DRAFT' || requested === 'DEPRECATED') return { kind: 'move', target: requested };

  if (current === 'DEPRECATED') return { kind: 'refused', message: revived(noun) };

  if (requested === 'IN_REVIEW') {
    return {
      kind: 'refused',
      message: `Only a draft ${noun} can be submitted for review. Return this ${noun} to draft first.`,
    };
  }
  // requested === 'APPROVED'
  return current === 'IN_REVIEW'
    ? { kind: 'move', target: 'APPROVED' }
    : {
        kind: 'refused',
        message: `A ${noun} is approved after it has been reviewed. Submit it for review first.`,
      };
}

/**
 * Whether this edge needs the approve key rather than the edit key.
 *
 * Three acts do: granting the sign-off, retiring the artifact, and taking either back. The third
 * is the one a target-decides rule misses — `APPROVED → DRAFT` is not an approval-shaped target and
 * is nonetheless the unmaking of a lead's sign-off.
 */
export function needsApproval(current: LifecycleStatus, requested: LifecycleStatus): boolean {
  if (requested === 'APPROVED' || requested === 'DEPRECATED') return true;
  return RANK[requested] < RANK[current] && RANK[current] >= RANK.APPROVED;
}

/**
 * Where a status sits relative to review — the server's `LifecycleLadder.standingOf`, and the whole
 * of what the review record knows about this enum.
 *
 * `DEPRECATED` lands with `DRAFT` deliberately: retiring an artifact while it is under review ends
 * the review the same way withdrawing does, because nobody judged the content — the question was
 * taken off the table.
 */
export function standingOf(status: LifecycleStatus): ReviewStanding {
  if (status === 'IN_REVIEW') return 'IN_REVIEW';
  if (status === 'APPROVED') return 'APPROVED';
  return 'OUTSIDE_REVIEW';
}
