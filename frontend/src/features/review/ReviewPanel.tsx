import { useState } from 'react';
import { Alert, Box, Button, Chip, Stack, TextField, Typography } from '@mui/material';
import CheckCircleOutlineIcon from '@mui/icons-material/CheckCircleOutlineOutlined';
import RateReviewOutlinedIcon from '@mui/icons-material/RateReviewOutlined';
import type { PermissionKey, Review, ReviewParticipation, ReviewSubjectType } from '../../api/types';
import { useRecordReviewVerdict, useReviews } from '../../api/queries';
import { DataRegion } from '../../components/DataRegion';
import { Panel } from '../../components/PageHeader';
import { PermissionGate } from '../../components/PermissionGate';
import { describeError } from '../../lib/describeError';
import { formatDateTime } from '../../lib/format';
import {
  reviewStateColors,
  reviewStateLabels,
  reviewSubjectLabels,
  reviewVerdictLabels,
} from '../../lib/labels';

/**
 * Who was asked to look at this artifact, what they said, and who signed it off — plan §6g's
 * reviewer apparatus, beside the artifact rather than on a screen of its own.
 *
 * One component for all three subject types, because the server keeps one record for all three: a
 * case, a suite and a plan differ in *what review means* (§6g's own table) and not in what the
 * record holds. Three panels would be three places for the verdict wording to drift.
 *
 * ### It does not move the artifact, and that is visible in what it offers
 *
 * The only affordances here are LGTM and "Request changes". Submitting, approving and sending back
 * are moves on the artifact's own ladder and live where that ladder lives — the suite tree's menu,
 * the plan header, the bulk dialog — because §6g's state machine has exactly one gate and this tier
 * is not it. A panel with an Approve button would be a second door to the sign-off for everyone
 * holding the advisory key.
 */
export function ReviewPanel({
  projectId,
  subjectType,
  subjectId,
  currentVersionNo,
}: {
  projectId: string;
  subjectType: ReviewSubjectType;
  subjectId: string | undefined;
  /**
   * The artifact's version as this page is rendering it, so the panel can say the content has
   * moved on since the reviewer was asked. Optional: a suite tree has no version to hand, and the
   * comparison is simply not offered there rather than being guessed.
   */
  currentVersionNo?: number;
}) {
  const reviews = useReviews(projectId, subjectType, subjectId);

  return (
    <Panel title="Review" testId="Review.Panel">
      <DataRegion
        testId="Review.Panel.Region"
        state={{ ...reviews, refetch: () => void reviews.refetch() }}
        isEmpty={(rows) => rows.length === 0}
        empty={{
          title: 'Not in review',
          /*
            Not a failure and not a missing feature: review is optional and off by default (§6g),
            so an artifact nobody submitted has no record and that is the normal state for most of
            them. Saying where the submit lives keeps the panel from reading as broken.
          */
          body: `This ${reviewSubjectLabels[subjectType]} has never been submitted for review. Submitting is part of its status, not of this panel.`,
        }}
      >
        {(rows) => (
          <Stack sx={{ gap: 2 }} data-testid="Review.Panel.List">
            <CurrentReview
              projectId={projectId}
              subjectType={subjectType}
              subjectId={subjectId ?? ''}
              review={rows[0]}
              currentVersionNo={currentVersionNo}
            />
            {rows.length > 1 && <EarlierReviews reviews={rows.slice(1)} />}
          </Stack>
        )}
      </DataRegion>
    </Panel>
  );
}

/** The permission a peer verdict costs, by subject type — the client half of the server's map. */
const REVIEW_PERMISSION = {
  CASE: 'case:review',
  SUITE: 'suite:review',
  PLAN: 'plan:review',
} as const satisfies Record<ReviewSubjectType, PermissionKey>;

function CurrentReview({
  projectId,
  subjectType,
  subjectId,
  review,
  currentVersionNo,
}: {
  projectId: string;
  subjectType: ReviewSubjectType;
  subjectId: string;
  review: Review;
  currentVersionNo?: number;
}) {
  const isOpen = review.state === 'IN_REVIEW';
  const movedOn = currentVersionNo !== undefined && currentVersionNo > review.subjectVersionNo;

  return (
    <Stack sx={{ gap: 1.25 }} data-testid="Review.Current">
      <Stack direction="row" sx={{ alignItems: 'center', gap: 1, flexWrap: 'wrap' }}>
        <Chip
          size="small"
          label={reviewStateLabels[review.state]}
          data-testid="Review.State"
          sx={{
            bgcolor: reviewStateColors[review.state],
            color: 'var(--color-on-status)',
            fontWeight: 600,
          }}
        />
        <Typography variant="body2" color="text.secondary">
          {/*
            "Submitted by" survives a null author: `submittedBy` is nullable because the account may
            have been offboarded since, and the record still stands. Collapsing that into a name
            would attribute the submission to somebody.
          */}
          {review.submittedBy
            ? `Submitted by ${review.submittedBy.displayName}`
            : 'Submitted by a former member'}{' '}
          · {formatDateTime(review.createdAt)}
        </Typography>
      </Stack>

      {review.approvedBy && review.approvedAt && (
        <Typography variant="body2" data-testid="Review.Approval">
          <CheckCircleOutlineIcon
            fontSize="inherit"
            sx={{ verticalAlign: 'text-bottom', mr: 0.5 }}
          />
          Signed off by {review.approvedBy.displayName} · {formatDateTime(review.approvedAt)}
        </Typography>
      )}

      {/*
        §6g's diff-since-approval, in the only form the data supports today: the review names the
        version somebody was asked to read, and the page knows which version it is showing. Saying
        so is the difference between a reviewer's LGTM meaning something and meaning nothing.
      */}
      {movedOn && (
        <Alert severity="warning" data-testid="Review.Stale">
          This {reviewSubjectLabels[subjectType]} has changed since it was submitted — the review
          covers v{review.subjectVersionNo}, and you are looking at v{currentVersionNo}.
        </Alert>
      )}

      <Trail participations={review.participations} />

      {isOpen && (
        <PermissionGate projectId={projectId} permission={REVIEW_PERMISSION[subjectType]}>
          {({ allowed }) => (
            <VerdictForm
              projectId={projectId}
              subjectType={subjectType}
              subjectId={subjectId}
              reviewId={review.id}
              allowed={allowed}
            />
          )}
        </PermissionGate>
      )}
    </Stack>
  );
}

