# §216 — The key that still started the application

**One phase of work**, `backend/` — one production file (a validation method), one new test class,
two comment blocks — plus the two operator documents whose failure modes change. **No migration, no
contract change, no permission moved, no `frontend/` or `packages/` byte, and no `schema` byte.**

Taken from plan §8 **#56**, the oldest open action in the repository, at the user's direction. What
#56 asks for is a **rotation on the production host**, and that is a `sudo` on a box a tool call
cannot reach — so the first thing this slice did was find out what part of it is actually owed here.

## The procedure was already written, and it was not the gap

`docs/plans/2026-08-07-linux-gate-and-key-rotation.md` §"Step 2" has carried the whole rotation since
§123: which instances are affected, the one-line check that decides it, back up, write, verify one
definition, `compose config`, `up -d`, sign in again as the proof. #56's *"a written procedure is
owed"* was discharged eight days ago and the entry never said so.

So the operator half is unblocked and unchanged. What was left was a question nobody had asked:
**what happens if the leaked key is supplied?**

## §108 fixed the spelling; the value was never refused

The line that caused §108 read `${TCMS_JWT_SECRET:?a JWT signing secret of at least 32 characters is
required}`. Spring has no `:?` form — it splits on the first `:` and takes the rest as a **default**
— so every deployment that did not set the variable signed its session tokens with the 59-character
literal `?a JWT signing secret of at least 32 characters is required`, which is in this repository.

§108's repair was the spelling (`${TCMS_JWT_SECRET}`, no colon) plus `ConfigConventionTest`, which
reads `application*.yml` as **text** and pins that spelling repo-wide. Both are rules about the
source, and they are the right rules: they stop the defect being **re-authored**.

Neither can see a value. The key arrives from an environment file on a host that neither the
repository nor its build has ever read, and `ConfigConventionTest`'s own header says why nothing
caught the original — *"long enough to clear `@NotBlank` and `@Size(min = 32)` … so the application
started without a murmur"*. **That sentence was still true this morning.** Every route back to the
failing state was open:

- a pre-§108 `/etc/tcms/tcms.env` backup restored — and step 1 of the rotation procedure *tells you
  to make one*;
- an operator meeting `Could not resolve placeholder 'TCMS_JWT_SECRET'`, searching the repository for
  the variable, and pasting the value that four documents quote;
- an env file written by copying the old compose line.

Each ends with a production instance signing forgeable sessions and a completely clean startup.

## What shipped: the instance, and the class

`JwtProperties#isSecretUsable`, an `@AssertTrue` on the bound record:

- **the instance** — `PUBLISHED_KEY`, the exact 59-character string;
- **the class** — any key beginning with `?` or `-`. A `${VAR:?msg}` resolves to `?msg` and a
  `${VAR:-x}` to `-x`, so *a signing key starting with either is the residue of a shell form Spring
  does not implement, not a key*. Any future property written that way lands here rather than in an
  incident.

The class half cannot reach a legitimate key: base64 begins with `[A-Za-z0-9+/]`, and every
provisioning path in this repository writes `openssl rand -base64 48`. A hand-chosen passphrase that
genuinely starts with one of the two characters is refused for the price of generating another,
which is the correct side to be wrong on.

A blank secret deliberately returns `true` here — `@NotBlank` owns that failure and says it better.
Two messages for one cause reads as two problems.

## The test asserts the rule and the consequence, which are not the same claim

`JwtPropertiesTest`, seven tests, no Spring context and no database:

| Test | What it settles |
|---|---|
| `thePublishedKeyIsRefused` | The key is refused — **and asserts it is ≥ 32 characters first**, because "it satisfied every other constraint" is the whole reason §108 ran for weeks. |
| `aResolvedShellDefaultIsRefused` | Both shell forms, parameterised. |
| `aGeneratedKeyIsAccepted` | The negative control. A rule that refuses everything is not a rule. |
| `aBlankKeyReportsOneCause` | Two violations, neither of them this one. |
| `theRefusedKeyIsTheOneThatShipped` | The constant against `application.yml`'s record of the incident. |
| `theContextRefusesToStartOnIt` | A context binding the key **fails to start, for this reason**. |

The last one is the one worth arguing for. A constraint nothing invokes at bind time would be §108's
own shape one layer up — a real check, correctly written, that the running application never
consults — so the rule and its consequence are proved separately. `ApplicationContextRunner` binds
through the same `ConfigurationPropertiesBindingPostProcessor` the application uses and costs
milliseconds; the alternative was a container, which is a check that runs once a night. It asserts
the **cause** as well as the failure, because a context that failed for an unrelated reason satisfies
`hasFailed()` exactly as well — measured: the first version asserted `hasMessageContaining` and went
red, because the constraint name is one cause down.

