import { Link as RouterLink } from 'react-router-dom';
import { useBoardDoors, useBoardsOf, usePendingProposalCounts } from '../../api/queries';
import type { BoardCard, BoardIssueCard } from '../../api/types';
import { useCurrentUserId } from '../../auth/useAuth';
import { Panel } from '../../components/PageHeader';
import { cn } from '../../lib/cn';
import { plural } from '../../lib/format';

const TEST_ID = 'MyWork.Awaiting';

/**
 * My work's **Awaiting you** strip — §8.4's five items, `07-my-work-home.html` (021 T8).
 *
 * <h2>Four tiles that count and one link that does not</h2>
 *
 * Reviews assigned · a tile per board with something at its door that you may move · your blocked
 * cards · proposals waiting · a plain `Timesheet approvals` link. The link carries **no badge**,
 * and that is a recorded decision rather than an omission: §8.3's badge table ends with
 * *"Timesheet approvals — none — the recorded decision stands"*, because an approvals count on a
 * personal home is a number that pressures the approver rather than informing them.
 *
 * **Every number is an *awaiting-you* count, never a total.** A tile reading `340` says nothing;
 * the whole strip exists to be a short list of things that are actually stuck on this person. A
 * count that is zero draws **no tile at all** rather than a `0` — the empty state is the sentence
 * at the foot, once, instead of four tiles saying nothing four times.
 *
 * <h2>What it reads, and the fan-out it deliberately bounds</h2>
 *
 * The reviews and the project set come from the read the page already makes — `GET /board?mine=true`,
 * one request, split on the client (see `MyWorkPage`). Everything else is per project, because
 * every read involved is per project: boards, board cards and the proposal queue all take a
 * project and there is no cross-project aggregate on the contract, deliberately (asking for one
 * would be a company-sized response, which is the shape `GET /board` with no project refuses).
 *
 * **The project set is the projects this reader has work in**, taken from the rows already on
 * screen — not `GET /projects`. That is the bound that keeps the strip from becoming N×M requests
 * on the one screen somebody keeps open all day: a reader in a company of forty projects has work
 * in two, and a door count for the thirty-eight they have never opened is not *awaiting them*. The
 * cost is real and stated: a board whose door has work waiting for the team, in a project this
 * person currently has nothing assigned in, is not on the strip. That is what the project's own
 * board is for.
 *
 * <h2>"Boards where you hold the moving key" — what that is derived from, and what it is not</h2>
 *
 * §8.4 and render note 24 ask for a tile per board **you may move cards on**, and note 24 glosses
 * it as *may move a card into that board's door column*. **There is no per-board permission on the
 * contract** — `issue:edit` is a project-scoped grant — so a literal reading is not derivable, and
 * a fan-out of `GET /me/permissions?projectId=` per project would answer the wrong question
 * anyway: it would light every board of a project or none of them.
 *
 * What *is* derivable, from the cards already fetched, is the honest half: `allowedTransitions` on
 * a card is **caller-scoped** (the contract says so — it drops `DONE` targets under the
 * review-to-close gate), so a door whose cards offer this caller no move is a door they cannot
 * work. A tile is drawn when the door has cards **and at least one of them can be moved by the
 * person reading**. A Test Engineer holding `issue:edit` on a project therefore sees a tile for
 * each of that project's boards with work at its door, where the render draws one — a difference
 * from note 24 that is recorded rather than faked, and that closes the day a board-scoped grant
 * exists.
 *
 * <h2>Blocked cards come from the same snapshot as the door counts</h2>
 *
 * `BoardCard` — the composite card this page's table is built from — carries **no**
 * `blockedFromStatus`; `BoardIssueCard` does. So the blocked tile is read out of the board
 * contents the door counts already fetched, filtered to cards assigned to the reader. Two reads of
 * the same cards is how two numbers on one strip come to disagree, so there is only one.
 */
