/** The company's admin surface — its people, its org chart, the roles, and who holds them. */
import { HttpResponse, delay, http } from 'msw';
import { db, newId, users } from '../db';
import { companyScopePermissions, currentUser, roles, teams } from '../fixtures/core';
import { API, LATENCY, holdsOn, notFound, problem, requireAuth } from './shared';

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
    ? holdsOn(projectId, 'project:members')
    : companyScopePermissions.includes('company:members');
}

/** `InvitationProperties.validity`'s default, so the dialog renders a realistic expiry. */
const inSevenDays = () => new Date(Date.now() + 7 * 24 * 60 * 60 * 1000).toISOString();

const forbidden = () =>
  problem(403, 'Not allowed', 'You do not have permission to manage access here.');

export const adminHandlers = [
  http.get(`${API}/users`, async () => {
    await delay(LATENCY);
    return HttpResponse.json(users);
  }),

  /*
    Invite. Four properties are mirrored from `MembershipInviteService`, because each is something
    the dialog can only be developed against if the mock behaves the same way — and the fourth is
    the one a mock most easily gets wrong in the flattering direction:

      1. **The gate is `company:members` at company scope**, from the same source
         `GET /me/permissions` answers with, so the button `PermissionGate` disables and the write
         this refuses cannot disagree.
      2. **Validation is the server's**, field for field, so a message that lands on an input here
         is the message that lands on it live.
      3. **The person genuinely joins the directory as INVITED.** A handler that answered 201 and
         wrote nothing would prove the dialog and not the feature — the list would stay as it was
         and nobody would notice until the live pass.
      4. **A second invitation of the same address is a 200 that mints nothing**, matched
         case-insensitively (`citext`). This is the property the dialog *renders differently*, so a
         mock that always answered 201 would make the "nothing was sent" branch unreachable in
         development and untested anywhere. Same class as §54's accepted-and-ignored
         `Idempotency-Key`.

    Not modelled: reusing an account that already exists on the instance. There is only one company
    in the mock, so the branch has nothing to be distinguishable *from* — its whole point is that a
    Company Admin cannot tell it apart, and the backend suite drives both sides of it.
  */
  http.post(`${API}/users`, async ({ request }) => {
    const denied = requireAuth(request);
    if (denied) return denied;
    await delay(LATENCY);

    if (!companyScopePermissions.includes('company:members')) {
      return problem(403, 'Not allowed', 'You do not have permission to manage access here.');
    }

    const body = (await request.json()) as { email?: string; displayName?: string };
    const email = (body.email ?? '').trim();
    const displayName = (body.displayName ?? '').trim();

    const errors: Record<string, string[]> = {};
    if (!email) errors.email = ['Enter the email address to invite.'];
    else if (email.length > 320) errors.email = ['That email address is too long.'];
    else if (!/^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(email)) {
      errors.email = ['Enter a valid email address.'];
    }
    if (!displayName) errors.displayName = ['Enter the name to show for this person.'];
    else if (displayName.length > 200) {
      errors.displayName = ['Keep the name to 200 characters or fewer.'];
    }
    if (Object.keys(errors).length > 0) {
      return problem(400, 'Validation failed', 'Check the highlighted fields.', errors);
    }

    const existing = users.find((u) => u.email?.toLowerCase() === email.toLowerCase());
    if (existing) {
      // Already a member, or already invited. Either way nothing is minted and nothing is sent —
      // and an outstanding link is left alone rather than replaced.
      return HttpResponse.json({
        user: existing,
        // `issued: false` is the whole point of the branch — the dialog reads it to tell "there
        // was nowhere to send it" from "there was nothing to send", and a mock that hard-coded
        // `true` here would make the wrong copy look right in development.
        issued: false,
        emailed: false,
        expiresAt: existing.status === 'INVITED' ? inSevenDays() : null,
      });
    }

    const invited: (typeof users)[number] = {
      id: newId(),
      displayName,
      email,
      avatarUrl: null,
      status: 'INVITED',
      lastLoginAt: null,
      teams: [],
    };
    users.push(invited);

    return HttpResponse.json(
      { user: invited, issued: true, emailed: true, expiresAt: inSevenDays() },
      { status: 201 },
    );
  }),

  /*
    Remove. Three properties are mirrored from `MembershipRemovalService`, and the middle one is
    the property the screen renders differently — a mock that always answered 204 would make the
    branch unreachable in development, which is how §55's three-state defect got as far as a live
    pass:

      1. **The gate is `company:members` at company scope**, from the same source the button's
         `PermissionGate` reads, so what the UI disables and what the write refuses agree.
      2. **Removing yourself is a 400 keyed on `userId`.** The row's own button is disabled for
         exactly this reason, so the refusal should be unreachable through the UI — which is
         precisely why the mock has to be able to produce it: a disable that stops working is
         invisible until something on the other side says no.
      3. **They actually leave the directory**, so the list the caller is looking at changes. A
         handler that answered 204 and wrote nothing would prove the dialog rather than the
         feature.

    Not modelled: the session revocation and the cancelled invitation. Neither is observable from
    the SPA — the removed person is not the one holding this screen — and inventing an observable
    for them here would be a mock asserting something the product does not show.
  */
  http.delete(`${API}/users/:userId`, async ({ params, request }) => {
    const denied = requireAuth(request);
    if (denied) return denied;
    await delay(LATENCY);

    if (!companyScopePermissions.includes('company:members')) return forbidden();

    const userId = String(params.userId);
    if (userId === currentUser.id) {
      return problem(400, 'Validation failed', 'Check the highlighted fields.', {
        userId: [
          'You cannot remove yourself from this company. Ask another administrator to do it.',
        ],
      });
    }

    const at = users.findIndex((u) => u.id === userId);
    // 204 whether they were there or not: a repeated request and an id that names nobody here
    // both end in the state the caller asked for.
    if (at >= 0) users.splice(at, 1);
    return new HttpResponse(null, { status: 204 });
  }),

  http.get(`${API}/teams`, async () => {
    await delay(LATENCY);
    return HttpResponse.json(teams);
  }),

  http.get(`${API}/roles`, async () => {
    await delay(LATENCY);
    return HttpResponse.json(roles);
  }),

  /*
    The list is **filtered by what the caller may staff**, not by what they can see — mirroring
    `AdminDirectoryService.listRoleAssignments`, which filters on `AdminReach.mayStaff` so that
    every row carries a Revoke the server will accept and every grant it would accept a revoke for
    is on the screen.

    It used to return everything to everybody, and that mattered once a project screen started
    reading the company-wide rows: the seeded persona is an ordinary member (`companyScopePermissions`
    below the fixtures explains why), so live mode shows them **no** company-wide grants while an
    unfiltered mock showed them all of them. A mock more generous than the server is the same defect
    class as one that is stricter — the branch the product actually renders becomes the one nobody
    develops against.

    **To drive the company-wide branch in mock mode, add `'company:members'` to
    `companyScopePermissions` in `fixtures/core.ts`** — the same documented toggle that file already
    carries for `project:create`.
  */
  http.get(`${API}/role-assignments`, async ({ request }) => {
    await delay(LATENCY);
    const projectId = new URL(request.url).searchParams.get('projectId');
    const rows = db.roleAssignments
      .filter((a) => !projectId || a.projectId === projectId)
      .filter((a) => mayStaff(a.projectId));
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
    }
    /*
      There is deliberately NO `role.scope === 'PROJECT' && !projectId` refusal, and its removal on
      2026-08-02 is the point rather than a relaxation. Roles spec §A1.1 made a PROJECT-scope role
      grantable with no project — "this role on every project in the company, unless that project
      says otherwise" — and this handler still refused it after `RoleGrantService` stopped doing so.

      A mock that is stricter than the server is the failure mode this file keeps a list of: the
      branch it forbids becomes unreachable in development, so the screen that renders inheritance
      cannot be built or seen until a live pass. The fixture below carries a company-wide grant for
      the same reason — a rule with no row is a rule nobody looks at.

      The asymmetry with COMPANY scope above is the model's, not an oversight: a COMPANY-scope role
      pinned to a project is still refused, because Company Admin is authority over the company and
      letting a project name it would let anyone holding `project:members` redefine it (§A1.1,
      exemption 2).
    */

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
      // Null for a team grant — ck_role_assignment_subject makes the subject user XOR team.
      // The contract said `user` was required and non-nullable, which the server could not
      // honour; corrected at integration (§41), so the cast lane B left here is gone.
      user: person
        ? { id: person.id, displayName: person.displayName, email: person.email, avatarUrl: null }
        : null,
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
