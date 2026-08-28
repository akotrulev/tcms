import {
  Alert,
  Box,
  Button,
  Chip,
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableRow,
  Tooltip,
} from '@mui/material';
import { useState } from 'react';
import { ApiError } from '../../api/client';
import { useRevokeRole } from '../../api/queries';
import type { RoleAssignment } from '../../api/types';
import { PermissionGate } from '../../components/PermissionGate';
import { ScrollX } from '../../components/ScrollX';
import { describeError } from '../../lib/describeError';
import { EM_DASH, formatDateTime } from '../../lib/format';

/**
 * One grant per row, with the Revoke the server will accept — shared by the company screen and
 * the project one.
 *
 * It is shared rather than copied because the interesting part is not the markup: it is the
 * **scope-dependent gate** (`project:members` for a row naming a project, `company:members` for
 * one that does not) and the **one refusal `PermissionGate` cannot predict** — a caller may not
 * revoke the grant carrying their own ability to revoke, which is a fact about the row rather than
 * about the caller. Two implementations of those would be two chances to render a Revoke the
 * server refuses, or to hide one it would have allowed. The same call `lib/labels.ts` and
 * `DataRegion` are the answer to elsewhere in this app.
 *
 * The list itself is already exactly the set the caller could revoke — the server filters by
 * `AdminReach.mayStaff` rather than by visibility — so the gate here agrees with the filter that
 * produced the rows rather than second-guessing it.
 */
