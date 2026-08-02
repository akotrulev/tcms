import { useState } from 'react';
import {
  Alert,
  Box,
  Button,
  Chip,
  Divider,
  MenuItem,
  Stack,
  TextField,
  Typography,
} from '@mui/material';
import CancelIcon from '@mui/icons-material/Cancel';
import CheckCircleOutlineIcon from '@mui/icons-material/CheckCircleOutlineOutlined';
import GroupOutlinedIcon from '@mui/icons-material/GroupOutlined';
import PersonOutlineIcon from '@mui/icons-material/PersonOutlineOutlined';
import RateReviewOutlinedIcon from '@mui/icons-material/RateReviewOutlined';
import type {
  PermissionKey,
  ProjectSettings,
  Review,
  ReviewAssignee,
  ReviewComment,
  ReviewParticipation,
  ReviewSubjectType,
} from '../../api/types';
import {
  useAddReviewComment,
  useAssignReviewer,
  useProjectMembers,
  useProjectSettings,
  useRecordReviewVerdict,
  useReviews,
  useSetReviewCommentResolution,
  useTeams,
  useUnassignReviewer,
} from '../../api/queries';
import { DataRegion } from '../../components/DataRegion';
import { Panel } from '../../components/PageHeader';
import { PermissionGate } from '../../components/PermissionGate';
import { describeError } from '../../lib/describeError';
import { formatDateTime } from '../../lib/format';
import { selectTestId } from '../../lib/muiTestIds';
import {
  reviewStateColors,
  reviewStateLabels,
  reviewSubjectLabels,
  reviewVerdictLabels,
} from '../../lib/labels';

/**
 * Which settings flag switches review on for each artifact type.
 *
 * `satisfies Record<ReviewSubjectType, ...>` for `lib/labels.ts`' reason: a fourth subject type
 * becomes a **compile error here** rather than silently reading `undefined` and hiding the panel on
 * the one artifact somebody just added support for. The value type is pinned to the settings keys,
 * so a renamed contract field fails the build too.
 */
const reviewEnabledKeys = {
  CASE: 'reviewEnabledCase',
  SUITE: 'reviewEnabledSuite',
  PLAN: 'reviewEnabledPlan',
} satisfies Record<ReviewSubjectType, keyof ProjectSettings>;

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
 *
 * ### It renders nothing at all when the project has review switched off
 *
 * §6g's opening requirement — *"a project that never turns it on should see no extra UI at all"* —
 * and the check lives **here** rather than at the three call sites, so a fourth screen that mounts
 * this panel cannot forget it. The setting is per artifact type, so a project reviewing plans and
 * not cases gets the panel on one and not the other.
 *
 * The gate is deliberately **not** rendered as an empty state saying "review is off": a project
 * that never enabled it has no use for a permanent panel explaining a feature it declined, which is
 * exactly the "extra UI" the requirement is about.
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
  const settings = useProjectSettings(projectId);
  const reviews = useReviews(projectId, subjectType, subjectId);

  /*
    While the settings are loading this renders nothing rather than a panel that may be about to
    disappear. `useCan`'s "not yet is not no" rule (§65) does not apply here, because the two
    failures are not comparable: a permission resolving late must not deny an affordance the caller
    holds, while a panel that flashes in and then vanishes is worse than one that arrives a beat
    late. `data` is undefined while pending and on failure, and both mean "do not render" — a
    settings read that failed is not evidence review is on.
  */
  if (!settings.data?.[reviewEnabledKeys[subjectType]]) {
    return null;
  }

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

      <Assignees
        projectId={projectId}
        subjectType={subjectType}
        subjectId={subjectId}
        reviewId={review.id}
        assignees={review.assignees}
        submittedById={review.submittedBy?.id}
        isOpen={isOpen}
      />

      <Trail participations={review.participations} />

      <Threads
        projectId={projectId}
        subjectType={subjectType}
        subjectId={subjectId}
        reviewId={review.id}
        comments={review.comments}
        isOpen={isOpen}
      />

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

