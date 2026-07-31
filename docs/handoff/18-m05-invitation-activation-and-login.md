# §18. Backend M0.5 — invitation, activation and local login (2026-07-30)

Run through the **`be-java`** skill (agent definition + `pattern-writer` + `database-code` +
`test-driven-development` + `code-review`), Mode B — extend, plus `data:data-migrations` for V6–V8.
Closes §17's *"the one thing that blocks a usable deployment"*. Plan:
**`docs/plans/2026-07-30-tcms-invitation-activation-login.md`**.

**No plugin skill covers local BCrypt+JWT issuance** — the two auth skills on disk (`auth0`,
`cognito`) are provider-side OIDC recipes. Said out loud per `CLAUDE.md`: general Spring Security
rules were used and nothing was invented in their place.

### The headline: the deployment now has a login, and running it found what the suite could not

`provision-company` left an `INVITED` administrator with no table, token or path behind that state.
That is closed. **`docker compose up` → provision → accept → sign in → 48 permissions**, verified on a
fresh volume against a **non-superuser owner**.

Three defects were found by *running* it, after 171 green tests:

| # | Defect | Why the suite was blind to it |
|---|---|---|
| 1 (HIGH) | **`FORCE ROW LEVEL SECURITY` covers the table owner, so `V7`'s `SECURITY DEFINER` company lookup returned zero rows.** Every token came out with **no `co` claim**; the first administrator of a new instance would sign in holding **0 permissions**, with no error anywhere. | **`IntegrationTestBase` migrates as the container's SUPERUSER**, and a superuser bypasses RLS unconditionally. Its own comment has warned since M0 that the test owner is weaker than the deployment's. This is the first time that gap produced a defect — and it will not be the last. |
| 2 (HIGH) | **`provision-company` could not start.** The CLI runs `WebApplicationType.NONE`, where `HttpSecurity` does not exist, so `SecurityConfiguration`'s `filterChain` bean could not be satisfied and the whole context failed. | Every test boots a *web* context. `CliContextStartsTest` covers it now, including that no filter chain is built there. |
| 3 (LOW) | **`@ConditionalOnProperty("spring.mail.host")` matches an empty value**, so `SPRING_MAIL_HOST: ${SMTP_HOST:-}` selected the SMTP mailer with nowhere to send — a connection-refused stack trace on every invitation instead of the "no relay configured" warning. | Nothing tested the unconfigured path *through compose's* spelling of "unset". |

**`V8` fixes defect 1 by removing the privilege escalation rather than making it work**: the function
now runs as the **caller** with RLS fully in force, walking the ACTIVE companies and asking the
ordinary policed question for each. It restores `app.current_company` afterwards — a function that
silently repoints its caller's tenancy discriminator is the exact shape of a cross-company read that
looks ordinary in every log afterwards.

### The two decisions that shaped everything else

| Decision | Detail |
|---|---|
| **`membership_invitation` is instance-level** (ERD §0.1, decided in `V6`) | The accept path runs **before authentication**, so no company context can exist — and `CompanyContext` is never populated from request input. Under RLS the row would be permanently unreachable, the same shape as V1's `role_assignment.company_id IS NULL` defect. **The cost is stated, not glossed:** "this user was invited to this company" sits outside the boundary. Mitigated in the DDL — no email, no name, a SHA-256 of the token, rows consumed then purgeable. The rejected alternative (encode the company id in the token so accept can open a context) keeps the exception list closed *and* makes request input select the tenancy. |
| **The token carries `sub`/`co`/`pv`, not grants** | §6b's `sys`/`cmp`/`prj` claims are **reserved and documented**, not forgotten. They are an optimization for a hot path nobody has measured, and shipping them creates a second authorization-resolution path beside `PermissionEvaluator` — §6b's own stated bug class. **`pv` ships now** because it is the revocation channel whatever the token carries, and retrofitting it means invalidating every token in flight. **TTL is 8h/30d rather than §6b's ~10 minutes, and that reverses when the grant claims land** — recorded in `JwtProperties`. |

### What shipped

| Path | Purpose |
|---|---|
| `V6__membership_invitation_and_permission_version.sql` | The invitation table (no RLS, by decision) + `app_user.permission_version`. |
| `V7` → `V8__companies_for_user_without_definer_rights.sql` | The login-time company lookup. V7's `SECURITY DEFINER` approach and its failure are kept in the file as the record of why V8 looks like it does. |
| `identity/{MembershipInvitation,MembershipInvitationRepository,InvitationService,InvitationTokens,PasswordPolicy,InvitationProperties}.java` | Issue, preview, redeem. 32 CSPRNG bytes, stored as a hex SHA-256 — a database read yields nothing redeemable. |
| `identity/{InvitationMailer,SmtpInvitationMailer,MailConfiguration}.java` | Real SMTP, **sent after commit**, failures logged and swallowed. |
| `identity/IdentityConfiguration.java` | `DelegatingPasswordEncoder` → BCrypt cost 12, so the algorithm is recorded *in* each hash and can be raised later. |
| `auth/` — **a new module** (`{tenancy, identity}`, a leaf) | `SecurityConfiguration` · `JwtConfiguration` · `SessionTokens` · `AuthenticationService` · `PermissionVersionFilter` · `CompanyContextFilter` · `CurrentUserService` · `AuthController` · `MeController` · `InvitationController`. |
| `openapi.yaml` + `frontend/src/api/schema.d.ts` | Two invitation endpoints, `InvitationPreview`, and `required` on `CurrentUser` **including its inline team objects** — lesson #1/#3 recurred here. |
| `compose.yaml` · `.env.example` | `TCMS_JWT_SECRET` (required, no default), `TCMS_BASE_URL`, optional SMTP. |

