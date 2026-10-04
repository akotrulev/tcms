import { Link as RouterLink, useParams } from 'react-router-dom';
import { ApiError } from '../../api/client';
import { useTeamBoard, useTeams } from '../../api/queries';
import { DataRegion } from '../../components/DataRegion';
import { PageHeader } from '../../components/PageHeader';
import { Banner } from '../../components/kit';
import { buttonVariants } from '../../components/ui';
import { Board } from './BoardPage';

/**
 * §6l's **team board** (§84) — one team's work, across every project the *reader* can see.
 *
 * The last of §6l's three boards to get a screen. The project board shipped in §53, the personal
 * one in §83, and all three are one endpoint with one response shape: `projectId` narrows *where*,
 * `teamId` and `mine` narrow *whose*. So this screen is a third caller of `Board`, not a third
 * board — the same reason §83 stripped the project out of `Board` in the first place.
 *
 * <h2>Why the route is `/teams/:teamId/board`</h2>
 *
 * Two things had to be decided: whether it lives under a project, and whether it lives under
 * `/admin`.
 *
 * **Not under `/projects/:projectId`**, for §83's reason unchanged: with `projectId` omitted the
 * cards span every project the reader can open, so any project in the URL would be an arbitrary
 * one — a breadcrumb naming a project most of the cards are not from, and a rail switcher that
 * appears to change a board it cannot change.
 *
 * **Not under `/admin/teams` either**, which is the call this slice adds. `/admin/teams` is the
 * company administration screen and its every affordance is gated on `company:teams`; this board is
 * gated on **nothing at all**. Any authenticated member of the company may open any team's board —
 * §6k's rule that the org chart itself is readable by every member, and §84's finding that there is
 * no permission key to gate it on and inventing one would be a 403 no administrator could grant
 * their way out of. Nesting a screen every member may open beneath a prefix that reads
 * "administration" teaches the opposite of what is true, and the URL is the part of a screen people
 * quote to each other.
 *
 * So `/teams/:teamId/board`, beside `/admin/teams` rather than beneath it. It is deliberately the
 * same shape as `/projects/:projectId/board`, and that is **not** the collision §83 refused: the
 * name `/board` alone would have been one word for two scopes, while a board whose subject is named
 * by its own segment reads unambiguously at both. The team list is the entry point — a `Board` link
 * per row, the one team affordance there with no permission gate on it.
 *
 * <h2>The bound is the READER's, and this screen must not pretend otherwise</h2>
 *
 * The cards are bounded by the caller's own project grants, so two people looking at the same team
 * board legitimately see different cards. That is the design (§84: a board built from "everything
 * assigned to this team" leaks the case keys, titles and project names of every project any member
 * was granted, to a reader granted none of them) — and it is a constraint on what may be *rendered*.
 * There is no count of the team's work, no capacity or load figure, and no "N items" anywhere: a
 * number computed from a partial view reads as canonical and is wrong for everybody but its reader.
 * The subtitle says whose view this is instead. The per-column counts `Board` draws are safe for
 * the same reason a project board's are — they count what is on screen and claim nothing else.
 *
 * <h2>What it deliberately does NOT have</h2>
 *
 * **No filter chips.** `teamId` with `mine=true` is a **400** ("Choose one board") — they select
 * two different boards, and the intersection is a fourth nobody asked for. The refusal is
 * unreachable from here because no control can express it, which is the point: `ui-rules` §4, a
 * control whose only outcome is an error is not a control.
 *
 * **No project narrowing.** `teamId` + `projectId` *is* legal on the server, and it is §6l's
 * "swimlanes by member" — but a swimlane is a second axis on a grid that currently has one, and
 * shipping the filter without the layout would be a project picker that answers a question §6l did
 * not ask. Named as not built rather than quietly dropped.
 *
 * **No "New task".** The same reason as My Work: a create needs a project, and this screen has
 * none — and a task created here would also need an assignee inside the team to belong on the board
 * it was created from, which is a rule nothing on the server enforces.
 */
