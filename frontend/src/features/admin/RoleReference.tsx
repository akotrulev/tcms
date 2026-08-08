import { Box, Chip, Stack, Tooltip, Typography } from '@mui/material';
import type { Role, RoleScope } from '../../api/types';
import { Identifier } from '../../components/Identifier';
import { allPermissions, groupByArea, holdsEveryPermission } from '../../lib/permissions';

/**
 * "What each role can do", with the permissions expanded — shared by the company screen and the
 * project one.
 *
 * An admin deciding whether to grant somebody Test Engineer needs to see that it excludes final
 * approval; a name alone doesn't say that, and the resulting guess is how people end up
 * over-privileged. That reasoning is identical on both screens, which is why there is one
 * component: the project screen showing a subtly different rendering of the same catalogue is how
 * two answers to "what does Test Lead mean" get into one product.
 */
export function RoleReference({
  roles,
  testId,
  /**
   * Narrows to the roles grantable at one scope. The project screen passes `PROJECT`, because a
   * COMPANY-scope role cannot be granted on a project at all (the server refuses it on `roleId`)
   * and offering its reference card next to a Grant button that will refuse it is the mis-teaching
   * the nav rail's own Company/Project regrouping was about.
   */
  scope,
}: {
  roles: readonly Role[];
  testId: string;
  scope?: RoleScope;
}) {
  /*
    Non-assignable roles are dropped on BOTH screens, and the argument is the one written above
    about scope, one axis over: this is the reference card sitting beside a Grant button, so a role
    that cannot be granted to anybody teaches the reader that it can.

    Agent (V40) is the case. It is PROJECT-scope, so the `scope` filter alone keeps it — and it is
    a ceiling an API token carries, refused by `POST /role-assignments` for every subject. Where it
    belongs is the token-issue form, which is why `GET /roles` returns it and why this component
    cannot be the place that decides.
  */
  const grantable = roles.filter((role) => role.assignable);
  const shown = scope ? grantable.filter((role) => role.scope === scope) : grantable;

  return (
    <Stack sx={{ gap: 2 }}>
      {shown.map((role) => (
        <Box key={role.id} data-testid={`${testId}.Item`}>
          <Stack direction="row" sx={{ alignItems: 'center', gap: 1, mb: 0.5, flexWrap: 'wrap' }}>
            <Typography variant="body2" sx={{ fontWeight: 600 }}>
              {role.name}
            </Typography>
            <Chip size="small" variant="outlined" label={role.scope.toLowerCase()} />
            {role.builtIn && (
              <Tooltip title="Built in. Its permissions can't be edited, so behaviour stays predictable across projects.">
                <Chip size="small" variant="outlined" label="built in" />
              </Tooltip>
            )}
          </Stack>

          <Typography variant="body2" sx={{ color: 'text.secondary', mb: 0.75 }}>
            {role.description}
          </Typography>

          {/*
            Coverage is DERIVED from the catalogue, not read from a sentinel. The previous pass
            looked for a '*' permission; no such permission exists in the model, and a sentinel
            would keep reading as full coverage after a new permission was added that the role
            does not hold. Now a gap renders as a gap (lib/permissions.ts).
          */}
          {holdsEveryPermission(role.permissions) ? (
            <Chip
              size="small"
              label={`every permission (${allPermissions.length})`}
              data-testid={`${testId}.AllPermissions`}
              sx={{
                bgcolor: 'color-mix(in srgb, var(--color-failed) 12%, transparent)',
                color: 'var(--color-failed)',
                border: '1px solid color-mix(in srgb, var(--color-failed) 40%, transparent)',
              }}
            />
          ) : (
            <Stack sx={{ gap: 0.75 }} data-testid={`${testId}.Permissions`}>
              {groupByArea(role.permissions).map((group) => (
                <Stack key={group.area} direction="row" sx={{ gap: 0.75, alignItems: 'baseline' }}>
                  <Typography
                    variant="caption"
                    sx={{ color: 'text.secondary', minWidth: 96, flexShrink: 0 }}
                  >
                    {group.label}
                  </Typography>
                  <Stack direction="row" sx={{ gap: 0.5, flexWrap: 'wrap' }}>
                    {group.permissions.map((permission) => (
                      <Identifier key={permission} variant="muted">
                        {permission}
                      </Identifier>
                    ))}
                  </Stack>
                </Stack>
              ))}
            </Stack>
          )}
        </Box>
      ))}
    </Stack>
  );
}