**`CompanyContextFilter` is the production entry point the tenancy layer never had.** Every company
opened before this slice was opened by a test or the CLI. It is try-with-resources: servlet threads
are pooled, and a context left open is inherited by the next request.

### Decisions worth not re-litigating

- **Every pre-auth failure is one failure, and costs the same time.** Unknown address, wrong password,
  not-yet-activated and deactivated all answer 401 with an empty body — and a miss verifies against a
  **dummy BCrypt hash**, because otherwise "no such account" answers in microseconds and "wrong
  password" in ~250 ms, which reinstates over the network exactly the oracle the shared body removes.
  Invitation redemption collapses five causes into **one** rejection constant, so splitting them apart
  later requires deliberately adding a constant.
- **A rejected password costs the invitation nothing** — the token is consumed only after the password
  is accepted, so a typo cannot burn the only way in.
- **Passwords are 12–72 bytes.** The 72 is BCrypt's: beyond it, input is silently ignored, so two
  passphrases sharing a 72-byte prefix both open the account. Counted in **bytes**, or exactly the
  users whose script is not Latin get past it.
- **Accepting returns 204, not a session.** One token-issuing path, and signing in afterwards proves
  the password round-tripped.
- **Someone joining a second company keeps their password** — the accept path *refuses* one rather
  than ignoring it.
- **A re-run does not replace an outstanding invitation** (it would invalidate a link already in
  someone's inbox); a lapsed one is replaced, and that counts as a change rather than a no-op.
- **`/auth/logout` is honestly documented as a client-side discard.** A stateless token has nothing to
  invalidate; `pv` is the real lever, and a `jti` denylist arrives with refresh tokens.

### Gate evidence

`./gradlew build` clean — **171 tests** (124 pre-existing + 47 new). `npm run build` + `npm run lint`
clean after `gen:api`. **Run for real** on a fresh volume: `co` claim present, `pv` = 1, 48 permissions
including `company:settings` and `case:create`.

**Mutation-verified**, 16 mutations across the slice, one narrow edit each. **Four survived, and each
survivor was a real gap** rather than noise — all four are now killed by exactly one test:

| Survivor | The gap it exposed |
|---|---|
| `isRedeemable` ignores `consumed_at` | Single-use rested on the membership having left INVITED. Anything that put it back would make a spent token work again. |
| the must-be-INVITED gate in `accept` | Without it, a **REMOVED** membership walks straight back to ACTIVE — offboarding undone by a link still in someone's mailbox. |
| the mailer rethrows instead of swallowing | "A mail outage cannot take a provisioned company down with it" was an assertion in a comment. The test now stops the relay outright. |
| the `pv` comparison, and the issuer validator | The only pv test also deactivated the account, so the *status* check one line away caught it. And a forged signature and a foreign issuer look nothing alike — only the first was covered. |

The blunt mutation (storing the token in plaintext) took down twelve tests, which proves only that the
suite runs. Recorded as the blunt one it is.

### Four things worth not re-learning

1. **`FORCE ROW LEVEL SECURITY` covers the owner, so `SECURITY DEFINER` is not an RLS bypass here.**
   The reflex reach for a definer function to answer a pre-auth question is wrong in this schema, and
   it fails *silently and only in production*, because the test harness's owner is a superuser.
2. **The suite cannot see anything that depends on the owner not being a superuser.** This is now a
   known, named gap rather than a footnote in `IntegrationTestBase` — see "Next" below.
3. **Every test boots a web context, so the CLI's context is untested by construction.** The same jar
   being two programs means two startup arrangements, and only one had coverage.
4. **`git checkout --` on an uncommitted file is a delete, not a revert — again.** §16 recorded this
   and it recurred mid-mutation-run, silently wiping the session's uncommitted Phase 3 edits. The
   working rule is now unconditional: **commit the slice before mutating it.**

### Deliberately not done

- **No refresh tokens, no `jti` denylist.** Both belong with the grant claims, where the TTL comes down.
- **No login throttling or lockout.** The constant-time miss closes the *timing* oracle; it does nothing
  about volume, and rate limiting is a separate concern with a separate home.
- **No password reset.** The invitation machinery is most of it — a token table, a mailer, a policy —
  but the flows differ (an existing password must be replaced, not set) and merging them now would
  make the accept path do two jobs.
- **No admin-initiated invitations** (`POST /companies/{id}/invitations`). `InvitationService.issueIfPending`
  is already the whole behaviour; what is missing is the endpoint and its permission check.
- **No `pv` cache.** §6b specifies Caffeine; it is a primary-key read today. Correctness first, exactly
  as the evaluator's own cache was deferred in §13 — a wrongly-keyed cache here accepts revoked sessions.
- **`revokeSessions()` has only two call sites** (activate, deactivate). Grant, team and company-membership
  changes must bump it too; none of those mutation paths exist yet, and that is recorded in the method
  rather than assumed covered.
- **The frontend has no invitation-accept route.** `tcms.invitation.accept-path` points at
  `/accept-invitation`, which the SPA does not serve yet — the link works against the API, not the UI.

### Next on the backend, in order

1. ~~**A non-superuser owner in `IntegrationTestBase`.**~~ → **DONE, §19.**
2. **The frontend accept-invitation route + real login wiring** — the SPA still runs entirely on MSW,
   so the two halves have never met.
3. The evaluator's cache, the `pv` cache, the `OWN` qualifier, an RLS-disabled lane for §13's surviving
   mutation.
4. Admin-initiated invitations and password reset, in that order.
