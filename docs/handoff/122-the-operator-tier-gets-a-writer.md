# §122 — the operator tier gets a writer

**M3 slice 7 · backend only · `./gradlew build` green (4m44s, full suite)**

## What was actually missing

Instance Admin was designed, enforced, documented, and **unreachable**.

- `Role.INSTANCE_ADMIN_ID` has been seeded by `V3` since the role catalogue landed.
- `PermissionEvaluator` resolves the SYSTEM term as its own independent term, so it survives
  the company and project gates that zero everything else.
- `instance_role_assignment` has existed since `V1` — a separate instance-level table,
  because a nullable `company_id` under RLS is permanently unreachable (§6-tenancy).
- `SchemaConventionTest`, the ERD §0.1 exception list, and `RepositoryTransactionTest` all
  cover it.

And **no code path in `src/main` had ever written a row into it.** Only three *test* classes
had ever called `InstanceRoleAssignment.forUser`. A fresh instance shipped with a complete
operator role that nobody could hold, and the only way to create one was a hand-written
`INSERT` against production state.

`RoleGrantService`'s header had already promised the fix — *"there is no HTTP path to a SYSTEM
grant in this slice and there should not be one until an instance-admin surface exists to hold
it; the operator CLI is the only way today."* It was the only way in the sense that no other
way existed, not in the sense that it worked. **This slice is that sentence, implemented.**

This is the same shape as §120's `stale_automation` and §121's open question #24: a thing the
schema and the docs both describe, that no writer had ever reached. Third instance in three
slices. It is worth asking of any `V1` column or table before assuming it is live.

## The four outcomes, and the one deliberate divergence

`GrantInstanceAdminResult` has four outcomes rather than a boolean for the reason
`IssueInvitationResult` does: each needs a *different next action* from the person at the
terminal, and "failed" hides which.

| Outcome | Exit | Why |
|---|---|---|
| `GRANTED` | 0 | The row was written. |
| `ALREADY_HELD` | **0** | See below — this is the divergence. |
| `NO_SUCH_USER` | 1 | Prints the `provision-company` line that would create the account. |
| `USER_DEACTIVATED` | 1 | Refused; see below. |

**`ALREADY_HELD` exits 0 and `issue-invitation`'s `ALREADY_ACTIVE` does not.** That looks like
an inconsistency and is a decision. `issue-invitation` asks for an **artifact** — a link — and
when it does not produce one, a script must not read that as success. A grant is not an
artifact: the operator asked for a person to *be* an Instance Admin, and after the run they
are. Re-running is the supported way to make sure, which is exactly the property
`provision-company` has and for the same reason — an operator repairing an instance should be
able to re-run the whole sequence without reading exit codes to find out which steps were
no-ops.

The `alreadyHeld` check itself is **not there for correctness**:
`uq_instance_role_assignment (user_id, role_id)` already makes a second row impossible. It is
there to turn a `DataIntegrityViolationException` into a sentence. That is why GA-5 mutates it
— deleting it looks harmless in review and costs the operator a stack trace.

## `INVITED` is granted, `DEACTIVATED` is refused

These are the two states that look like they should be treated the same and must not be.

**An `INVITED` account can be granted, and the CLI prints a note saying the grant is dormant.**
`provision-company` leaves its first administrator `INVITED`, so the only sequence that
bootstraps a fresh instance is provision → grant → redeem. Refusing the middle step would make
this command **unable to create the very first operator**, leaving the hand-written `INSERT`
as the only route to the tier — which is the thing the slice exists to remove. The grant is
inert until `ACTIVE` because `PermissionEvaluator.systemTermIfAccountActive` checks status
before reading the SYSTEM term, and it lights up on acceptance with no second command.

**A `DEACTIVATED` account is refused**, and the reasoning runs the other way through the same
mechanism. The grant would be equally inert *today* — and would become the widest power in the
product the moment somebody reactivated the user, with no second look and nothing recording
that the two acts were related. An operator who types a departed colleague's address has made
a mistake; a command that silently succeeds is how that mistake survives to become a live
grant later.

