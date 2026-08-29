import { useMemo, useState } from 'react';
import { usePutTeamMember, useRemoveTeamMember, useUsers } from '../../api/queries';
import type { Team } from '../../api/types';
import { DataRegion } from '../../components/DataRegion';
import {
  Banner,
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeaderCell,
  TableRow,
} from '../../components/kit';
import {
  Button,
  Dialog,
  DialogContent,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '../../components/ui';
import { describeError } from '../../lib/describeError';

/**
 * Who is in a team, and who leads it.
 *
 * ## Every write here goes straight to the server — there is no Save
 *
 * Each row's checkbox is one `PUT`/`DELETE` on that person's own membership URL, which is what the
 * contract models: a membership is a state at an address, so the write is idempotent by
 * construction and a half-applied batch cannot exist. The alternative — collect changes, submit a
 * list — would need the server to reconcile a set, and §68 recorded what that costs: a reconcile is
 * bounded by what the client was *served*, so anything the caller could not see gets silently
 * dropped. There is such a population here: the org chart resolves names through company
 * membership and drops an id it cannot place, so a legacy row naming somebody outside the company
 * renders nowhere. A reconcile would delete it without being asked; per-person writes cannot.
 *
 * ## Leaving the team is an access change, not a tidy-up
 *
 * A team can hold role grants, so removing somebody takes those grants away on their very next
 * request. The copy says so, because the screen otherwise reads like an address book.
 */

const TestId = 'Admin.TeamMembers';

export function TeamMembersDialog({
  open,
  team,
  onClose,
}: {
  open: boolean;
  team: Team;
  onClose: () => void;
}) {
  const users = useUsers();
  const put = usePutTeamMember();
  const remove = useRemoveTeamMember();
  const [failure, setFailure] = useState<string | null>(null);

  const memberIds = useMemo(() => new Set(team.members.map((m) => m.id)), [team.members]);
  const leadIds = useMemo(() => new Set(team.leads.map((l) => l.id)), [team.leads]);

  /*
    The people list is the company's, not the team's, because this dialog's job is to move people
    between the two sets. Deactivated accounts are kept rather than filtered: somebody who has left
    may still be in the team, and hiding the row would leave a member nobody can take out — §68's
    rule that a row nothing renders is a row nobody can act on.
  */
  const people = useMemo(
    () =>
      [...(users.data ?? [])].sort(
        (a, b) =>
          Number(!memberIds.has(a.id)) - Number(!memberIds.has(b.id)) ||
          a.displayName.localeCompare(b.displayName),
      ),
    [users.data, memberIds],
  );

  const busy = put.isPending || remove.isPending;

  const setMembership = async (userId: string, member: boolean, lead: boolean) => {
    setFailure(null);
    try {
      if (member) await put.mutateAsync({ teamId: team.id, userId, input: { lead } });
      else await remove.mutateAsync({ teamId: team.id, userId });
    } catch (error) {
      // In place, beside the row that failed, rather than as a toast: the useful sentence names
      // the person, and the checkbox has already snapped back to the server's answer.
      setFailure(describeError(error).body);
    }
  };

  return (
    <Dialog open={open} onOpenChange={(next: boolean) => !next && onClose()}>
      <DialogContent className="max-w-lg" data-testid={TestId}>
        <DialogHeader>
          <DialogTitle>{team.name} — members</DialogTitle>
        </DialogHeader>

        <div className="mt-4 flex max-h-[62vh] flex-col gap-4 overflow-y-auto pr-1">
          {failure && (
            <div data-testid={`${TestId}.Error`}>
              <Banner tone="bad">
                <span role="alert">{failure}</span>
              </Banner>
            </div>
          )}

          {team.projects.length > 0 && (
            <div data-testid={`${TestId}.GrantsWarning`}>
              <Banner tone="warn">
                <span>
                  This team holds{' '}
                  {team.projects.length === 1 ? 'a role' : `${team.projects.length} roles`} on{' '}
                  {team.projects.map((p) => p.projectName).join(', ')}. Anyone you add here gets{' '}
                  {team.projects.length === 1 ? 'it' : 'them'} immediately.
                </span>
              </Banner>
            </div>
          )}

          <DataRegion
            testId={`${TestId}.People`}
            state={{ ...users, refetch: () => void users.refetch() }}
            isEmpty={(rows) => rows.length === 0}
            empty={{
              kind: 'no-records',
              title: 'No people',
              body: 'Invite somebody to the company before building teams.',
            }}
          >
            {() => (
              <Table data-testid={`${TestId}.Grid`} className="min-w-[420px]">
                <TableHead>
                  <TableRow>
                    <TableHeaderCell>Person</TableHeaderCell>
                    <TableHeaderCell className="w-[100px]" align="right">
                      In team
                    </TableHeaderCell>
                    <TableHeaderCell className="w-[100px]" align="right">
                      Leads
                    </TableHeaderCell>
                  </TableRow>
                </TableHead>
                <TableBody>
                  {people.map((person) => {
                    const isMember = memberIds.has(person.id);
                    const isLead = leadIds.has(person.id);
                    return (
                      <TableRow key={person.id} data-testid={`${TestId}.Row`}>
                        <TableCell variant="title" subtitle={person.email}>
                          {person.displayName}
                        </TableCell>
                        <TableCell align="right">
                          {/*
                            ~~The testid goes on the INPUT, not the wrapper: a bare `data-testid` on
                            a MUI Checkbox lands on the wrapper span, where `toBeChecked` cannot see
                            it and every assertion quietly passes.~~ → **superseded (007 T15).**
                            There is no wrapper — this is the platform's checkbox, which is also
                            the one a test can click without a pointer-API shim.
                          */}
                          <input
                            type="checkbox"
                            className="size-4 accent-[color:var(--color-steel)]"
                            checked={isMember}
                            disabled={busy}
                            aria-label={`${person.displayName} is in ${team.name}`}
                            onChange={(event) =>
                              void setMembership(person.id, event.target.checked, false)
                            }
                            data-testid={`${TestId}.Member`}
                          />
                        </TableCell>
                        <TableCell align="right">
                          <input
                            type="checkbox"
                            className="size-4 accent-[color:var(--color-steel)] disabled:opacity-45"
                            checked={isLead}
                            disabled={busy || !isMember}
                            title={
                              isMember
                                ? 'A lead is a member with a flag — it grants nothing extra.'
                                : 'Add them to the team first.'
                            }
                            aria-label={`${person.displayName} leads ${team.name}`}
                            onChange={(event) =>
                              void setMembership(person.id, true, event.target.checked)
                            }
                            data-testid={`${TestId}.Lead`}
                          />
                        </TableCell>
                      </TableRow>
                    );
                  })}
                </TableBody>
              </Table>
            )}
          </DataRegion>
        </div>

        <DialogFooter>
          {/* "Done", not "Save": every change here has already been written. */}
          <Button onClick={onClose} variant="primary" data-testid={`${TestId}.Done`}>
            Done
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