/**
 * Who was **asked** — §6g's reviewer assignment, and the half of the panel that says what is
 * outstanding rather than what has happened.
 *
 * ### Asked and spoke are two lists, deliberately not merged
 *
 * The obvious tidy-up is one list of people with a verdict beside each. It is wrong in both
 * directions: somebody may give a verdict without ever being asked (§6g's peer review is open to
 * the tier, not to a nominated few), and a request nobody has answered is precisely the row a lead
 * opens this panel to find. Merging them would render the second as a person with no verdict — the
 * same shape as somebody who was never asked at all.
 *
 * ### The picker offers project members and teams, and cannot offer a refusal
 *
 * `useProjectMembers` is the server's own "people who can be given work in this project" list, and
 * the assignment endpoint validates against project visibility — of which that list is a **subset**
 * (a Company Admin sees the project without holding a grant on it, so they are assignable and not
 * offered). A picker narrower than the rule is a picker that never produces a 400; the reverse
 * would be a form a user fills in correctly and cannot submit.
 *
 * The submitter is filtered out here as well as refused there, for the same reason and with the
 * server still owning it: their own peer review would not count towards the required minimum, so
 * offering them is offering a request that can never be discharged.
 */
function Assignees({
  projectId,
  subjectType,
  subjectId,
  reviewId,
  assignees,
  submittedById,
  isOpen,
}: {
  projectId: string;
  subjectType: ReviewSubjectType;
  subjectId: string;
  reviewId: string;
  assignees: ReviewAssignee[];
  /** Filtered out of the picker — see this component's header. Absent when unattributable. */
  submittedById: string | undefined;
  isOpen: boolean;
}) {
  const unassign = useUnassignReviewer(projectId, subjectType, subjectId);

  if (assignees.length === 0 && !isOpen) {
    // A closed review nobody was asked to look at gets no section — nothing to show and nothing
    // that can be added, which is `Threads`' rule and §6g's "no extra UI" applied one level in.
    return null;
  }

  return (
    <Stack sx={{ gap: 1 }} data-testid="Review.Assignees">
      <Typography variant="subtitle2">Asked to review</Typography>

      {assignees.length === 0 ? (
        <Typography variant="body2" color="text.secondary" data-testid="Review.Assignees.Empty">
          Nobody has been asked yet. Anyone with review rights can still give a verdict.
        </Typography>
      ) : (
        <Stack direction="row" sx={{ gap: 0.75, flexWrap: 'wrap' }}>
          {assignees.map((assignee) => (
            <PermissionGate
              key={assignee.id}
              projectId={projectId}
              permission={REVIEW_PERMISSION[subjectType]}
            >
              {({ allowed }) => (
                <Chip
                  size="small"
                  variant="outlined"
                  icon={assignee.team ? <GroupOutlinedIcon /> : <PersonOutlineIcon />}
                  /*
                    Both null is a real row: the person asked has been offboarded since, and the
                    request stands. Dropping the chip would hide a request nobody can discharge,
                    which is the one state a lead most needs to see (§66's rule, from the render
                    side).
                  */
                  label={
                    assignee.team?.name ?? assignee.user?.displayName ?? 'A former member'
                  }
                  data-testid="Review.Assignee"
                  /*
                    An explicit delete icon, only so it can carry a test id. MUI's default one is
                    an internal component with no identifier of its own, so a test can find the
                    chip and have nothing to click — the same class of miss as §71's hidden native
                    input, discovered here the same way.
                  */
                  deleteIcon={<CancelIcon data-testid="Review.Assignee.Remove" />}
                  onDelete={
                    isOpen && allowed
                      ? () => unassign.mutate({ reviewId, assignmentId: assignee.id })
                      : undefined
                  }
                />
              )}
            </PermissionGate>
          ))}
        </Stack>
      )}

      {isOpen && (
        <PermissionGate projectId={projectId} permission={REVIEW_PERMISSION[subjectType]}>
          {({ allowed }) => (
            <AssignForm
              projectId={projectId}
              subjectType={subjectType}
              subjectId={subjectId}
              reviewId={reviewId}
              assignees={assignees}
              submittedById={submittedById}
              allowed={allowed}
            />
          )}
        </PermissionGate>
      )}

      {unassign.isError && (
        <Alert severity="error" data-testid="Review.Assignees.Error">
          {describeError(unassign.error).body}
        </Alert>
      )}
    </Stack>
  );
}

