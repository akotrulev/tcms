# Decision round 3, steps 1 and 2 — the Linux gate, and the signing key

**Date:** 2026-08-07 · **Baseline commit:** `e43e1b3` (§121 phase 4)
**Decisions:** `docs/plans/2026-07-30-tcms-high-level-plan.md` §8 **#46** and **#56**
**Status:** neither is done. This document is the procedure both were owed.

> **Both steps need a terminal you are sitting at.** `sudo` on `shanks` requires a password and
> cannot be answered from a tool call, and the rotation touches a live instance's session keys. Every
> command below is written to be run by a person, one line at a time — see the global note about
> never pasting a multi-line block containing an interactive prompt.

---

## Why these two, and why in this order

They are steps 1 and 2 of a nine-step order because of what they change about *everything after
them*, not because they are large.

**Step 1 changes what can be observed.** `.github/workflows/tests.yml` has existed since §113 and has
never run: it targets `[self-hosted, tcms-lab]` and no runner carries that label. So the 2014-test
backend suite runs on exactly one machine, a Mac, and §58 is the standing proof that this is not
merely "sometimes nobody runs them" — on 2026-08-01 master was red for seven consecutive CI runs on a
defect that **cannot fail on macOS**, because `Clock.systemUTC()` there already yields microsecond
precision and the Postgres round-trip is therefore lossless. Every local gate was green. CI was the
only observer, and then CI stopped looking.

