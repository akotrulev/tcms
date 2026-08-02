import {
  Alert,
  Button,
  Checkbox,
  Dialog,
  DialogActions,
  DialogContent,
  DialogTitle,
  Stack,
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableRow,
  Tooltip,
  Typography,
} from '@mui/material';
import { useMemo, useState } from 'react';
import { usePutTeamMember, useRemoveTeamMember, useUsers } from '../../api/queries';
import type { Team } from '../../api/types';
import { DataRegion } from '../../components/DataRegion';
import { ScrollX } from '../../components/ScrollX';
import { describeError } from '../../lib/describeError';
import { inputTestId } from '../../lib/muiTestIds';

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
    <Dialog open={open} onClose={onClose} fullWidth maxWidth="sm" data-testid={TestId}>
      <DialogTitle>{team.name} — members</DialogTitle>
      <DialogContent>
        <Stack spacing={2} sx={{ mt: 1 }}>
          {failure && (
            <Alert severity="error" role="alert" data-testid={`${TestId}.Error`}>
              {failure}
            </Alert>
          )}

          {team.projects.length > 0 && (
            <Alert severity="warning" data-testid={`${TestId}.GrantsWarning`}>
              This team holds{' '}
              {team.projects.length === 1 ? 'a role' : `${team.projects.length} roles`} on{' '}
              {team.projects.map((p) => p.projectName).join(', ')}. Anyone you add here gets{' '}
              {team.projects.length === 1 ? 'it' : 'them'} immediately.
            </Alert>
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
              <ScrollX>
                <Table size="small" data-testid={`${TestId}.Grid`}>
                  <TableHead>
                    <TableRow>
                      <TableCell sx={{ color: 'text.secondary' }}>Person</TableCell>
                      <TableCell sx={{ color: 'text.secondary', width: 100 }} align="center">
                        In team
                      </TableCell>
                      <TableCell sx={{ color: 'text.secondary', width: 100 }} align="center">
                        Leads
                      </TableCell>
                    </TableRow>
                  </TableHead>
                  <TableBody>
                    {people.map((person) => {
                      const isMember = memberIds.has(person.id);
                      const isLead = leadIds.has(person.id);
                      return (
                        <TableRow key={person.id} data-testid={`${TestId}.Row`} hover>
                          <TableCell>
                            <Typography variant="body2">{person.displayName}</Typography>
                            <Typography variant="caption" sx={{ color: 'text.secondary' }}>
                              {person.email}
                            </Typography>
                          </TableCell>
                          <TableCell align="center">
                            {/*
                              The testid goes on the INPUT, not the wrapper. A bare `data-testid` on
                              a MUI Checkbox lands on the wrapper span, where `toBeChecked` cannot
                              see it and every assertion quietly passes — §68's own lesson, in its
                              own test.
                            */}
                            <Checkbox
                              size="small"
                              checked={isMember}
                              disabled={busy}
                              onChange={(event) =>
                                void setMembership(person.id, event.target.checked, false)
                              }
                              slotProps={inputTestId(
                                `${TestId}.Member`,
                                `${person.displayName} is in ${team.name}`,
                              )}
                            />
                          </TableCell>
                          <TableCell align="center">
                            <Tooltip
                              title={
                                isMember
                                  ? 'A lead is a member with a flag — it grants nothing extra.'
                                  : 'Add them to the team first.'
                              }
                            >
                              <span>
                                <Checkbox
                                  size="small"
                                  checked={isLead}
                                  disabled={busy || !isMember}
                                  onChange={(event) =>
                                    void setMembership(person.id, true, event.target.checked)
                                  }
                                  slotProps={inputTestId(
                                    `${TestId}.Lead`,
                                    `${person.displayName} leads ${team.name}`,
                                  )}
                                />
                              </span>
                            </Tooltip>
                          </TableCell>
                        </TableRow>
                      );
                    })}
                  </TableBody>
                </Table>
              </ScrollX>
            )}
          </DataRegion>
        </Stack>
      </DialogContent>
      <DialogActions>
        {/* "Done", not "Save": every change here has already been written. */}
        <Button onClick={onClose} variant="contained" data-testid={`${TestId}.Done`}>
          Done
        </Button>
      </DialogActions>
    </Dialog>
  );
}