/**
 * The picker. One select of people and teams, and one button.
 *
 * The value is prefixed (`user:` / `team:`) rather than being two selects or a select plus a radio:
 * the request names exactly one of the two, so a single control is the shape that cannot express
 * "both" or "neither" — the hook's union type, at the level of the widget.
 */
function AssignForm({
  projectId,
  subjectType,
  subjectId,
  reviewId,
  assignees,
  submittedById,
  allowed,
}: {
  projectId: string;
  subjectType: ReviewSubjectType;
  subjectId: string;
  reviewId: string;
  assignees: ReviewAssignee[];
  submittedById: string | undefined;
  allowed: boolean;
}) {
  const [choice, setChoice] = useState('');
  const assign = useAssignReviewer(projectId, subjectType, subjectId);
  const members = useProjectMembers(projectId, allowed);
  const teams = useTeams();

  // Already asked, so not offered again. The server answers 201 and writes nothing either way, so
  // this is about not showing a name whose selection does visibly nothing.
  const askedUsers = new Set(assignees.map((row) => row.user?.id).filter(Boolean));
  const askedTeams = new Set(assignees.map((row) => row.team?.id).filter(Boolean));

  const people = (members.data ?? []).filter(
    // The submitter is the server's field error, not this filter's rule — offered and then
    // refused would be a picker that produces a 400, which is the one thing it must not do.
    (person) => !askedUsers.has(person.id) && person.id !== submittedById,
  );
  const groups = (teams.data ?? []).filter((team) => !askedTeams.has(team.id));

  return (
    <Stack direction="row" sx={{ gap: 1, alignItems: 'flex-start', flexWrap: 'wrap' }}>
      <TextField
        select
        size="small"
        label="Ask someone to review"
        value={choice}
        disabled={!allowed || assign.isPending}
        onChange={(event) => setChoice(event.target.value)}
        sx={{ minWidth: 240 }}
        /*
          On the Select's DISPLAY element, not `htmlInput`. A `TextField select` renders a hidden
          native input plus the div a user actually clicks, so an id on the former is findable and
          `pointer-events: none` — §71's trap, which fired again there despite the helper existing,
          because the file it was copied from predates it.
        */
        slotProps={{ select: { SelectDisplayProps: selectTestId('Review.Assign.Choice') } }}
      >
        {people.map((person) => (
          <MenuItem key={person.id} value={`user:${person.id}`}>
            {person.displayName}
          </MenuItem>
        ))}
        {groups.map((team) => (
          <MenuItem key={team.id} value={`team:${team.id}`}>
            {team.name} (team)
          </MenuItem>
        ))}
      </TextField>
      <Button
        variant="outlined"
        size="small"
        sx={{ mt: 0.5 }}
        disabled={!allowed || assign.isPending || !choice}
        onClick={() =>
          assign.mutate(
            {
              reviewId,
              assignee: choice.startsWith('team:')
                ? { teamId: choice.slice('team:'.length) }
                : { userId: choice.slice('user:'.length) },
            },
            { onSuccess: () => setChoice('') },
          )
        }
        data-testid="Review.Assign.Submit"
      >
        Ask
      </Button>
      {assign.isError && (
        <Alert severity="error" sx={{ flexBasis: '100%' }} data-testid="Review.Assign.Error">
          {/*
            Beside the picker, because every refusal this can reach is about *who* was named — the
            submitter, somebody with no grant, a team that has gone — and a toast detached from the
            select would name a person the reader can no longer see.
          */}
          {describeError(assign.error).body}
        </Alert>
      )}
    </Stack>
  );
}

