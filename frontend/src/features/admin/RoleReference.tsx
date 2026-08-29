import type { Role, RoleScope } from '../../api/types';
import { Identifier } from '../../components/Identifier';
import { Badge } from '../../components/kit';
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
 *
 * The render draws this as a four-column tick grid — Read / Write / Run / Approve — under the
 * caption *"this is a summary and the spec is authoritative"*. It is **not** ported that way, and
 * the render's own note is the argument: a convenience copy that drifts is how a permission matrix
 * starts lying. These rows are the catalogue the server served, so there is nothing to drift.
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
    <div className="flex flex-col">
      {shown.map((role) => (
        <div
          key={role.id}
          className="border-b border-white/8 py-3.5 last:border-b-0"
          data-testid={`${testId}.Item`}
        >
          <div className="mb-1 flex flex-wrap items-center gap-2">
            <h3 className="text-[13.5px] font-semibold text-ink">{role.name}</h3>
            <Badge tone="plain">{role.scope.toLowerCase()}</Badge>
            {role.builtIn && (
              <span title="Built in. Its permissions can't be edited, so behaviour stays predictable across projects.">
                <Badge tone="plain">built in</Badge>
              </span>
            )}
          </div>

          <p className="mb-2 max-w-[74ch] text-[12.5px] leading-[1.55] text-ink-3">{role.description}</p>

          {/*
            Coverage is DERIVED from the catalogue, not read from a sentinel. The previous pass
            looked for a '*' permission; no such permission exists in the model, and a sentinel
            would keep reading as full coverage after a new permission was added that the role
            does not hold. Now a gap renders as a gap (lib/permissions.ts).
          */}
          {holdsEveryPermission(role.permissions) ? (
            <Badge tone="failed" data-testid={`${testId}.AllPermissions`}>
              every permission ({allPermissions.length})
            </Badge>
          ) : (
            <div className="flex flex-col gap-1.5" data-testid={`${testId}.Permissions`}>
              {groupByArea(role.permissions).map((group) => (
                <div key={group.area} className="flex items-baseline gap-2">
                  <span className="w-[96px] shrink-0 text-[11.5px] text-ink-4">{group.label}</span>
                  <span className="flex flex-wrap gap-1.5">
                    {group.permissions.map((permission) => (
                      <Identifier key={permission} variant="muted">
                        {permission}
                      </Identifier>
                    ))}
                  </span>
                </div>
              ))}
            </div>
          )}
        </div>
      ))}
    </div>
  );
}
