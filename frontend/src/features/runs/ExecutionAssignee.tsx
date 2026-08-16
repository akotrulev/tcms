import { Alert, Box, MenuItem, TextField, Typography } from '@mui/material';
import { useAssignExecution, useProjectMembers } from '../../api/queries';
import type { TestExecution } from '../../api/types';
import { PermissionGate } from '../../components/PermissionGate';
import { describeError } from '../../lib/describeError';

/**
 * Who is working this row of the checklist — **A4's control, and `assignee_id`'s first UI writer**.
 *
 * The field has been rendered on the run detail and on the execution queue since M2 and nothing in
 * the product could change it: the only writer was `POST /runs`'s whole-checklist `assigneeId`,
 * which the create dialog did not offer either. So every execution in the shipped product was
 * permanently unassigned, and the two screens that show an assignee could only ever print
 * "unassigned".
 *
 * ## It lives beside the verdict, not on the run's table
 *
 * The run detail renders one line per execution in a cell already carrying the executor and the
 * time; a select per row there is two hundred selects, most of them off screen, on the screen whose
 * job is the *summary*. Here there is one execution, the person looking at it is the person
 * deciding whether to work it, and "give this to somebody else" is the second thing they might do
 * after "record a verdict".
 *
 * ## Three things it does not do
 *
 * - **No optimistic update.** The write is a single field and the response carries the whole
 *   execution, including the bumped `lockVersion` the verdict save needs as its next `If-Match` —
 *   so painting the new name before the server answers would leave the runner holding a version it
 *   invented. `useAssignExecution` writes the served row into the cache instead.
 * - **No "assign to me" shortcut.** It would be a second control for a value already one click
 *   away in the same list, and the list is where somebody who is *not* the reader also lives.
 * - **No prediction of the run-closed refusal beyond `frozen`.** The page already knows the run has
 *   ended and disables the control; anything else the server refuses arrives as a sentence below.
 */
export function ExecutionAssignee({
  projectId,
  runId,
  execution,
  frozen,
}: {
  projectId: string;
  runId: string;
  execution: TestExecution;
  /** The run has ended, so its checklist is frozen — the server answers 409 (§6t's sibling rule). */
  frozen: boolean;
}) {
  const members = useProjectMembers(projectId, true);
  const assign = useAssignExecution(projectId, runId, execution.id);

  const current = execution.assignee;
  const listed = members.data ?? [];
  /*
    The current assignee may not be in the list, and it is not an error state: `GET /members`
    answers "active member holding a grant on this project", while the column merely requires
    active company membership — so somebody whose project grant was revoked after being assigned
    is a real row. Dropping them would render a select whose value matches no option, which MUI
    shows as blank: the screen would say "unassigned" about a case that is assigned.
  */
  const options =
    current && !listed.some((member) => member.id === current.id) ? [current, ...listed] : listed;

  return (
    <Box data-testid="Execution.Assignee">
      <PermissionGate projectId={projectId} permission="run:execute">
        {({ allowed }) => (
          <TextField
            label="Assigned to"
            select
            fullWidth
            size="small"
            // Controlled: the value is the server's answer, so a refused write leaves the control
            // showing who the row actually belongs to rather than who somebody tried to give it to.
            value={current?.id ?? ''}
            // §31 — disabled with a reason rather than hidden, so a tester learns the affordance
            // exists and is somebody else's to take. `PermissionGate` supplies the tooltip.
            disabled={!allowed || frozen || assign.isPending}
            onChange={(event) => void assign.mutateAsync(event.target.value || null).catch(() => {})}
            helperText={
              frozen
                ? 'This run has ended, so its checklist can no longer be reorganised.'
                : 'Whoever this run expects to work the case.'
            }
            slotProps={{ htmlInput: { 'data-testid': 'Execution.Assignee.Select' } }}
          >
            <MenuItem value="" data-testid="Execution.Assignee.None">
              <Box component="span" sx={{ color: 'text.secondary' }}>
                Nobody
              </Box>
            </MenuItem>
            {options.map((member) => (
              <MenuItem
                key={member.id}
                value={member.id}
                data-testid={`Execution.Assignee.${member.id}`}
              >
                {member.displayName}
              </MenuItem>
            ))}
          </TextField>
        )}
      </PermissionGate>
      {/*
        The refusal in place rather than in a corner toast — `useAssignExecution` marks the
        mutation handled for exactly this reason. The one that matters is the 409: the run ended
        while this page was open, and the sentence names reopening as the way forward.
      */}
      {assign.isError && (
        <Alert severity="error" role="alert" sx={{ mt: 1 }} data-testid="Execution.Assignee.Error">
          <Typography variant="body2">{describeError(assign.error).body}</Typography>
        </Alert>
      )}
    </Box>
  );
}
