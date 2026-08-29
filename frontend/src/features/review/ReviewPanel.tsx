import { Check, MessageSquare, User, Users, X } from 'lucide-react';
import { useState } from 'react';
import type {
  PermissionKey,
  ProjectSettings,
  Review,
  ReviewState,
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
import { Badge, Banner, type BadgeTone } from '../../components/kit';
import { PermissionGate } from '../../components/PermissionGate';
import { Button, Textarea } from '../../components/ui';
import { describeError } from '../../lib/describeError';
import { formatDateTime } from '../../lib/format';
import {
  reviewStateLabels,
  reviewSubjectLabels,
  reviewVerdictLabels,
} from '../../lib/labels';

/** `.sec h3` from `renders/tcms.css` — this panel sits in a rail, and takes the rail's heading. */
const SECTION_HEADING = 'm-0 mb-2.5 text-[11px] font-semibold tracking-[.08em] text-ink-3 uppercase';

/**
 * A review state's badge tone. `IN_REVIEW` is amber — a state that is currently *waiting on
 * somebody*, which is design language §5's amber — and an approval is the green a pass is.
 */
const stateTone = {
  IN_REVIEW: 'blocked',
  APPROVED: 'passed',
  REJECTED: 'failed',
  WITHDRAWN: 'skipped',
} as const satisfies Record<ReviewState, BadgeTone>;

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
  // The fourth §6g opt-in (005 S9b, column added by `V71`). It arrived here as a compile error,
  // which is the paragraph above doing its job rather than a coincidence worth leaving unremarked.
  DOC: 'reviewEnabledDoc',
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
    <section data-testid="Review.Panel">
      <h2 className={SECTION_HEADING}>Review</h2>
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
          <div className="flex flex-col gap-3" data-testid="Review.Panel.List">
            <CurrentReview
              projectId={projectId}
              subjectType={subjectType}
              subjectId={subjectId ?? ''}
              review={rows[0]}
              currentVersionNo={currentVersionNo}
            />
            {rows.length > 1 && <EarlierReviews reviews={rows.slice(1)} />}
          </div>
        )}
      </DataRegion>
    </section>
  );
}

/**
 * The permission a peer verdict costs, by subject type — the client half of the server's map.
 *
 * `satisfies Record<ReviewSubjectType, PermissionKey>` is what made `DOC` a compile error the day
 * the enum gained it, which is the point of writing it this way: the fourth reviewable artifact
 * arrived with its own key (`doc:review`, seeded by `V71`) and a map with an `else` branch would
 * have shipped a panel that refused every verdict on a page and said nothing about why.
 */