## No company context, deliberately

Every table touched — `app_user`, `instance_role_assignment` — is instance-level and carries
no `company_id` (ERD §0.1 exception list), so this runs with **no company open**.

That is not a shortcut around `CompanyContext`. `CompanyAwareTransactionManager.doBegin`
treats a context-less transaction as legitimate and leaves the GUC unset, which under RLS
means zero rows from any company-owned table reached from here — fail-closed, as designed.
Opening a context would be worse than unnecessary: it would imply the grant belongs to one
client, and **an instance-wide power scoped to a single company is a tier the model does not
have.** Contrast `IssueInvitationService`, which must resolve its company id *before* opening
the context and wrap the transaction in it. There is nothing to resolve here.

`reachesAnotherCompanyEntirely` is the test that pins this: the granted account is not a
member of the company the assertion reads through.

## The verb registration is the load-bearing line

`grant-instance-admin` had to be added to `OperatorCommands`' verb set — the one modified file
in the slice. `TcmsApplication.main:36` reads that set **before the context exists**, to choose
`WebApplicationType.NONE`. An `ApplicationRunner` whose verb is missing still runs: the command
does its work, prints its success, and then **never exits**, because a web server is listening.
The operator's terminal blocks on a step that already succeeded.

This is recorded as GA-1 and flagged as a row that **may redden nothing** — the registration is
one line in a second file, and the failure is structurally invisible to a service-level test.
If it reddens nothing, that is the finding.

## Files

| Path | |
|---|---|
| `provisioning/GrantInstanceAdminCommand.java` | CLI entry — parse, report, exit codes (187 lines, mostly operator-facing prose) |
| `provisioning/GrantInstanceAdminService.java` | Behaviour — resolve by email, check status, check held, write, log |
| `provisioning/GrantInstanceAdminResult.java` | The four-outcome record |
| `provisioning/OperatorCommands.java` | *modified* — the verb |
| `test/provisioning/GrantInstanceAdminServiceTest.java` | 7 tests, `gia-` slug namespace |

The `log.info` on a successful grant is loud on purpose and at INFO rather than DEBUG: this is
the widest grant the product has, and an instance's log should be able to answer *"when did
this account become an operator"* without a database read.

## State

**Built and unverified — 10 mutation rows, NONE RUN.** §65 is the standing policy; the
deviations §115, §117, §120 and §121 each recorded were exceptions, not a new rule. The suite
went green first time and the full `./gradlew build` is green.

**No browser rows.** There is no screen and, per `RoleGrantService`'s own reasoning, there
should not be one until an instance-admin surface exists to hold it.

**Not done, and gated on a release:** the actual grant on the lab box. The command is

```
sudo docker compose --env-file /etc/tcms/tcms.env run --rm backend \
  grant-instance-admin --email=<address>
```

which needs a redeployed image — a version bump and a `target=lab` dispatch after this lands.

## Also in the tree, not this slice

Uncommitted from earlier work and deliberately left alone: `.github/workflows/ci.yml`,
`.github/workflows/deploy.yml`, `deploy/shanks/Caddyfile`, `deploy/shanks/install-caddy.sh`,
and the two `docs/plans/2026-08-07-cicd-*` documents. The `/index.html` cache-header defect
flagged during that work is still open.

## The concurrency note, because it nearly cost the gate

This slice was written while §121's lane was live in the same tree. `./gradlew compileJava`
went **red on a file that did not exist ninety seconds earlier** — an untracked
`repository/CaseQuarantineService.java` calling `findScopedById` and `getCompanyId`, neither of
which existed yet — and the line number moved between two consecutive runs. The correct move
was to stop and not touch it: editing another lane's in-flight work is precisely how
`docs/plans/2026-07-31-parallel-execution-rework.md` §0's three silent losses happened. The
gate was run to completion only after that lane finished. **The build being red said nothing
about this slice**, and a session that had "fixed" the red would have committed a repair to
somebody else's half-written file.