export function RoleAssignmentTable({
  rows,
  testId,
  /**
   * Hides the Project column. Set on a screen that is *about* one project, where every row would
   * repeat its name — the column is what tells a company-wide list which project a row is about,
   * and it carries no information on a list that has already answered that in its heading.
   */
  showProject = true,
  /**
   * Renders the action cell as a note instead of a Revoke, with this sentence as the reason.
   *
   * Used for the inherited rows on a project screen. Revoking one there is a legitimate action
   * with a **company-wide** effect — it removes the role from every project — so the control for
   * it lives on the company screen, where the blast radius is what the page is about. This is not
   * §31's disable-don't-hide being waived: nothing is refused here, the action simply belongs to a
   * different scope and the row says which.
   */
  readOnlyReason,
  /** Marks a row as replaced by a grant made on this project — see `roleAccess.ts`. */
  isOverridden,
}: {
  rows: readonly RoleAssignment[];
  testId: string;
  showProject?: boolean;
  readOnlyReason?: string;
  isOverridden?: (grant: RoleAssignment) => boolean;
}) {
  const revoke = useRevokeRole();
  /*
    Keyed by assignment id rather than held as one string. The self-lockout refusal belongs to the
    row that caused it — a single `error` would move the message onto whichever row was clicked
    next, which is how a warning about YOUR grant ends up under somebody else's name.
  */
  const [rowErrors, setRowErrors] = useState<Record<string, string>>({});

  const onRevoke = async (assignment: RoleAssignment) => {
    setRowErrors((current) => ({ ...current, [assignment.id]: '' }));
    try {
      await revoke.mutateAsync(assignment.id);
    } catch (error) {
      // The one refusal `PermissionGate` cannot predict: "you may not revoke the grant carrying
      // your own ability to revoke" is a fact about the ROW, not about the caller's permissions,
      // so it can only be surfaced where it happens.
      const message =
        error instanceof ApiError && error.fieldErrors?.assignmentId
          ? error.fieldErrors.assignmentId.join(' ')
          : describeError(error).body;
      setRowErrors((current) => ({ ...current, [assignment.id]: message }));
    }
  };

  return (
    <ScrollX>
      <Table size="small" data-testid={`${testId}.Grid`}>
        <TableHead>
          <TableRow>
            <TableCell sx={{ color: 'text.secondary' }}>Who</TableCell>
            <TableCell sx={{ color: 'text.secondary', width: 140 }}>Role</TableCell>
            {showProject && (
              <TableCell sx={{ color: 'text.secondary', width: 180 }}>Project</TableCell>
            )}
            <TableCell sx={{ color: 'text.secondary', width: 160 }}>Granted via</TableCell>
            <TableCell sx={{ color: 'text.secondary', width: 130 }}>Granted</TableCell>
            <TableCell sx={{ color: 'text.secondary', width: 130 }} align="right">
              {/* Header intentionally blank-labelled: the column holds one action per row. */}
              Access
            </TableCell>
          </TableRow>
        </TableHead>
        <TableBody>
          {[...rows]
            // `projectName` is null for a system-scoped role (an Admin holds it instance-wide,
            // not on a project), so those sort first under an explicit label rather than
            // crashing the comparator or drifting to the bottom unexplained.
            .sort(
              (a, b) =>
                (a.projectName ?? '').localeCompare(b.projectName ?? '') ||
                (a.user?.displayName ?? '').localeCompare(b.user?.displayName ?? ''),
            )
            .map((assignment) => {
              const overridden = isOverridden?.(assignment) ?? false;
              return (
                <TableRow
                  key={assignment.id}
                  data-testid={`${testId}.Row`}
                  hover
                  // The row is still readable — an overridden grant is a real grant that a
                  // project decision has replaced, and hiding it would leave an administrator
                  // wondering where the company-wide role went.
                  sx={overridden ? { opacity: 0.62 } : undefined}
                >
                  <TableCell sx={{ fontWeight: 500 }}>
                    {assignment.user?.displayName ?? EM_DASH}
                  </TableCell>
                  <TableCell>
                    <Chip
                      size="small"
                      variant="outlined"
                      label={assignment.role.name}
                      sx={overridden ? { textDecoration: 'line-through' } : undefined}
                    />
                  </TableCell>
                  {showProject && (
                    <TableCell>
                      {assignment.projectName ?? (
                        <Tooltip
                          title={
                            assignment.role.scope === 'PROJECT'
                              ? 'Held on every project in the company, unless a project grants this person something else.'
                              : 'A company-scoped role applies across every project.'
                          }
                        >
                          <Box component="span" sx={{ color: 'text.secondary' }}>
                            {assignment.role.scope === 'PROJECT' ? 'Every project' : 'Company-wide'}
                          </Box>
                        </Tooltip>
                      )}
                    </TableCell>
                  )}
                  <TableCell sx={{ fontSize: '0.75rem', color: 'text.secondary' }}>
                    {/* Whether a role came through a team or directly determines how you revoke
                        it — removing a person from a project won't help if the team grants it. */}
                    {assignment.team ? `${assignment.team.name} (team)` : 'granted directly'}
                  </TableCell>
                  <TableCell sx={{ fontSize: '0.75rem', color: 'text.secondary' }}>
                    {formatDateTime(assignment.grantedAt)}
                    {/*
                      Who made the grant (005 S1) — stored since M0 and served since 2026-08-28, so
                      this is a wire addition and not a new fact.

                      Beside the date rather than in a column of its own: the two are one answer to
                      one question — *how did this person come to hold this here* — and a grant with
                      a date and no author is the half of it that cannot be followed up.

                      **Null is ordinary and renders as nothing**, never as "unknown": the
                      provisioning command writes grants with no person behind them, and so does a
                      granter whose account no longer resolves. A row that said "granted by unknown"
                      would invite somebody to go looking for one.
                    */}
                    {assignment.grantedBy && (
                      <Box component="span" sx={{ display: 'block' }} data-testid={`${testId}.Row.GrantedBy`}>
                        by {assignment.grantedBy.displayName}
                      </Box>
                    )}
                    {rowErrors[assignment.id] && (
                      <Alert
                        severity="error"
                        role="alert"
                        sx={{ mt: 0.5, py: 0, fontSize: '0.7rem' }}
                        data-testid={`${testId}.Row.Error`}
                      >
                        {rowErrors[assignment.id]}
                      </Alert>
                    )}
                  </TableCell>
                  <TableCell align="right">
                    {overridden ? (
                      <Tooltip title="This project grants them a role of its own, and a project grant replaces the company-wide one outright — even when it confers less.">
                        <Chip
                          size="small"
                          label="replaced here"
                          data-testid={`${testId}.Row.Overridden`}
                          sx={{
                            bgcolor: 'transparent',
                            color: 'var(--color-deprecated)',
                            border:
                              '1px solid color-mix(in srgb, var(--color-deprecated) 45%, transparent)',
                          }}
                        />
                      </Tooltip>
                    ) : readOnlyReason ? (
                      <Tooltip title={readOnlyReason}>
                        <Box
                          component="span"
                          sx={{ fontSize: '0.75rem', color: 'text.secondary' }}
                          data-testid={`${testId}.Row.ReadOnly`}
                        >
                          company-wide
                        </Box>
                      </Tooltip>
                    ) : (
                      /*
                        The same scope-dependent gate the server applies, and the same one the
                        grant dialog applies — a row naming a project asks that project's
                        `project:members`, a company-wide row asks `company:members`. Disabled
                        with a reason rather than hidden: an action cell that empties on some rows
                        shifts the whole column.
                      */
                      <PermissionGate
                        projectId={assignment.projectId ?? 'company'}
                        permission={assignment.projectId ? 'project:members' : 'company:members'}
                      >
                        {({ allowed }) => (
                          <Button
                            size="small"
                            color="error"
                            disabled={!allowed || revoke.isPending}
                            onClick={() => void onRevoke(assignment)}
                            data-testid={`${testId}.Row.Revoke`}
                          >
                            Revoke
                          </Button>
                        )}
                      </PermissionGate>
                    )}
                  </TableCell>
                </TableRow>
              );
            })}
        </TableBody>
      </Table>
    </ScrollX>
  );
}
