# §230 — social login (GitHub · GitLab · Google) and account linking

**Date:** 2026-08-16 · **Plan:** `docs/plans/2026-08-16-social-login-and-account-linking.md`
(executed task-by-task; every decision below is that plan's §0, restated here with what actually
happened). **Migration:** `V58`. **Stacks:** contract + backend + frontend; **no `packages/`
surface by decision** (session auth is out of the CLI/MCP lane; tokens stay `TCMS_TOKEN`).

## What shipped

A person can link GitHub, GitLab and/or Google to their existing account from
`/account/security` and thereafter sign in with any linked identity. Three *instance-level*
providers configured by the operator via environment variables — no `identity_provider` row,
no change to `IdentityProviderKind` — riding the §223/§6r OIDC machinery for GitLab and Google
and a purpose-built adapter for GitHub (plain OAuth2: no discovery, no `id_token`, no nonce;
identity is `GET /user` with the exchanged token).

## The non-collision table (decision 1 — the whole argument)

| Path | Creates an account? | Changed by this slice? |
|---|---|---|
| `provision-company` CLI | yes (the first admin) | untouched |
| Invitation + password | yes | backend untouched; the SPA now composes accept + login (below) |
| Company-IdP SSO with JIT on | yes | untouched |
| **Social login** | **never** — resolves an already-linked identity only | new |

**Social sign-in never creates an account.** A `(provider, subject)` nobody linked gets the same
opaque refusal as every other cause. **No email auto-linking** (decision 4): the §6r auto-link
exists because a company admin vouches for their own issuer; nobody vouches for all of GitHub.
The consequence is accepted and deliberate: a user who never linked cannot sign in with Google
"because the email matches" — that is the point. The key is `(provider, subject)`, never email
(decision 5); GitHub's subject is the immutable numeric `id`, never `login`, which is a rename
away from binding somebody else.

## The decisions that will be asked about later