const REVIEW_PERMISSION = {
  CASE: 'case:review',
  SUITE: 'suite:review',
  PLAN: 'plan:review',
  DOC: 'doc:review',
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
    <div className="flex flex-col gap-2" data-testid="Review.Current">
      <div className="flex flex-wrap items-center gap-2">
        <Badge tone={stateTone[review.state]} data-testid="Review.State">
          {reviewStateLabels[review.state]}
        </Badge>
        <span className="text-[12px] text-ink-4">
          {/*
            "Submitted by" survives a null author: `submittedBy` is nullable because the account may
            have been offboarded since, and the record still stands. Collapsing that into a name
            would attribute the submission to somebody.
          */}
          {review.submittedBy
            ? `Submitted by ${review.submittedBy.displayName}`
            : 'Submitted by a former member'}{' '}
          · {formatDateTime(review.createdAt)}
        </span>
      </div>

      {review.approvedBy && review.approvedAt && (
        <p className="m-0 flex items-center gap-1.5 text-[13px] text-ink-2" data-testid="Review.Approval">
          <Check aria-hidden className="size-3.5" style={{ color: 'var(--color-passed)' }} />
          Signed off by {review.approvedBy.displayName} · {formatDateTime(review.approvedAt)}
        </p>
      )}

      {/*
        §6g's diff-since-approval, in the only form the data supports today: the review names the
        version somebody was asked to read, and the page knows which version it is showing. Saying
        so is the difference between a reviewer's LGTM meaning something and meaning nothing.
      */}
      {movedOn && (
        <Banner tone="warn" data-testid="Review.Stale">
          <span>
            This {reviewSubjectLabels[subjectType]} has changed since it was submitted — the review
            covers v{review.subjectVersionNo}, and you are looking at v{currentVersionNo}.
          </span>
        </Banner>
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
    </div>
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
    <div className="flex flex-col gap-1.5" data-testid="Review.Assignees">
      <h3 className="m-0 text-[12px] font-medium text-ink-2">Asked to review</h3>

      {assignees.length === 0 ? (
        <p className="m-0 text-[12.5px] text-ink-4" data-testid="Review.Assignees.Empty">
          Nobody has been asked yet. Anyone with review rights can still give a verdict.
        </p>
      ) : (
        <div className="flex flex-wrap gap-1.5">
          {assignees.map((assignee) => (
            <PermissionGate
              key={assignee.id}
              projectId={projectId}
              permission={REVIEW_PERMISSION[subjectType]}
            >
              {({ allowed }) => (
                /*
                  A NAME WITH A WITHDRAWAL, not a `kit/Chip`. §5's chip is *a value the reader
                  owns and is a button* — this is neither: nothing happens when you click a
                  person's name here, and the only act on the row is removing the request. The kit
                  component's own `onClick` is required for exactly that reason, so borrowing it
                  would mean inventing a click that does nothing.

                  Both null is a real row: the person asked has been offboarded since, and the
                  request stands. Dropping the row would hide a request nobody can discharge,
                  which is the one state a lead most needs to see (§66's rule, from the render
                  side).
                */
                <span
                  data-testid="Review.Assignee"
                  className="inline-flex items-center gap-1.5 rounded-[5px] border border-white/8 px-2.5 py-[3px] text-xs text-ink-2"
                >
                  {assignee.team ? (
                    <Users aria-hidden className="size-3 text-ink-4" />
                  ) : (
                    <User aria-hidden className="size-3 text-ink-4" />
                  )}
                  {assignee.team?.name ?? assignee.user?.displayName ?? 'A former member'}
                  {isOpen && allowed && (
                    <button
                      type="button"
                      aria-label={`Withdraw the request to ${assignee.team?.name ?? assignee.user?.displayName ?? 'a former member'}`}
                      onClick={() => unassign.mutate({ reviewId, assignmentId: assignee.id })}
                      data-testid="Review.Assignee.Remove"
                      className="text-ink-4 hover:text-ink focus-visible:outline-2 focus-visible:outline-offset-[-2px] focus-visible:outline-[color:var(--color-given)]"
                    >
                      <X aria-hidden className="size-3" />
                    </button>
                  )}
                </span>
              )}
            </PermissionGate>
          ))}
        </div>
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
        <Banner tone="bad" data-testid="Review.Assignees.Error">
          <span>{describeError(unassign.error).body}</span>
        </Banner>
      )}
    </div>
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
  const [open, setOpen] = useState(false);
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

  const options = [
    ...people.map((person) => ({ value: `user:${person.id}`, label: person.displayName })),
    ...groups.map((team) => ({ value: `team:${team.id}`, label: `${team.name} (team)` })),
  ];
  const chosen = options.find((option) => option.value === choice);

  return (
    <div className="flex flex-wrap items-start gap-2">
      {/*
        A LISTBOX THIS FILE OWNS, not `ui/select`.

        ~~`slotProps.select.SelectDisplayProps` carried the test id, because a `TextField select`
        renders a hidden native input plus the div a user actually clicks and an id on the former
        is findable and `pointer-events: none` — §71's trap.~~ → **superseded by 007 T11**, with
        the helper module the workaround lived in (D3 of 007 deletes it in T16). What replaces it is not the kit's
        Radix `Select`: that primitive calls `hasPointerCapture` on its trigger, jsdom implements
        neither it nor the polyfill, and `src/test/setup.ts` is deliberately not a place where the
        application is bent to suit the harness. This screen's suite drives the picker three times,
        so the control is a plain disclosure over a real `role="listbox"` — native focus, native
        keys, and nothing to stub.
      */}
      <div className="relative min-w-[240px]">
        <label
          htmlFor="Review.Assign.Choice"
          className="mb-1 block text-[12px] font-medium text-ink-2"
        >
          Ask someone to review
        </label>
        <button
          type="button"
          id="Review.Assign.Choice"
          role="combobox"
          aria-expanded={open}
          aria-haspopup="listbox"
          aria-controls={open ? 'Review.Assign.Options' : undefined}
          disabled={!allowed || assign.isPending}
          onClick={() => setOpen((current) => !current)}
          data-testid="Review.Assign.Choice"
          className={
            'flex w-full items-center justify-between gap-2 rounded-[6px] border border-white/8 ' +
            'bg-field px-3 py-1.5 text-left text-[13px] text-ink disabled:cursor-not-allowed ' +
            'disabled:opacity-45 focus-visible:outline-2 focus-visible:outline-offset-2 ' +
            'focus-visible:outline-[color:var(--color-given)]'
          }
        >
          {chosen ? chosen.label : <span className="text-ink-4">Nobody chosen</span>}
        </button>

        {open && (
          <>
            <div aria-hidden className="fixed inset-0 z-40" onClick={() => setOpen(false)} />
            <div
              id="Review.Assign.Options"
              role="listbox"
              onKeyDown={(event) => {
                if (event.key === 'Escape') setOpen(false);
              }}
              className="absolute z-50 mt-1 max-h-64 w-full overflow-auto rounded-md border border-white/8 bg-panel p-1 shadow-[0_16px_32px_-8px_rgb(0_0_0/60%)]"
            >
              {options.length === 0 ? (
                <p className="m-0 px-2.5 py-1.5 text-[12.5px] text-ink-4">
                  Everybody who can be asked has been.
                </p>
              ) : (
                options.map((option) => (
                  <button
                    key={option.value}
                    type="button"
                    role="option"
                    aria-selected={option.value === choice}
                    onClick={() => {
                      setChoice(option.value);
                      setOpen(false);
                    }}
                    className={
                      'block w-full cursor-pointer rounded-[4px] px-2.5 py-1.5 text-left text-[13px] ' +
                      'hover:bg-white/5 hover:text-ink focus-visible:outline-2 ' +
                      'focus-visible:outline-offset-[-2px] focus-visible:outline-[color:var(--color-given)] ' +
                      (option.value === choice ? 'text-ink' : 'text-ink-2')
                    }
                  >
                    {option.label}
                  </button>
                ))
              )}
            </div>
          </>
        )}
      </div>

      <Button
        size="sm"
        className="mt-[22px]"
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
        <Banner tone="bad" className="basis-full" data-testid="Review.Assign.Error">
          {/*
            Beside the picker, because every refusal this can reach is about *who* was named — the
            submitter, somebody with no grant, a team that has gone — and a toast detached from the
            select would name a person the reader can no longer see.
          */}
          <span>{describeError(assign.error).body}</span>
        </Banner>
      )}
    </div>
  );
}