`theRefusedKeyIsTheOneThatShipped` is §213's lesson applied to a security constant. A hand-typed copy
of a string four documents already record is a second place for it to be wrong, and the way it goes
wrong is silent: the check keeps passing and stops describing the incident. It reads the quoted line
out of `application.yml`, indentation and all, and the comment block there now says **do not reword
this line** and why.

## What is deliberately not refused, and it is the interesting decision

`.env.example` ships `TCMS_JWT_SECRET=local-development-signing-key-not-for-any-server` — 51
characters, committed, and `CLAUDE.md` documents `cp .env.example .env` as a required setup step. On
its face that is the same defect one file over, and refusing it was the first thing considered.

It is **not** refused, and the argument is the file's own. `.env.example` states its position in its
header — *"LOCAL DEVELOPMENT credentials for a disposable container bound to 127.0.0.1 … not a
template for a server"* — and says exactly that about `POSTGRES_SUPERUSER_PASSWORD`,
`DB_MIGRATION_PASSWORD` and `DB_PASSWORD`, which are the same shape and the same exposure. Refusing
one of the four would break the documented setup flow, and leave the reasoning for the other three
untouched — a rule that improves the least dangerous member of a set it does not otherwise touch.

The distinction that makes the two cases different: the §108 key was **never chosen**. Nobody wrote
it as a value; it is the wreckage of a syntax error, and it reached production precisely because no
human decision stood behind it. `.env.example`'s key is a decision, recorded, in the file that owns
it. Changing it is a change to that decision and belongs in a slice about that file.

## Files

| Path | What |
|---|---|
| `backend/src/main/java/com/tcms/auth/JwtProperties.java` | `PUBLISHED_KEY`, `isSecretUsable()`, and the `@param secret` note that length was never the only bar. |
| `backend/src/test/java/com/tcms/auth/JwtPropertiesTest.java` | New, seven tests. |
| `backend/src/main/resources/application.yml` | The §108 comment block gains the value-level half, and a do-not-reword marker on the quoted key. |
| `backend/src/test/java/com/tcms/config/ConfigConventionTest.java` | Header: what a text rule structurally cannot see, and where the other half now lives. |
| `docs/plans/2026-08-07-linux-gate-and-key-rotation.md` | The new failure shape in *If it does not come up*; **"restore the backup" struck through** — if the box was on the leaked key, that backup no longer starts. |
| `docs/plans/2026-07-30-tcms-hetzner-deployment-runbook.md` | "refused two independent ways" → three, with the one that covers a hand-written env file. |

## Gates

Run on `shanks`.

| Gate | Result |
|---|---|
| `cd backend && ./gradlew build` | **SUCCESSFUL, 2709 tests / 0 skipped / 0 failures** (+7) |

`frontend/` and `packages/` hold no changed byte and were not rebuilt.

## Verification

**No browser row.** Nothing renders.

**No mutation-queue row, and the reason is not "it is covered by a test".** The behaviour that wants
driving on a real host — deploy to a box carrying the leaked key, read the container log, confirm it
names `secretUsable` — is a *deployment* step, and it now lives in the rotation plan's *If it does
not come up* section, where the operator performing the rotation will meet it. A second copy in the
mutation queue would be a second home for one instruction, and the queue is not the document somebody
holds while they are on the box.

`§203 PL-1` and §212's `PL-7`/`PL-8` are carried forward unrun.

## What is NOT done

- **The production key is still not rotated, and #56 stays open.** This slice closed the half a build
  can close. The remaining half is `ssh`, `sudo`, and about four minutes, and it is written out
  step by step. **Start with the one-line check** — `ssh tcms 'sudo grep -c "^TCMS_JWT_SECRET="
  /etc/tcms/tcms.env'` — because it decides whether any of the rest applies to this instance.
- **Nothing here can tell you whether the leaked key was ever used.** There is no server-side session
  record, so the absence of evidence is structural rather than reassuring. Unchanged from §108, and
  worth restating in the slice that might otherwise read as closure.
- **`.env.example`'s development credentials are untouched**, all four of them, by the decision
  above. The nearest candidate that would change it is a slice about that file as a whole, not about
  the signing key.
- **The refusal covers the signing key alone.** `DB_PASSWORD`, `DB_MIGRATION_PASSWORD` and the SMTP
  credentials bind through their own properties classes with no equivalent check. The class half
  (`?`/`-` prefixes) is the part that generalises, and generalising it would mean a shared constraint
  or an annotation — real work, and no incident behind it yet.
- **`ConfigConventionTest` still cannot see a value and that is not fixable from here.** The env file
  lives on a host. What changed is that the application refuses the value at bind time, which is the
  only observer that is ever in the right place.