export function AwaitingYou({ rows }: { rows: readonly BoardCard[] }) {
  const me = useCurrentUserId();

  /*
    Distinct, in first-seen order. A `Set` rather than a sort: the order the server sent the cards
    in is the order the reader is about to read them in, and re-sorting the project list by name
    would put the tiles in an order nothing else on the page uses.
  */
  const projectIds = [...new Set(rows.map((card) => card.projectId))];
  const projectKeys = new Map(rows.map((card) => [card.projectId, card.projectKey]));

  const boards = useBoardsOf(projectIds, projectIds.length > 0);
  const boardIds = boards.map((entry) => entry.board.id);
  /*
    `{}` — no filter. The strip asks what is at the door for the TEAM, and a filter would make the
    number depend on chips this screen does not have. It is also what keeps the key distinct from
    the project board's filtered one rather than colliding with it.
  */
  const doors = useBoardDoors(boardIds, {}, boardIds.length > 0);
  const proposals = usePendingProposalCounts(projectIds, projectIds.length > 0);

  const reviews = rows.filter((card) => card.kind === 'REVIEW');
  const reviewsWhere = countByProject(reviews.map((card) => card.projectKey));

  const tiles = boards.flatMap((entry) => {
    const waiting = doors.waiting[entry.board.id] ?? 0;
    if (waiting === 0) return [];
    const contents = doors.contents[entry.board.id];
    const door = doors.door[entry.board.id];
    if (!contents || door === undefined) return [];
    const atTheDoor = contents.lanes
      .flatMap((lane) => lane.cards)
      .filter((card) => card.columnId === door);
    if (!canMoveAtTheDoor(atTheDoor)) return [];
    return [{ ...entry, waiting }];
  });

  const blocked = Object.entries(doors.contents)
    .flatMap(([, contents]) => contents.lanes.flatMap((lane) => lane.cards))
    .filter((card) => card.blockedFromStatus != null && card.assignee?.id === me);
  /*
    A card claimed by two boards is fetched twice and is one card. Deduped on the key, which is the
    issue's identity — the alternative is a strip telling somebody they have two blocked cards
    because their project has a Dev board and a QA board that both claim `Blocked` (§2.4 says all
    three do).
  */
  const blockedKeys = [...new Set(blocked.map((card) => card.key))];

  const proposalsWaiting = Object.values(proposals).reduce((total, count) => total + count, 0);
  const proposalProject = Object.entries(proposals).find(([, count]) => count > 0)?.[0];

  const counted =
    reviews.length + tiles.length + blockedKeys.length + (proposalsWaiting > 0 ? 1 : 0);

  return (
    <div className="mb-3.5">
    <Panel
      dense
      title="Awaiting you"
      testId={TEST_ID}
      actions={
        <span className="text-[11.5px] text-ink-4" data-testid={`${TEST_ID}.Hint`}>
          {counted === 0 ? 'nothing is stuck on you' : 'the awaiting-you count, never a total'}
        </span>
      }
    >
      <div className="flex flex-wrap gap-2.5">
        {reviews.length > 0 && (
          <Tile
            testId={`${TEST_ID}.Reviews`}
            count={reviews.length}
            label={plural(reviews.length, 'review')}
            /*
              No link. Every review is already a row in the table below, in this screen's own
              second panel, and a tile linking to the page it is on is the dead affordance
              `ui-rules` calls a control that does nothing.
            */
            detail={reviewsWhere}
          />
        )}

        {tiles.map((entry) => (
          <Tile
            key={entry.board.id}
            testId={`${TEST_ID}.Door.${entry.board.name}`}
            count={entry.waiting}
            label={`At the ${entry.board.name}'s door`}
            detail={projectKeys.get(entry.projectId) ?? ''}
            to={`/projects/${entry.projectId}/work?view=board&board=${entry.board.id}`}
          />
        ))}

        {blockedKeys.length > 0 && (
          <Tile
            testId={`${TEST_ID}.Blocked`}
            count={blockedKeys.length}
            label="Your blocked cards"
            detail={blockedKeys.slice(0, 3).join(' · ')}
          />
        )}

        {proposalsWaiting > 0 && proposalProject !== undefined && (
          <Tile
            testId={`${TEST_ID}.Proposals`}
            count={proposalsWaiting}
            label={plural(proposalsWaiting, 'proposal')}
            detail="Test cases → Proposals"
            to={`/projects/${proposalProject}/generated-cases`}
          />
        )}

        {/*
          The link that does not count (§8.3). It is here as well as in the user menu because this
          screen is the post-sign-in landing and the menu is two clicks from it — the same row in
          two places is not a duplicate when one of them is a drawer.
        */}
        <RouterLink
          to="/timesheets/approvals"
          data-testid={`${TEST_ID}.Approvals`}
          className={cn(
            'flex min-w-[180px] flex-1 flex-col gap-0.5 rounded-lg border border-dashed border-line',
            'bg-transparent px-3 py-2.5 no-underline hover:border-line-strong',
            'focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-[color:var(--color-given)]',
          )}
        >
          <span className="text-[12.5px] font-medium text-ink">Timesheet approvals →</span>
          <span className="text-[11px] text-ink-4">A link, deliberately without a count.</span>
        </RouterLink>
      </div>

      {counted === 0 && (
        <p data-testid={`${TEST_ID}.None`} className="m-0 mt-2.5 text-[12px] text-ink-4">
          No review, no card at a board&rsquo;s door and no proposal is waiting on you right now.
        </p>
      )}
    </Panel>
    </div>
  );
}

