import {
  Chip,
  Stack,
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableRow,
  Typography,
} from '@mui/material';
import CheckIcon from '@mui/icons-material/Check';
import { useRoles } from '../../api/queries';
import type { PermissionKey, Role } from '../../api/types';
import { DataRegion } from '../../components/DataRegion';
import { ScrollX } from '../../components/ScrollX';
import { Code, Definitions } from './prose';
import {
  allPermissions,
  areaLabels,
  permissionAreas,
  permissionLabels,
} from '../../lib/permissions';

/**
 * Who can do what — **read from this instance at runtime, never written down here.**
 *
 * This repository has paid for a hand-copied permission matrix five times, and
 * `packages/client/src/test/matrixCopies.test.ts` exists because two of the copies drifted. A
 * sixth copy on the one page whose whole purpose is to tell users the truth about their access
 * would be the worst-placed of them all: it is the copy nobody would think to check, read by the
 * people least able to notice it is wrong.
 *
 * So the columns come from `GET /roles` and the rows come from `lib/permissions.ts` — the same
 * catalogue `useCan` reads to phrase a disabled control's tooltip. A grant moved between roles by
 * a migration moves here on the next page load, with no commit, and an instance running an older
 * or newer seed shows *its* matrix rather than the one that was true when this file was written.
 *
 * `GET /roles` costs `company:read`, which every company member holds through the non-assignable
 * `Company Member` baseline — so this renders for every signed-in reader, which is the audience
 * the page was asked for. It is not gated in the UI for that reason: there is nothing to refuse.
 *
 * **This is not a second `RoleReference`**, which `features/admin/RoleReference.tsx` already is and
 * which the two admin screens share. That component answers *"if I grant this person Test Lead,
 * what have I just given them"* — one card per role, permissions expanded, and non-assignable
 * roles dropped because it sits beside a Grant button that would refuse them. This one answers
 * *"who can do this, and who cannot"*, which is a comparison across roles rather than a reading of
 * one, and it deliberately KEEPS the credential-only roles because a reader wondering what their
 * CI token or their agent can reach has nowhere else to look. Same source, two questions. If a
 * third view of the catalogue is ever wanted, it is worth asking which of these two it really is.
 *
 * **The ordering is the catalogue's, not the response's.** Roles arrive in whatever order the
 * server lists them; sorting by how much each holds puts the six built-ins in the tier order the
 * roles spec states them in, and makes the staircase down the table legible as a staircase.
 */
export function RoleMatrix() {
  const roles = useRoles();

  return (
    <DataRegion
      testId="Help.RoleMatrix"
      state={roles}
      isEmpty={(data) => data.length === 0}
      empty={{
        title: 'No roles to show',
        body: 'This instance returned no roles. That is unexpected — the six built-in roles are seeded.',
      }}
    >
      {(data) => <Matrix roles={data} />}
    </DataRegion>
  );
}

function Matrix({ roles }: { roles: Role[] }) {
  const columns = [...roles].sort((a, b) => a.permissions.length - b.permissions.length);
  const rows = allPermissions.filter((permission) =>
    columns.some((role) => role.permissions.includes(permission)),
  );

  return (
    <Stack sx={{ gap: 1.5 }}>
      <Definitions
        items={columns.map((role) => ({
          term: (
            <Stack direction="row" component="span" sx={{ gap: 1, alignItems: 'center' }}>
              <span data-testid={`Help.RoleMatrix.Role.${role.name}`}>{role.name}</span>
              {!role.assignable && (
                <Chip size="small" variant="outlined" label="Credentials only — never granted to a person" />
              )}
            </Stack>
          ),
          detail: role.description,
        }))}
      />

      <ScrollX>
        <Table size="small" sx={{ minWidth: 720 }} data-testid="Help.RoleMatrix.Table">
          <TableHead>
            <TableRow>
              <TableCell sx={{ fontWeight: 600 }}>What it lets you do</TableCell>
              {columns.map((role) => (
                <TableCell key={role.id} align="center" sx={{ fontWeight: 600 }}>
                  {role.name}
                </TableCell>
              ))}
            </TableRow>
          </TableHead>
          <TableBody>
            {rows.map((permission, index) => (
              <PermissionRow
                key={permission}
                permission={permission}
                columns={columns}
                showArea={index === 0 || permissionAreas[rows[index - 1]!] !== permissionAreas[permission]}
              />
            ))}
          </TableBody>
        </Table>
      </ScrollX>
    </Stack>
  );
}

/**
 * One permission across every role, with the area heading rendered on the first row of its group.
 *
 * The area label is a row cell rather than a `<TableRow>` of its own so the grouping survives the
 * horizontal scroll the wide table needs — a full-width group header inside a scrolling table
 * scrolls out of view sideways, taking the only thing that said what these rows have in common.
 */
function PermissionRow({
  permission,
  columns,
  showArea,
}: {
  permission: PermissionKey;
  columns: Role[];
  showArea: boolean;
}) {
  return (
    <TableRow hover data-testid={`Help.RoleMatrix.Row.${permission}`}>
      <TableCell>
        {showArea && (
          <Typography
            variant="caption"
            sx={{
              display: 'block',
              color: 'text.secondary',
              textTransform: 'uppercase',
              letterSpacing: '0.06em',
              fontWeight: 600,
              mt: 0.5,
            }}
          >
            {areaLabels[permissionAreas[permission]]}
          </Typography>
        )}
        <Typography variant="body2" component="span">
          {permissionLabels[permission]}
        </Typography>{' '}
        <Code>{permission}</Code>
      </TableCell>
      {columns.map((role) => {
        const held = role.permissions.includes(permission);
        return (
          <TableCell
            key={role.id}
            align="center"
            /*
              The whole sentence on the cell, because a tick and a dash carry it only for a reader
              who can see which column they are under. Out of that context — a screen reader moving
              cell by cell, a row read aloud — "✓" is not an answer to anything. It sits on the cell
              rather than on the glyph inside it so there is exactly one accessible name per cell,
              which a `titleAccess` on the icon plus a label on a wrapper would not be.
            */
            aria-label={`${role.name} ${held ? 'can' : 'cannot'} ${permissionLabels[permission]}`}
          >
            {held ? (
              <CheckIcon fontSize="small" color="success" aria-hidden />
            ) : (
              <Typography component="span" variant="body2" sx={{ color: 'text.disabled' }} aria-hidden>
                —
              </Typography>
            )}
          </TableCell>
        );
      })}
    </TableRow>
  );
}