- **Two instance-level tables, ERD §0.1 exceptions #14/#15** (`V58`): `user_social_identity`
  (a person's GitHub-ness spans their companies; the login path reads it pre-context) and
  `social_login_attempt` (`sso_login_attempt`'s twin, pre-session by construction).
  `SchemaConventionTest` failed first, then passed — the deliberate-act protocol held.
  **`UserIdentity.linkedAt` exited `READERLESS_BY_DECISION`**: its entry said "exits when a
  screen lists a person's external identities", and the security page is that screen one table
  over — the derived `OrderByLinkedAt` put the property in the read corpus.
- **Intent travels in the attempt row, not the URL** (decision 11): one callback
  (`GET /auth/social/callback`) serves LOGIN and LINK, told apart by the row the hashed `state`
  resolves. A `LINK` row alone carries a `user_id`, enforced by `V58`'s shape check.
- **Link/unlink live under `/me`, not `/auth`** (decision 9): `/api/v1/auth/**` is permitAll and
  linking requires the session. The begin-link leg answers `{ url }` rather than a 302 — a
  top-level navigation cannot carry the Authorization header.
- **Unlink refuses to strand an account** (decision 10): 409 when the account has no password
  hash and this is its only social identity. It deliberately does **not** count company-IdP
  `user_identity` rows (RLS-protected per company; a cross-company count from a pre-context
  service would be structurally incomplete) — failing closed is the chosen direction, and the
  409's detail names the remedy. The guard is live code: company-IdP JIT (§6r) creates
  passwordless accounts, and one of those may link.
- **No new permission keys** (decision 12): managing your own sign-in identities is
  self-service, gated by authentication. The catalogue is still 55; both matrix copies
  untouched.
- **Register → signed in → security page** (decision 2, the same-day revision): when acceptance
  *created* the credential, `AcceptInvitationPage` follows the 204 with the ordinary
  `POST /auth/login` — through the same `login` seam `LoginPage` uses — and lands on
  `/account/security?welcome=1` (providers configured) or `/` (none). The acceptance endpoint
  still answers **204 with no session**; composing in the SPA preserves §223's stated rationale,
  since the login round-trip is what proves the password stored. A failed auto-login falls back
  to `/login` carrying the destination in the `from` seam LoginPage already honours — **no new
  seam was added**; the plan predicted one might be needed and it was not. **Social is an
  optional extra** (user, 2026-08-16): the welcome intro is an offer, Skip is one click, nothing
  is gated on having linked.
- **Appendix A cut**: invitation-time social acceptance (accept the invitation *with* GitHub)
  was designed and deferred — it was the only `InvitationService`/`AppUser` modification and the
  only cross-aggregate transaction in the first draft. The plan's Appendix A holds the design
  and the comparison table; reviving means a follow-up migration adding `invitation_id`, not a
  rewrite.

## Caveats a maintainer needs

- **GitHub reports failure as HTTP 200 with `{"error":...}`** — the token's presence is the
  success signal, not the status. `GithubUserClientTest.errorBodyIsRefusal` pins it. The
  `/user/emails` read is best-effort (decision 13): a 403 degrades to no email, never to
  failure; `email_at_link` is provenance and never a key.
- **The provider path segment is a `String` parsed in the controller, not a converted enum** —
  a conversion failure would 400 a browser mid-redirect that cannot read it. The browser legs
  302 in every case (`MYSPACE` included); the `/me` XHR legs answer 404 for unknown and
  unconfigured alike. All three public routes joined `EndpointAccessTest`'s
  `DELIBERATELY_PUBLIC` and `ContractConventionTest`'s `NO_CLIENT_ERROR_POSSIBLE`, each with the
  §6r reasoning at the entry.
- **Operator wiring**: `tcms.auth.social.*` in `application.yml` with `${VAR:}` empty defaults —
  correct for an *optional* feature where §108's no-colon rule is about required secrets; the
  comment at the block says so. Compose (`compose.yaml` + `deploy/compose.prod.yaml`) passes
  seven optional vars with the `:-` spelling that is right in compose and wrong in Spring. The
  OAuth app's callback is `${TCMS_API_BASE_URL}/api/v1/auth/social/callback`
  (`OidcProperties.socialRedirectUri()`, `redirectUri()`'s sibling).
- **Test-fixture subjects collide across classes**: the suite shares one Postgres container, so
  two test classes linking the same `(provider, subject)` fight over `uq_user_social_identity_subject`.
  Found when the full backend run reddened `SocialIdentityServiceTest` beside a green solo run;
  subjects are now per-class-prefixed (`soc-gh-*`, `sis-*`, `slt-*`). A new social test class
  must mint its own prefix.
- **The SPA has no success toast** — `NotifyValue` is errors and prompts only — so the
  `#linked=` acknowledgement renders as an inline success Alert on the security page, routed
  through router state by `SsoCallbackPage` (the fragment marker never touches the query
  string). `?welcome=1` does **not** survive the OAuth round-trip; returning from a completed
  link shows the acknowledgement without the intro, pinned as correct.

## Gates (all on shanks)

Backend `./gradlew build` **BUILD SUCCESSFUL, 2,949 tests** — including the fail-first runs the
plan scripted (`SchemaConventionTest` before the exception entries, every new test class before
its implementation). Frontend **1,491 tests / 111 files**, build + lint 0 warnings. Packages
**329 (78/128/123)** — no social surface, green throughout as the contract-freshness check.

## Built and unverified (§65 — rows written, nothing run)

Frontend queue `§230 SL-1…SL-7` (lanes named per row; `SL-2` needs a real GitHub OAuth app and
says what to do when there is none) and backend queue `§230 SB-1…SB-2` (replay writes nothing;
the linking race leaves exactly one row). Until a pass runs them, the browser-level flow is
**built and unverified** — the suites above drive the same seams over MockMvc/RTL, not a real
provider round-trip.
