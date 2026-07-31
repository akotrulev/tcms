/** The company's admin surface — its people, its org chart, the roles, and who holds them. */
import { HttpResponse, delay, http } from 'msw';
import { db, newId, users } from '../db';
import { companyScopePermissions, currentUser, roles, teams } from '../fixtures/core';
import { API, LATENCY, notFound, permissionsOn, problem, requireAuth } from './shared';

/**
 * The scope-dependent gate, mirrored from `RoleGrantService` — `project:members` on the named
 * project, `company:members` when there is none.
 *
 * From the same two permission sources `GET /me/permissions` answers with, so the button
 * `PermissionGate` disables and the write the mock refuses cannot disagree. That divergence class
 * is the one this project has paid for twice (`If-Match` in the body, the unread `Idempotency-Key`),
 * and it is worth one shared function to avoid a third.
 */
function mayStaff(projectId: string | null | undefined): boolean {
  return projectId
    ? permissionsOn(projectId).includes('project:members')
    : companyScopePermissions.includes('company:members');
}

const forbidden = () =>
  problem(403, 'Not allowed', 'You do not have permission to manage access here.');

export const adminHandlers = [
  http.get(`${API}/users`, async () => {
    await delay(LATENCY);
    return HttpResponse.json(users);
  }),

  http.get(`${API}/teams`, async () => {
    await delay(LATENCY);
    return HttpResponse.json(teams);
  }),

  http.get(`${API}/roles`, async () => {
    await delay(LATENCY);
    return HttpResponse.json(roles);
  }),

  http.get(`${API}/role-assignments`, async ({ request }) => {
    await delay(LATENCY);
    const projectId = new URL(request.url).searchParams.get('projectId');
    const rows = projectId
      ? db.roleAssignments.filter((a) => a.projectId === projectId)
      : db.roleAssignments;
    return HttpResponse.json(rows);
  }),

  /*
    Grant. Five properties are mirrored from the real endpoint, because each is something a
    screen can only be developed against if the mock behaves the same way:

      1. **The gate depends on the scope**, per `mayStaff` above.
      2. **A project that does not exist is a 404**, before the gate is consulted at all — the
         same order `RoleGrantService` uses, so an unreachable project and one the caller merely
         holds nothing on stay indistinguishable (§6t).
      3. **A role whose scope disagrees is a 400 on `roleId`**, and a SYSTEM-scope role is refused
         outright: those grants live in `instance_role_assignment` and the operator CLI is the only
         path to one.
      4. **An identical re-grant is a 200 with the existing row**, not a duplicate and not a 409.
      5. **The grant is made observable**, by the `myRole` line below. A mock that wrote the row
         without it would prove the screen and not the feature.
  */
  http.post(`${API}/role-assignments`, async ({ request }) => {
    const denied = requireAuth(request);
    if (denied) return denied;
    await delay(LATENCY);

    const body = (await request.json()) as {
      roleId?: string;
      userId?: string | null;
      teamId?: string | null;
      projectId?: string | null;
    };
    const projectId = body.projectId ?? null;

    if (projectId && !db.projects.some((p) => p.id === projectId)) {
      return notFound('That project');
    }
    if (!mayStaff(projectId)) return forbidden();

    const errors: Record<string, string[]> = {};
    const hasUser = !!body.userId;
    const hasTeam = !!body.teamId;
    if (hasUser === hasTeam) {
      errors.userId = [
        hasUser
          ? 'Grant the role to a person or to a team, not to both.'
          : 'Choose a person or a team to grant the role to.',
      ];
    }

    const role = roles.find((r) => r.id === body.roleId);
    if (!role) {
      errors.roleId = ['Choose a role.'];
    } else if (role.scope === 'SYSTEM') {
      errors.roleId = ['Instance-wide roles are granted by the operator, not from here.'];
    } else if (role.scope === 'COMPANY' && projectId) {
      errors.roleId = [
        'This role applies to the whole company, so it cannot be granted on a single project.',
      ];
    } else if (role.scope === 'PROJECT' && !projectId) {
      errors.roleId = [
        'This role applies to one project, so choose the project it should apply to.',
      ];
    }

    if (hasUser && !hasTeam && !users.some((u) => u.id === body.userId)) {
      errors.userId = ['That person is not a member of this company.'];
    }
    if (hasTeam && !hasUser && !teams.some((t) => t.id === body.teamId)) {
      errors.teamId = ['That team does not exist in this company.'];
    }
    if (Object.keys(errors).length > 0 || !role) {
      return problem(400, 'Validation failed', 'Check the highlighted fields.', errors);
    }

    const existing = db.roleAssignments.find(
      (a) =>
        a.role.id === role.id &&
        (a.projectId ?? null) === projectId &&
        (hasUser ? a.user?.id === body.userId && !a.team : a.team?.id === body.teamId),
    );
    if (existing) return HttpResponse.json(existing);

    const person = users.find((u) => u.id === body.userId);
    const team = teams.find((t) => t.id === body.teamId);
    const project = db.projects.find((p) => p.id === projectId);
    const created: (typeof db.roleAssignments)[number] = {
      id: newId(),
      role,
      // Null for a team grant. The contract declares `user` required and non-nullable and the
      // server cannot honour that — ck_role_assignment_subject makes it user XOR team — so the
      // mock answers the shape the server answers rather than the one the schema declares. See
      // handoff §39; this cast goes when the contract is corrected.
      user: (person
        ? { id: person.id, displayName: person.displayName, email: person.email, avatarUrl: null }
        : null) as (typeof db.roleAssignments)[number]['user'],
      team: team ? { id: team.id, name: team.name } : null,
      projectId,
      projectName: project?.name ?? null,
      grantedAt: new Date().toISOString(),
    };
    db.roleAssignments.push(created);

    // `myRole` is what the picker and the project rail read, so this is the one line that makes a
    // grant to the signed-in persona visible anywhere outside the admin table.
    if (project && body.userId === currentUser.id) {
      project.myRole = role.name;
    }

    return HttpResponse.json(created, {
      status: 201,
      headers: { Location: `${API}/role-assignments/${created.id}` },
    });
  }),

  /*
    Revoke. 204 whether the grant was there or not, and one refusal: a caller may not revoke the
    grant carrying their own ability to revoke.

    The lockout check here is an APPROXIMATION and says so. The server computes it by doing the
    delete and asking the permission evaluator again, which is correct for team grants and for a
    caller holding two grants; there is no evaluator here, so this asks the cheap version — is this
    row the current user's own grant of a role that confers the gating permission. It refuses the
    case the screen needs to render and would wrongly refuse a redundant second grant. Stated
    rather than left to be discovered, because a mock that is subtly stricter than the server
    teaches the wrong shape.
  */
  http.delete(`${API}/role-assignments/:assignmentId`, async ({ params, request }) => {
    const denied = requireAuth(request);
    if (denied) return denied;
    await delay(LATENCY);

    const index = db.roleAssignments.findIndex((a) => a.id === params.assignmentId);
    if (index < 0) return new HttpResponse(null, { status: 204 });

    const grant = db.roleAssignments[index];
    if (!mayStaff(grant.projectId)) return forbidden();

    const gate = grant.projectId ? 'project:members' : 'company:members';
    if (grant.user?.id === currentUser.id && grant.role.permissions.includes(gate)) {
      return problem(400, 'Validation failed', 'Check the highlighted fields.', {
        assignmentId: [
          'This grant is what lets you manage access here, so removing it would lock you out. ' +
            'Ask another administrator to remove it.',
        ],
      });
    }

    db.roleAssignments.splice(index, 1);
    const project = db.projects.find((p) => p.id === grant.projectId);
    if (project && grant.user?.id === currentUser.id) {
      project.myRole = null;
    }
    return new HttpResponse(null, { status: 204 });
  }),
];
