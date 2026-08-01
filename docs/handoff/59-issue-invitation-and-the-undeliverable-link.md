# §59. `issue-invitation` — the link that was never emailed, and the mail that stays off

**Date:** 2026-08-01 · **Commits:** `8b2be7b` (backend), `af17c35` (deploy), `762a836` (CI)

Written after the fact, in §60. The slice landed in three commits and never got a section; the index
stopped at §58 while the work had moved two slices past it. That gap is itself the finding —
see the note at the bottom.

## What §58 could not do, and only deploying revealed

The first real deployment (§58) stood the product up on a box with **no SMTP relay**, which is a
supported configuration. An invitation raised from the UI on that instance was **undeliverable by
anybody**, and every individual piece of that was correct:

| Piece | Why it is right |
|---|---|
| The token is stored only as `hex(sha256(secret))` | `InvitationServiceTest` pins it. A readable token in that table makes a database read enough to become a client's administrator. |
| The API response carries no URL | It is a secret, on a wire that gets logged. `UserInvitationView` omits it deliberately (§55). |
| The unconfigured mailer logs a warning | It has no relay. Logging the link instead would put the secret in the log. |
| `provision-company` refuses to replace an outstanding invitation | §55's rule: a re-run must not invalidate a link already in somebody's inbox. |
| `provision-company` only addresses a company's **first** admin | It provisions companies; it does not administer them. |

So the UI told the invitee "ask your operator for the activation link", and **the operator had no
mechanism to produce one**. Not a missing feature so much as an emergent one: five correct decisions
composing into a dead end that no single one of them is wrong for.

Note what the fix could not be. "Log the link when there is no relay" and "return the URL in the
response when mail is off" are both one-liners, and both convert a deliberate secrecy property into a
configuration-dependent one — the worst shape, because the property then holds on the instances
somebody tested and not on the instance that has the problem.

## What shipped

- **`InvitationService.reissue`** — the deliberate inverse of `issueIfPending`. That one leaves a
  live invitation alone because `provision-company` is re-runnable; this one **replaces** it, because
  the operator explicitly asked. The previous token dies the moment the row is replaced, which is
  what makes re-issuing safe to offer: there is never more than one redeemable token per membership,
  so a link recovered from an old mailbox cannot be used behind the operator's back.
- **`issue-invitation` operator CLI** — `IssueInvitationCommand` / `IssueInvitationService`, same
  shape as `provision-company`: the command parses and reports, the service holds the behaviour, the
  company id is resolved **before** the context opens and the context wraps the transaction (§15's
  `doBegin` ordering — a context opened inside the transaction leaves the GUC unset and every read
  returns zero rows).
- **`OperatorCommands`** — the verb list, in one place. `TcmsApplication.main` asks it **before the
  context exists**, to choose `WebApplicationType.NONE`: a CLI run under a servlet container does its
  work correctly and then never exits, so the operator sees a command that "hangs" after succeeding.
  A second verb was the moment that list stopped being an inline string comparison.
- **SMTP through compose** (`af17c35`) — plumbing only, **mail stays off**. Every variable defaults
  to empty, and `MailConfiguration` conditions the real mailer on `!'${spring.mail.host:}'.isBlank()`
  precisely so an unset variable arriving as an empty string reads as "no relay" rather than "a relay
  at `''`" — which is §21's defect, and the reason an install with no mail must be a first-class
  configuration rather than a broken one. Enabling it later is four values in `/etc/tcms/tcms.env`
  and a restart, with no image rebuild.

**Why not a mail server on the box**, recorded so it is not re-litigated: Hetzner blocks outbound
port 25 (measured from the box), so it cannot reach a recipient's MX at all, and with no domain of
its own the instance can publish no SPF/DKIM/DMARC. 465 is blocked too, hence STARTTLS on 587.
Timeouts are 10s so a dead relay costs the message rather than the sending thread — and mail is
queued after commit, so it can never cost the invitation.

## The CI change, and what it costs (`762a836`)

CI now runs `./gradlew build -x test`. Requested for push-feedback speed: **~327s → ~60s**. `-Werror`,
the compile checks and the Modulith wiring still run.

What is paid is written into the workflow step rather than left to be rediscovered: **no machine
anywhere runs the 1000+ integration tests now.** There is no nightly lane and no scheduled lane.
`./gradlew build` on a developer machine is the whole of it, and a machine that is not run before
pushing is not a gate.

The cost was not theoretical even when it was written — the §58 clock defect was open at that moment,
and it is precisely the class that only a Linux machine can observe. §60 closes that defect and adds
an ArchUnit rule for it, *because* there is no longer an observer to catch the next one.
`docs/plans/2026-08-01-tcms-homelab-target.md` §9 is the standing proposal for getting a Linux gate
back. Reverting is deleting `-x test` from one line.

## Also in this slice

`docs/agent-context/be-java.md` — the be-java orientation cache: version lane, module graph, the
`doBegin` context ordering, the `@Transactional`-on-repositories rule, and the two `@Disabled` tests
with their one-line fix. It paid for itself immediately: §60 opened by reading it and skipped the
whole orientation pass, and its "known unknowns" entry is what made the carried debt the obvious next
slice rather than something to rediscover.

## The finding this section is

**Two slices shipped with no handoff section and the index did not notice.** §59's three commits and
the deployment-plan rewrite all landed after §58's row, and `HANDOFF.md` still ended at §58 — so a
reader arriving at the index would have been told the `issue-invitation` path did not exist, while
`OperatorCommands` had been in the tree for a day.

This is §46's "a 'Not done' note decays" in its other direction: not a stale blocker, a **missing
entry**. The convention (`CLAUDE.md`: "finishing a slice means adding a file and one index row") is
sound; what it lacks is anything that fails when the row is absent. Worth noting that the same slice
that skipped its handoff row is the one that wrote a thorough orientation cache — the artifact with a
loading protocol got written, the one relying on discipline did not.