export function TeamBoardPage() {
  const { teamId = '' } = useParams();
  const items = useTeamBoard(teamId);
  /*
    The team's name, for the header — read from `GET /teams`, which declares no 403 and is readable
    by any authenticated member (§6k). That matters: a title fetched through an endpoint stricter
    than the board would leave the screen nameless for exactly the reader §84 says may open it.

    It is deliberately NOT a `DataRegion`: the name is chrome, and a board that renders under
    "Team board" because the org chart failed to load is strictly better than one that refuses to
    render at all. A team the list does not contain also falls through to the fallback rather than
    asserting anything — the board's own 404 below is what says "no such team", and two components
    answering that question is how they come to disagree.
  */
  const teams = useTeams();
  const team = teams.data?.find((row) => row.id === teamId);

  /*
    **The one refusal this screen has to word itself.**

    The server's 404 is empty-bodied and indistinguishable across its three causes on purpose (§6t:
    otherwise this operation is a company-wide team-existence oracle answerable with a guessed
    UUID). `describeError` would therefore render "Not found — Not Found", the HTTP status text,
    which tells a reader nothing and looks like a bug.

    Keyed on `status === 404` and not on the body, because there is no body — and it is safe to name
    the team as the missing thing here specifically because **this hook sends no `projectId`**: the
    operation's other 404 arm cannot be reached from this screen. §82's rule, in the same shape as
    `RunCreateDialog`'s 409: key on the one thing the request could have got wrong, never on
    matching a server sentence.

    It has to be visibly distinct from `[]`, which is the whole reason it is not left to the empty
    state. A team with no members and a team whose members have no work the reader can see are both
    a legitimate empty board; a team that does not exist is a broken link, and the way out is the
    team list, so the way out is offered.
  */
  const missing = items.isError && items.error instanceof ApiError && items.error.status === 404;

  return (
    <div>
      <PageHeader
        testId="TeamBoard.Header"
        title={team ? `${team.name} — board` : 'Team board'}
        documentTitle={team ? `Board · ${team.name}` : 'Team board'}
        subtitle="Work assigned to this team’s members, in every project you can see. Somebody with different project access will see a different set."
      />

      {missing ? (
        <div role="alert">
          <Banner tone="bad" data-testid="TeamBoard.Missing">
            <div className="flex flex-col items-start gap-2">
              <p className="m-0 font-medium text-ink">No such team</p>
              <p className="m-0">
                This team doesn’t exist, or it belongs to another company. It may have been deleted
                since this link was made.
              </p>
              <RouterLink
                to="/admin/teams"
                data-testid="TeamBoard.Missing.Teams"
                className={buttonVariants({ variant: 'danger', size: 'sm' })}
              >
                Back to teams
              </RouterLink>
            </div>
          </Banner>
        </div>
      ) : (
        <DataRegion
          testId="TeamBoard.Content"
          state={{ ...items, refetch: () => void items.refetch() }}
          isEmpty={(rows) => rows.length === 0}
          empty={{
            kind: 'no-records',
            /*
              Reachable and empty — a team with no members and a team whose members have no work
              this reader can open are deliberately the same answer on the wire, so they are the
              same sentence here. Wording that guessed between them would claim to know something
              the response does not carry, and "this team has no members" is a specific, checkable,
              frequently wrong statement to put on screen.
            */
            title: 'Nothing on this team’s board',
            body: 'No executions, reviews or tasks are waiting on this team in any project you can see.',
          }}
        >
          {/*
            The project board's own component (§83), with `crossProject` on: a team's work spans
            projects, so a card that does not say which one it is from is unplaceable — and unlike
            My Work, the reader here is looking at other people's work, so the assignee `Board`
            already renders on every card is the field that makes the board legible.
          */}
          {(rows) => <Board items={rows} crossProject />}
        </DataRegion>
      )}
    </div>
  );
}
