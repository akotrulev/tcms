/** Sign-in, sign-out, invitation redemption, and the two questions about the caller. */
import { HttpResponse, delay, http } from 'msw';
import { db } from '../db';
import { companyScopePermissions, currentUser } from '../fixtures/core';
import { API, LATENCY, permissionsOn, problem, requireAuth } from './shared';

export const authHandlers = [
  http.post(`${API}/auth/login`, async ({ request }) => {
    await delay(LATENCY);
    const body = (await request.json()) as { email?: string; password?: string };

    if (!body.email || !body.password) {
      return problem(400, 'Missing credentials', 'Enter your email and password.');
    }
    // Any password of 4+ characters signs you in as the seeded lead — but a short one
    // still fails, so the form's error path is reachable in the prototype.
    if (body.password.length < 4) {
      return problem(401, 'Sign-in failed', "That email and password don't match an account.");
    }

    return HttpResponse.json({
      token: `mock.${btoa(body.email)}.jwt`,
      expiresAt: new Date(Date.now() + 8 * 3600_000).toISOString(),
      user: currentUser,
    });
  }),

  http.post(`${API}/auth/logout`, () => new HttpResponse(null, { status: 204 })),

  /*
    Invitation redemption. Both handlers answer a **bodiless 404** for every rejection —
    unknown token, already redeemed, and (in the real service) expired, membership removed or
    account deactivated. That is the contract, and it is a security property rather than
    laziness: told apart, the responses would let anyone with a token-shaped string learn which
    addresses exist on this instance. A mock that helpfully explained "already used" would make
    the UI's one non-committal panel look like a bug and invite someone to "improve" it.
  */
  http.get(`${API}/auth/invitations/:token`, async ({ params }) => {
    await delay(LATENCY);
    const invitation = db.invitations.get(String(params.token));
    if (!invitation || invitation.consumed) return new HttpResponse(null, { status: 404 });

    return HttpResponse.json({
      email: invitation.email,
      displayName: invitation.displayName,
      passwordRequired: invitation.passwordRequired,
    });
  }),

  http.post(`${API}/auth/invitations/:token/accept`, async ({ params, request }) => {
    await delay(LATENCY);
    const invitation = db.invitations.get(String(params.token));
    if (!invitation || invitation.consumed) return new HttpResponse(null, { status: 404 });

    const body = (await request.json().catch(() => ({}))) as { password?: string };
    const password = body?.password;

    // 422 — and unlike the 404s, this one DOES carry a reason: it describes the password just
    // submitted, not the account or the instance, so saying "too short" leaks nothing.
    if (invitation.passwordRequired) {
      if (!password) {
        return problem(422, 'The password was not accepted', 'Choose a password to activate your account.');
      }
      // Counted in bytes, matching BCrypt's real limit and the server's own check.
      const bytes = new TextEncoder().encode(password).length;
      if (bytes < 12) {
        return problem(422, 'The password was not accepted', 'Use at least 12 characters.');
      }
      if (bytes > 72) {
        return problem(422, 'The password was not accepted', 'That is too long — 72 bytes is the maximum.');
      }
    } else if (password) {
      // Rejected rather than ignored: holding an invitation to a second company must not confer
      // the ability to overwrite an existing credential.
      return problem(
        422,
        'The password was not accepted',
        'This account already has a password. Sign in with the one you use today.',
      );
    }

    // Consumed only after the password was accepted, so a typo cannot burn the only way in.
    invitation.consumed = true;
    return new HttpResponse(null, { status: 204 });
  }),

  http.get(`${API}/me`, ({ request }) => requireAuth(request) ?? HttpResponse.json(currentUser)),

  /*
    One project, one answer — `{ projectId, permissions }`, matching the contract and the real
    MeController. This handler used to return an ARRAY of per-project entries, which nothing
    consumed and no endpoint ever produced; it would have failed the first consumer written
    against it the moment live mode existed. Drift in an unused handler is still drift.

    It also used to answer **Test Lead for every project**, which was harmless while nothing
    called it and became actively misleading the moment the disable-don't-hide gate did: a mock
    that grants everything can only ever render the enabled branch, so the whole feature would
    have been unprovable in the mode the app is developed in. The answer now comes from the
    project's own `myRole`, and the fixtures already carry a project where the caller is a
    **Viewer** — MOB, seeded that way precisely because read-only is the default on joining.

    No project id means the company-wide question, which the real endpoint answers with the
    SYSTEM + COMPANY terms only — deliberately NOT the union of the caller's project grants. The
    answer is `companyScopePermissions`, a named fixture rather than a literal here, because it is
    now consulted twice: by this handler and by `POST /projects`, whose gate must agree with the
    button this answer disables. Its own comment says what to add to drive the enabled branch.
  */
  http.get(`${API}/me/permissions`, ({ request }) => {
    const denied = requireAuth(request);
    if (denied) return denied;

    const projectId = new URL(request.url).searchParams.get('projectId');
    if (!projectId) {
      // Company-scope grants carry no restrictive qualifier in the seeded catalogue — every
      // qualified grant in the roles spec is on a PROJECT-scope role — so ALL is the derived
      // answer here rather than a shortcut. See `permissionsOn` for the project-scope table.
      return HttpResponse.json({
        projectId: null,
        permissions: companyScopePermissions.map((key) => ({ key, qualifier: 'ALL' })),
      });
    }

    // An unknown project answers with the empty set rather than a 404, matching the real
    // endpoint: an unreachable project and a non-existent one must be indistinguishable (§6t).
    return HttpResponse.json({ projectId, permissions: permissionsOn(projectId) });
  }),
];