Seven of the nine steps in decision round 3 write backend code, and one of them (**#52**, the outbox)
touches every `@Async` path in the product. Landing the runner first means those seven land on a
differently-shaped gate instead of on the one that is structurally blind.

**Step 2 is a live exposure with a deadline attached.** The signing key is public *and* the next
deploy fails without the fix — see the trap below. It cannot be sequenced later than "before the next
release", and there is a release in every one of the remaining steps.

---

# Step 1 — register the `shanks` runner

## What exists already

| Piece | State |
|---|---|
| `deploy/shanks/install-runner.sh` | Written, self-contained, never run |
| `.github/workflows/tests.yml` | Written since §113, **inert** — nothing carries its label |
| `.github/actionlint.yaml` | Declares `tcms-lab`, so a typo in a workflow is a lint error rather than a job that queues for ever |
| `deploy.yml`'s `lab` job | Already targets the same runner; it starts working the moment this does |

The script is deliberately self-contained — it reads nothing else from the repo — so it needs no
checkout on the box. It is piped in over SSH.

## The command

Registration tokens expire in about an hour, which is why the token is minted inside the same
command rather than fetched first and pasted:

```bash
ssh -t shanks "sudo bash -s -- --token=$(gh api -X POST \
    repos/akotrulev/tcms/actions/runners/registration-token -q .token)" \
  < deploy/shanks/install-runner.sh
```

Three details in that line are load-bearing and none is decorative:

- **`bash -s --`** passes `--token=…` to the *script* rather than to `bash`.
- **`ssh -t`** allocates a TTY so `sudo` prompts you for a password on the terminal, instead of
  trying to read it from stdin — which is busy carrying the script.
- **`gh api` runs on the Mac**, inside the command substitution, so the token is minted at the moment
  of use.

You will be prompted for the `shanks` sudo password. That is expected and is the whole reason this
cannot be automated from here.

## What the script does, in order

1. Creates a dedicated `ghrunner` user — **not your login**. GitHub does not clean the workspace
   between jobs, so running as `akotrulev` would let a workflow rewrite files you are editing.
2. Adds it to `docker` (it drives compose; on this box that is effectively root).
3. Downloads the latest `actions-runner-linux-x64` tarball and **verifies its SHA-256** against the
   checksum published in the release notes. A mismatch is fatal; a checksum that cannot be *found* is
   a warning, because the release-note format is GitHub's to change.
4. Runs `installdependencies.sh`, and **tolerates its failure** — that helper carries a hardcoded
   distro table and Ubuntu 26.04 is newer than its tested matrix, so it can refuse a platform the
   runner then works fine on. `config.sh` immediately after is the real test.
5. Registers with `--labels self-hosted,linux,x64,tcms-lab --replace --unattended`, repo-scoped.
6. Installs it as a systemd service and starts it.

`--replace` makes re-running the whole thing idempotent, so a partial failure is safe to retry.

## Verify

```
https://github.com/akotrulev/tcms/settings/actions/runners
```

The runner must show as **Idle** with the `tcms-lab` label. Then prove it actually claims a job — and
use the test lane as the probe, since it is both the cheapest one and the thing you are here for:

```bash
gh workflow run tests.yml
gh run watch
```

**Expect the first run to take a while and expect it to be the interesting one.** It is the first
time these 2014 tests have executed on Linux. Its `Show what this runner actually is` step exists
precisely for this: when the nightly disagrees with your Mac, the first question is always
*different how*, and that step prints the kernel, the JDK, the Docker server version and the free
memory.

If it goes red, that is the lane working, not the lane broken. Read the failure against §58's family
before assuming a regression: anything comparing an `Instant` to a database round-trip, anything
depending on filesystem case-sensitivity, anything with a timing assumption.

## What this costs, and the rule that is absolute

A self-hosted runner executes whatever a workflow says, on this hardware, as this user.

> **THE REPOSITORY MUST STAY PRIVATE.** GitHub's own documentation says not to use self-hosted
> runners on public repositories: anyone who opens a pull request can propose workflow changes that
> then execute on your box. `akotrulev/tcms` is private (verified 2026-08-06), and open question #2
> resolved the licence as private-not-published, so this holds by decision rather than by accident.
> **If it is ever made public, remove the runner first:**
>
> ```bash
> sudo ./svc.sh stop && sudo ./svc.sh uninstall && ./config.sh remove --token <token>
> ```

Two supporting rules, both already satisfied: `pull_request` must never target this runner
(`deploy.yml` is dispatch-only, `tests.yml` is schedule + dispatch, and a **scheduled run always
executes the default branch's workflow**, never a fork's), and the registration is repo-scoped rather
than account-scoped, so another repository that is later compromised cannot claim its jobs.

## Two things that will not be automatic afterwards

- **The workspace is never cleaned between jobs.** Never write a credential into it. `deploy.yml`'s
  lab job logs out of GHCR in an `if: always()` step for this reason.
- **`_work/` and pulled images accumulate.** Prune periodically — but keep the last two or three
  image tags, because they are the rollback story.

## The gap this does not close

`tests.yml` sends a red nightly nowhere useful. GitHub emails the workflow file's last committer when
a *scheduled* run fails: one person, silently, and it moves if someone else edits the file. **A red
nightly nobody reads is worse than no nightly**, because it converts an absent gate into an ignored
one. This is tracked as an open question in `docs/plans/2026-08-06-shanks-deployment.md` §12 and is
deliberately not solved here — but it stops being hypothetical the moment the runner is Idle.

---

# Step 2 — rotate the JWT signing key (§108)

## What actually happened

`application.yml` carried:

```yaml
secret: ${TCMS_JWT_SECRET:?a JWT signing secret of at least 32 characters is required}
```

`${VAR:?msg}` is **shell and Compose syntax. Spring does not implement it.** Spring splits on the
first `:` and takes everything after it as a *default value*. So any deployment that did not set the
variable — which was all of them — signed every session token with the literal 59-character string:

```
?a JWT signing secret of at least 32 characters is required
```

That string is in this repository. **Anyone who could read the repo could forge a session for any
user, in any company, at any permission level.** Treat every token issued before rotation as
forgeable.

It survived a correct comment saying a default would be dangerous, a real `@Size(min = 32)`
validation, and 1000+ green tests — because every one of those checks a *resolved value*, and the
defect was in the *text*. `ConfigConventionTest` is the check that reads the text, and it now pins
the spelling: `${TCMS_JWT_SECRET}`, no colon.

## Which instances are affected

| Instance | Affected? | Why |
|---|---|---|
| **Hetzner production** (`tcms`) | **Yes, unless `/etc/tcms/tcms.env` already sets it** | Provisioned from the runbook before §108. The runbook did not carry `TCMS_JWT_SECRET` at all until §108 added it. |
| **`shanks` lab** | **No** — if it was provisioned by the script | `deploy/shanks/provision-host.sh` writes `TCMS_JWT_SECRET=$(openssl rand -base64 48)` when it creates the env file. A box provisioned that way was never on the default. |
| Local compose | No | `compose.yaml` declares `${TCMS_JWT_SECRET:?…}` — **correct syntax there** — so it fails fast rather than defaulting. |

**Check before acting.** This is a one-line read and it decides whether the rest of the section
applies:

```bash
ssh tcms 'sudo grep -c "^TCMS_JWT_SECRET=" /etc/tcms/tcms.env'
```

- `1` → the variable is set. Confirm it is not the leaked string, then you are only deciding whether
  to rotate as hygiene.
- `0` → **the instance is on the leaked default and the next deploy will fail.** Both halves of the
  trap below apply.

## The trap: this is not optional any more

`application.yml` now spells it `${TCMS_JWT_SECRET}` with **no colon**, so an unresolvable
placeholder throws at bind time and the context refuses to start. `compose.prod.yaml` declares it
`${TCMS_JWT_SECRET:?…}` — correct syntax in Compose — so compose refuses first.

**So a box whose env file lacks the variable cannot be deployed to at all.** The failure arrives at
`docker compose config` in the deploy path, before the pull, which is the good direction — but it
means the very next release is blocked until this is done. It is not a task that can be carried.

## The cost, stated plainly

**Rotating logs every user out.** No data is lost — tokens are stateless and no session is stored
server-side — but the symptom is the entire instance landing on the sign-in screen after a release,
**which reads exactly like the database was wiped.** Both the runbook and `provision-host.sh` carry
this warning, and `provision-host.sh` refuses to touch an existing env file for precisely this
reason.

Given the tokens are currently forgeable by anyone who can read the repo, logging everyone out is the
intended effect, not a side effect.

## Procedure

**1. Generate the new key on the box, not on the Mac.** Nothing then carries it over a shell history,
a clipboard, or this transcript.

```bash
ssh -t tcms
```

Then, on the box, one line at a time:

```bash
sudo cp -a /etc/tcms/tcms.env /etc/tcms/tcms.env.bak-$(date +%Y%m%d)
```

```bash
sudo grep -n 'TCMS_JWT_SECRET' /etc/tcms/tcms.env
```

**2. Write the new value.** If the line is absent, append it:

```bash
printf 'TCMS_JWT_SECRET=%s\n' "$(openssl rand -base64 48)" | sudo tee -a /etc/tcms/tcms.env >/dev/null
```

If the line exists and you are rotating it, edit the file in place with an editor rather than a
`sed -i` — `provision-host.sh` §559 makes the same point about this file, and a base64 value can
contain `/` and `+`, which is exactly how a careless `sed` delimiter corrupts it:

```bash
sudo nano /etc/tcms/tcms.env
```

**3. Confirm exactly one definition survived.** A duplicated key is silently last-wins, and an env
file with two `TCMS_JWT_SECRET` lines is indistinguishable from one with the right value:

```bash
sudo grep -c '^TCMS_JWT_SECRET=' /etc/tcms/tcms.env    # must print 1
```

**4. Prove compose can resolve everything before restarting anything.** This is the same check the
deploy path runs, and it is the cheap place to discover a typo:

```bash
cd /srv/tcms && sudo docker compose --env-file /etc/tcms/tcms.env config >/dev/null && echo OK
```

**5. Restart and watch the health check.**

```bash
cd /srv/tcms && sudo docker compose --env-file /etc/tcms/tcms.env up -d
```

```bash
cd /srv/tcms && sudo docker compose ps
```

Wait for `healthy`. Flyway runs on boot, so the first response is not instant.

**6. Verify from your own browser.** You will be signed out — that is the proof. Sign in again and
confirm a normal page loads; a token signed with the old key must now be rejected rather than
silently accepted.

## If it does not come up

```bash
cd /srv/tcms && sudo docker compose logs --tail=80 backend
```

Two failure shapes and they read differently:

- **`Could not resolve placeholder 'TCMS_JWT_SECRET'`** — the variable did not reach the container.
  The env file is right but compose was invoked without `--env-file`, or the key is misspelled.
- **A validation failure on `@Size(min = 32)`** — the value reached the app and is too short. An
  `openssl rand -base64 48` value is 64 characters, so this means the line was truncated, most likely
  by an editor wrapping it.

Restoring the backup from step 1 returns you to the previous state, including the leaked key. That is
a rollback for an outage, not a resolution.

## Afterwards

- Strike open action 1 in `docs/HANDOFF.md`.
- The runbook's `ROTATE THIS IF THE INSTANCE RAN ANY BUILD BEFORE 2026-08-06` block
  (`docs/plans/2026-07-30-tcms-hetzner-deployment-runbook.md` §7) stays as written — it is
  instructions for a *future* installer reading the file, not a status line about this box.
- Update §8 entry **#56** with the date and which instances were touched.

## What rotation does not undo

A forged token issued before rotation stops working. **Anything done with one has already happened.**
There is no server-side session record to audit against, so there is no way to enumerate whether the
key was ever used — the absence of evidence here is structural, not reassuring. If the instance was
reachable from the public internet with the repository readable by anyone outside the two people who
have it, that is a disclosure question rather than a technical one, and it belongs to you rather than
to this document.

---

## Both steps, as a checklist

- [ ] **#46** `ssh -t shanks … < deploy/shanks/install-runner.sh` — runner shows **Idle** with `tcms-lab`
- [ ] **#46** `gh workflow run tests.yml` — first Linux run of the suite; read a red run against §58's family before assuming a regression
- [ ] **#56** `ssh tcms 'sudo grep -c "^TCMS_JWT_SECRET=" /etc/tcms/tcms.env'` — decides whether the rest applies
- [ ] **#56** back up, write, verify exactly one definition, `compose config`, `up -d`, wait for `healthy`
- [ ] **#56** sign in again from a browser — being signed out is the proof
- [ ] Strike open action 1 in `docs/HANDOFF.md`; date **#56** in §8
- [ ] Then step 3: write `docs/handoff/110-*.md` from `9cccb2a` and `ccf9eea`