/**
 * One counted tile.
 *
 * A link when there is somewhere to go and a plain `<div>` when there is not, rather than an `<a>`
 * with a `#` href: a link that navigates nowhere is announced as a link, focused as a link, and
 * does nothing when followed.
 */
function Tile({
  testId,
  count,
  label,
  detail,
  to,
}: {
  testId: string;
  count: number;
  label: string;
  detail: string;
  to?: string;
}) {
  const body = (
    <>
      <span className="font-mono text-[17px] leading-none font-semibold text-ink">{count}</span>
      <span className="text-[12.5px] font-medium text-ink">{label}</span>
      {detail !== '' && <span className="text-[11px] text-ink-4">{detail}</span>}
    </>
  );
  const className = cn(
    'flex min-w-[180px] flex-1 flex-col gap-0.5 rounded-lg border border-line bg-raised px-3 py-2.5 shadow-e1',
    to !== undefined
      && 'no-underline hover:border-line-strong focus-visible:outline-2'
      + ' focus-visible:outline-offset-2 focus-visible:outline-[color:var(--color-given)]',
  );

  return to === undefined ? (
    <div data-testid={testId} className={className}>
      {body}
    </div>
  ) : (
    <RouterLink to={to} data-testid={testId} className={className}>
      {body}
    </RouterLink>
  );
}

/**
 * `2 in ACME, 1 in BETA` — the render's detail line, and the reason the reviews tile needs one: a
 * bare `3` on a screen that spans projects does not say which product's afternoon it is.
 */
function countByProject(keys: readonly string[]): string {
  const counts = new Map<string, number>();
  for (const key of keys) counts.set(key, (counts.get(key) ?? 0) + 1);
  return [...counts].map(([key, count]) => `${count} in ${key}`).join(', ');
}

/**
 * Whether this caller can move anything sitting at the board's door.
 *
 * `allowedTransitions` is caller-scoped on the wire, so an empty set on every card at the door is
 * the contract saying *not yours to move* — which is the closest derivable reading of §8.4's
 * "where you hold the moving key". See this file's header for what it is not.
 */
function canMoveAtTheDoor(cards: readonly BoardIssueCard[]): boolean {
  return cards.some((card) => card.allowedTransitions.length > 0);
}