function Trail({ participations }: { participations: ReviewParticipation[] }) {
  if (participations.length === 0) {
    return (
      <Typography variant="body2" color="text.secondary" data-testid="Review.Trail.Empty">
        Nobody has commented yet.
      </Typography>
    );
  }

  return (
    <Stack sx={{ gap: 1 }} data-testid="Review.Trail">
      {participations.map((participation) => (
        <Box
          key={participation.id}
          data-testid="Review.Trail.Item"
          sx={{ pl: 1.5, borderLeft: '2px solid', borderColor: 'divider' }}
        >
          <Stack direction="row" sx={{ alignItems: 'center', gap: 0.75, flexWrap: 'wrap' }}>
            <Typography variant="body2" sx={{ fontWeight: 600 }}>
              {reviewVerdictLabels[participation.verdict]}
            </Typography>
            <Typography variant="caption" color="text.secondary">
              {participation.user?.displayName ?? 'A former member'} ·{' '}
              {formatDateTime(participation.createdAt)}
            </Typography>
          </Stack>
          {participation.comment && (
            <Typography variant="body2" color="text.secondary">
              {participation.comment}
            </Typography>
          )}
        </Box>
      ))}
    </Stack>
  );
}

/**
 * The advisory tier's two buttons and the comment that goes with them.
 *
 * Disabled rather than hidden when the caller holds no `*:review` (§31): a Viewer holds
 * `case:comment` and not `case:review` — the three tiers §6g describes — and hiding the control
 * would leave them unable to tell the difference between "you may not" and "nobody may".
 */
function VerdictForm({
  projectId,
  subjectType,
  subjectId,
  reviewId,
  allowed,
}: {
  projectId: string;
  subjectType: ReviewSubjectType;
  subjectId: string;
  reviewId: string;
  allowed: boolean;
}) {
  const [comment, setComment] = useState('');
  const record = useRecordReviewVerdict(projectId, subjectType, subjectId);

  const submit = (verdict: 'LGTM' | 'CHANGES_REQUESTED') => {
    record.mutate(
      { reviewId, verdict, comment },
      { onSuccess: () => setComment('') },
    );
  };

  return (
    <Stack sx={{ gap: 1 }} data-testid="Review.VerdictForm">
      <TextField
        size="small"
        multiline
        minRows={2}
        label="Comment (optional)"
        value={comment}
        disabled={!allowed || record.isPending}
        onChange={(event) => setComment(event.target.value)}
        slotProps={{ htmlInput: { 'data-testid': 'Review.Comment' } }}
      />
      <Stack direction="row" sx={{ gap: 1, flexWrap: 'wrap' }}>
        <Button
          variant="contained"
          startIcon={<CheckCircleOutlineIcon />}
          disabled={!allowed || record.isPending}
          onClick={() => submit('LGTM')}
          data-testid="Review.Lgtm"
        >
          Looks good
        </Button>
        <Button
          startIcon={<RateReviewOutlinedIcon />}
          disabled={!allowed || record.isPending}
          onClick={() => submit('CHANGES_REQUESTED')}
          data-testid="Review.RequestChanges"
        >
          Request changes
        </Button>
      </Stack>
      {/*
        In place, beside the review it is about. The 403 a caller can reach here is the one the
        disabled state already predicted; the 409 is the one it could not — somebody approved or
        sent the artifact back while this panel was open, which is ordinary rather than exceptional.
      */}
      {record.isError && (
        <Alert severity="error" data-testid="Review.Error">
          {describeError(record.error).body}
        </Alert>
      )}
    </Stack>
  );
}

function EarlierReviews({ reviews }: { reviews: Review[] }) {
  return (
    <Box data-testid="Review.Earlier">
      <Typography variant="caption" color="text.secondary">
        Earlier reviews
      </Typography>
      <Stack sx={{ gap: 0.25, mt: 0.5 }}>
        {reviews.map((review) => (
          <Typography key={review.id} variant="body2" color="text.secondary">
            {reviewStateLabels[review.state]} · v{review.subjectVersionNo} ·{' '}
            {formatDateTime(review.createdAt)}
          </Typography>
        ))}
      </Stack>
    </Box>
  );
}