function Trail({ participations }: { participations: ReviewParticipation[] }) {
  if (participations.length === 0) {
    return (
      // "Nobody has given a verdict" and not "nobody has commented": since §79 the two are
      // different things on this panel, and the old wording named the one below it.
      <Typography variant="body2" color="text.secondary" data-testid="Review.Trail.Empty">
        Nobody has given a verdict yet.
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
 * §6g's threads — *"Review without inline comments degrades into Slack conversations that
 * vanish."*
 *
 * ### The two affordances cost two different permissions, and that is not an oversight
 *
 * Writing a remark costs `case:comment` for **every** subject type — the catalogue has one comment
 * key where it has three review keys, because commenting is the bottom tier rather than a
 * per-artifact judgement, and §6b built it for the client who reviews coverage without any write
 * access. Resolving costs the subject's `*:review`: declaring a point dealt with is a statement
 * about the review's progress. So a Viewer can write here and cannot resolve, and both controls are
 * *disabled rather than hidden* (§31) so the difference is legible instead of being learned from a
 * 403.
 *
 * ### Threads are one level deep, and the server enforces it
 *
 * A reply carries no reply affordance, because a reply to a reply is a field error. Rendering the
 * control anyway would be an affordance the product does not have.
 */
function Threads({
  projectId,
  subjectType,
  subjectId,
  reviewId,
  comments,
  isOpen,
}: {
  projectId: string;
  subjectType: ReviewSubjectType;
  subjectId: string;
  reviewId: string;
  comments: ReviewComment[];
  isOpen: boolean;
}) {
  const [replyingTo, setReplyingTo] = useState<string | null>(null);

  if (comments.length === 0 && !isOpen) {
    // A closed review nobody discussed gets no comment section at all — there is nothing to show
    // and nothing that can be added, and an empty heading would be the "extra UI" §6g objects to.
    return null;
  }

  return (
    <Stack sx={{ gap: 1.25 }} data-testid="Review.Comments">
      <Divider />
      <Typography variant="subtitle2">Comments</Typography>

      {comments.length === 0 ? (
        <Typography variant="body2" color="text.secondary" data-testid="Review.Comments.Empty">
          No comments yet.
        </Typography>
      ) : (
        comments.map((thread) => (
          <Thread
            key={thread.id}
            projectId={projectId}
            subjectType={subjectType}
            subjectId={subjectId}
            reviewId={reviewId}
            thread={thread}
            isOpen={isOpen}
            replying={replyingTo === thread.id}
            onReplyToggle={() =>
              setReplyingTo((current) => (current === thread.id ? null : thread.id))
            }
          />
        ))
      )}

      {isOpen && (
        <PermissionGate projectId={projectId} permission="case:comment">
          {({ allowed }) => (
            <CommentForm
              projectId={projectId}
              subjectType={subjectType}
              subjectId={subjectId}
              reviewId={reviewId}
              parentId={null}
              allowed={allowed}
              label="Add a comment"
              submitLabel="Comment"
              testId="Review.Comment.New"
            />
          )}
        </PermissionGate>
      )}
    </Stack>
  );
}

function Thread({
  projectId,
  subjectType,
  subjectId,
  reviewId,
  thread,
  isOpen,
  replying,
  onReplyToggle,
}: {
  projectId: string;
  subjectType: ReviewSubjectType;
  subjectId: string;
  reviewId: string;
  thread: ReviewComment;
  isOpen: boolean;
  replying: boolean;
  onReplyToggle: () => void;
}) {
  const resolution = useSetReviewCommentResolution(projectId, subjectType, subjectId);
  const resolved = !!thread.resolvedAt;

  return (
    <Box
      data-testid="Review.Thread"
      sx={{
        pl: 1.5,
        borderLeft: '2px solid',
        borderColor: resolved ? 'success.main' : 'divider',
        opacity: resolved ? 0.7 : 1,
      }}
    >
      <CommentBody comment={thread} testId="Review.Thread.Root" />

      {resolved && (
        <Typography variant="caption" color="text.secondary" data-testid="Review.Thread.Resolved">
          {/*
            The resolver is named, which is the whole reason `V24` added the column: a thread marked
            dealt with by nobody is the one entry an auditor cannot follow up. Null survives it, for
            `submittedBy`'s reason — the account may have been offboarded since.
          */}
          Resolved by {thread.resolvedBy?.displayName ?? 'a former member'} ·{' '}
          {formatDateTime(thread.resolvedAt ?? '')}
        </Typography>
      )}

      {thread.replies.length > 0 && (
        <Stack sx={{ gap: 0.75, mt: 0.75, pl: 2 }} data-testid="Review.Thread.Replies">
          {thread.replies.map((reply) => (
            <CommentBody key={reply.id} comment={reply} testId="Review.Thread.Reply" />
          ))}
        </Stack>
      )}

      {isOpen && (
        <Stack direction="row" sx={{ gap: 1, mt: 0.75, flexWrap: 'wrap' }}>
          <PermissionGate projectId={projectId} permission="case:comment">
            {({ allowed }) => (
              <Button
                size="small"
                disabled={!allowed}
                onClick={onReplyToggle}
                data-testid="Review.Thread.ReplyToggle"
              >
                {replying ? 'Cancel' : 'Reply'}
              </Button>
            )}
          </PermissionGate>
          <PermissionGate projectId={projectId} permission={REVIEW_PERMISSION[subjectType]}>
            {({ allowed }) => (
              <Button
                size="small"
                disabled={!allowed || resolution.isPending}
                onClick={() =>
                  resolution.mutate({ reviewId, commentId: thread.id, resolved: !resolved })
                }
                data-testid="Review.Thread.Resolve"
              >
                {resolved ? 'Re-open' : 'Resolve'}
              </Button>
            )}
          </PermissionGate>
        </Stack>
      )}

      {replying && (
        <PermissionGate projectId={projectId} permission="case:comment">
          {({ allowed }) => (
            <CommentForm
              projectId={projectId}
              subjectType={subjectType}
              subjectId={subjectId}
              reviewId={reviewId}
              parentId={thread.id}
              allowed={allowed}
              label="Reply"
              submitLabel="Reply"
              testId="Review.Comment.Reply"
              onDone={onReplyToggle}
            />
          )}
        </PermissionGate>
      )}

      {resolution.isError && (
        <Alert severity="error" sx={{ mt: 1 }} data-testid="Review.Thread.Error">
          {describeError(resolution.error).body}
        </Alert>
      )}
    </Box>
  );
}

function CommentBody({ comment, testId }: { comment: ReviewComment; testId: string }) {
  return (
    <Box data-testid={testId}>
      <Typography variant="caption" color="text.secondary">
        {comment.author?.displayName ?? 'A former member'} · {formatDateTime(comment.createdAt)}
      </Typography>
      {/*
        `pre-wrap`, because the body is prose a reviewer typed: a paragraph break they made is part
        of what they said, and collapsing it changes the remark.
      */}
      <Typography variant="body2" sx={{ whiteSpace: 'pre-wrap' }}>
        {comment.body}
      </Typography>
    </Box>
  );
}

function CommentForm({
  projectId,
  subjectType,
  subjectId,
  reviewId,
  parentId,
  allowed,
  label,
  submitLabel,
  testId,
  onDone,
}: {
  projectId: string;
  subjectType: ReviewSubjectType;
  subjectId: string;
  reviewId: string;
  parentId: string | null;
  allowed: boolean;
  label: string;
  submitLabel: string;
  testId: string;
  onDone?: () => void;
}) {
  const [body, setBody] = useState('');
  const add = useAddReviewComment(projectId, subjectType, subjectId);

  return (
    <Stack sx={{ gap: 1, mt: 1 }} data-testid={testId}>
      <TextField
        size="small"
        multiline
        minRows={2}
        label={label}
        value={body}
        disabled={!allowed || add.isPending}
        onChange={(event) => setBody(event.target.value)}
        slotProps={{ htmlInput: { 'data-testid': `${testId}.Body` } }}
      />
      <Box>
        <Button
          variant="contained"
          size="small"
          // Empty is refused by the server as a field error, so the button is disabled rather than
          // letting a caller discover it — the same rule the editor's Save follows.
          disabled={!allowed || add.isPending || !body.trim()}
          onClick={() =>
            add.mutate(
              { reviewId, body, parentId },
              {
                onSuccess: () => {
                  setBody('');
                  onDone?.();
                },
              },
            )
          }
          data-testid={`${testId}.Submit`}
        >
          {submitLabel}
        </Button>
      </Box>
      {add.isError && (
        <Alert severity="error" data-testid={`${testId}.Error`}>
          {describeError(add.error).body}
        </Alert>
      )}
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