function Trail({ participations }: { participations: ReviewParticipation[] }) {
  if (participations.length === 0) {
    return (
      // "Nobody has given a verdict" and not "nobody has commented": since §79 the two are
      // different things on this panel, and the old wording named the one below it.
      <p className="m-0 text-[12.5px] text-ink-4" data-testid="Review.Trail.Empty">
        Nobody has given a verdict yet.
      </p>
    );
  }

  return (
    <div className="flex flex-col gap-1.5" data-testid="Review.Trail">
      {participations.map((participation) => (
        <div
          key={participation.id}
          data-testid="Review.Trail.Item"
          className="border-l-2 border-white/8 pl-3"
        >
          <div className="flex flex-wrap items-center gap-1.5">
            <span className="text-[13px] font-medium text-ink">
              {reviewVerdictLabels[participation.verdict]}
            </span>
            <span className="text-[12px] text-ink-4">
              {participation.user?.displayName ?? 'A former member'} ·{' '}
              {formatDateTime(participation.createdAt)}
            </span>
          </div>
          {participation.comment && (
            <p className="m-0 text-[13px] text-ink-3">{participation.comment}</p>
          )}
        </div>
      ))}
    </div>
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
    <div className="flex flex-col gap-2 border-t border-white/8 pt-3" data-testid="Review.Comments">
      <h3 className="m-0 text-[12px] font-medium text-ink-2">Comments</h3>

      {comments.length === 0 ? (
        <p className="m-0 text-[12.5px] text-ink-4" data-testid="Review.Comments.Empty">
          No comments yet.
        </p>
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
    </div>
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
    <div
      data-testid="Review.Thread"
      className="border-l-2 pl-3"
      style={{
        borderColor: resolved ? 'var(--color-passed)' : 'rgb(255 255 255 / 8%)',
        opacity: resolved ? 0.7 : 1,
      }}
    >
      <CommentBody comment={thread} testId="Review.Thread.Root" />

      {resolved && (
        <span className="block text-[12px] text-ink-4" data-testid="Review.Thread.Resolved">
          {/*
            The resolver is named, which is the whole reason `V24` added the column: a thread marked
            dealt with by nobody is the one entry an auditor cannot follow up. Null survives it, for
            `submittedBy`'s reason — the account may have been offboarded since.
          */}
          Resolved by {thread.resolvedBy?.displayName ?? 'a former member'} ·{' '}
          {formatDateTime(thread.resolvedAt ?? '')}
        </span>
      )}

      {thread.replies.length > 0 && (
        <div className="mt-1.5 flex flex-col gap-2 pl-4" data-testid="Review.Thread.Replies">
          {thread.replies.map((reply) => (
            <CommentBody key={reply.id} comment={reply} testId="Review.Thread.Reply" />
          ))}
        </div>
      )}

      {isOpen && (
        <div className="mt-1.5 flex flex-wrap gap-2">
          <PermissionGate projectId={projectId} permission="case:comment">
            {({ allowed }) => (
              <Button
                size="sm"
                variant="quiet"
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
                size="sm"
                variant="quiet"
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
        </div>
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
        <Banner tone="bad" className="mt-2" data-testid="Review.Thread.Error">
          <span>{describeError(resolution.error).body}</span>
        </Banner>
      )}
    </div>
  );
}

function CommentBody({ comment, testId }: { comment: ReviewComment; testId: string }) {
  return (
    <div data-testid={testId}>
      <span className="block text-[12px] text-ink-4">
        {comment.author?.displayName ?? 'A former member'} · {formatDateTime(comment.createdAt)}
      </span>
      {/*
        `pre-wrap`, because the body is prose a reviewer typed: a paragraph break they made is part
        of what they said, and collapsing it changes the remark.
      */}
      <p className="m-0 text-[13px] whitespace-pre-wrap text-ink-2">{comment.body}</p>
    </div>
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
    <div className="mt-2 flex flex-col gap-2" data-testid={testId}>
      <label htmlFor={`${testId}.Body.Label`} className="text-[12px] font-medium text-ink-2">
        {label}
      </label>
      <Textarea
        id={`${testId}.Body.Label`}
        rows={2}
        value={body}
        disabled={!allowed || add.isPending}
        onChange={(event) => setBody(event.target.value)}
        data-testid={`${testId}.Body`}
      />
      <div>
        <Button
          size="sm"
          variant="primary"
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
      </div>
      {add.isError && (
        <Banner tone="bad" data-testid={`${testId}.Error`}>
          <span>{describeError(add.error).body}</span>
        </Banner>
      )}
    </div>
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
    <div className="flex flex-col gap-2" data-testid="Review.VerdictForm">
      <label htmlFor="Review.Comment.Label" className="text-[12px] font-medium text-ink-2">
        Comment (optional)
      </label>
      <Textarea
        id="Review.Comment.Label"
        rows={2}
        value={comment}
        disabled={!allowed || record.isPending}
        onChange={(event) => setComment(event.target.value)}
        data-testid="Review.Comment"
      />
      <div className="flex flex-wrap gap-2">
        <Button
          size="sm"
          variant="primary"
          disabled={!allowed || record.isPending}
          onClick={() => submit('LGTM')}
          data-testid="Review.Lgtm"
        >
          <Check aria-hidden className="size-3.5" />
          Looks good
        </Button>
        <Button
          size="sm"
          disabled={!allowed || record.isPending}
          onClick={() => submit('CHANGES_REQUESTED')}
          data-testid="Review.RequestChanges"
        >
          <MessageSquare aria-hidden className="size-3.5" />
          Request changes
        </Button>
      </div>
      {/*
        In place, beside the review it is about. The 403 a caller can reach here is the one the
        disabled state already predicted; the 409 is the one it could not — somebody approved or
        sent the artifact back while this panel was open, which is ordinary rather than exceptional.
      */}
      {record.isError && (
        <Banner tone="bad" data-testid="Review.Error">
          <span>{describeError(record.error).body}</span>
        </Banner>
      )}
    </div>
  );
}

function EarlierReviews({ reviews }: { reviews: Review[] }) {
  return (
    <div data-testid="Review.Earlier">
      <span className="block text-[11px] font-medium tracking-[.06em] text-ink-4 uppercase">
        Earlier reviews
      </span>
      <div className="mt-1 flex flex-col gap-0.5">
        {reviews.map((review) => (
          <span key={review.id} className="text-[12.5px] text-ink-4">
            {reviewStateLabels[review.state]} · v{review.subjectVersionNo} ·{' '}
            {formatDateTime(review.createdAt)}
          </span>
        ))}
      </div>
    </div>
  );
}
