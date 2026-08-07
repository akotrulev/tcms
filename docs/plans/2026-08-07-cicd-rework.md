# CI/CD rework — host portability, runner isolation, promote-by-digest

**Date:** 2026-08-07
**Status:** proposed, not implemented
**Supersedes in part:** `docs/plans/2026-08-06-shanks-deployment.md` §8 (runner install), the
`deploy.yml` header's account of the production path, and `deploy/shanks/provision-host.sh` as the
provisioning mechanism.

Decisions taken by the user on 2026-08-07, before this was written:

| Question | Answer |
|---|---|
| Runner isolation | Ephemeral containers for build/test + one narrow privileged deploy runner |
| Host provisioning | Ansible playbook, lab/prod inventory |
| Versioning | SHA-tagged build; production promotes an already-built digest, never rebuilds |
| Extra stages | Backend tests in the PR gate · security scanning · smoke test + auto-rollback · DB backup + migration guard |
| Branch protection on master | **Not now.** Revisit later — §9 records what that costs and what covers it meanwhile. |

---

## 0. What is already done, and by whom

A concurrent session landed the merge-to-master → lab lane while this was being designed. It is in
the working tree, **uncommitted**, at the time of writing:

- `ci.yml` gains a `version` job (derives `g<sha8>` — GitHub expressions have no substring
  function, so the eight characters must come from a step) and a `lab` job that calls `deploy.yml`
  as a reusable workflow with `target: lab` hardcoded.
- `deploy.yml` gains a `workflow_call` trigger; `preflight` accepts a second shape, `g<7-40 hex>`,
  alongside bare semver; the `ci-is-green` warning is suppressed when called rather than dispatched.

**That work stands.** This plan builds on it and does not redo it. Where it needs changing, §4 says
so explicitly.

---

## 1. The three defects this rework exists to fix

Stated first, because everything below is downstream of them.

### 1.1 The production version number is decorative

`deploy.yml`'s `deploy` job has `needs: [image, web]`, and both of those build **from the checkout
the workflow_dispatch resolved** — which is the tip of the default branch at dispatch time. The
`version` input names the resulting artefacts and nothing else.

So `gh workflow run deploy.yml -f version=0.1.3` does not deploy 0.1.3. It deploys *whatever master
is right now*, and calls it 0.1.3. The two coincide only when nobody has merged since the release
was cut, which is exactly the assumption a release process must not make. The bundle assertions
(`Assert the release version reached the bundle`) confirm the *label* travelled; nothing confirms
the *code* did.

This is also why production has never run anything the lab verified: the lab and production jobs
consume the same freshly-built image, but on two different dispatches, at two different times, from
two different commits.

**Fix:** production takes a tag that already exists and re-deploys that digest. §4.

### 1.2 The SPA cannot be promoted

The backend travels as a GHCR image — immutable, addressable, and the rollback story. The SPA
travels as a **run artifact with 30-day retention**. Promote-by-digest is impossible for a half of
the release that expires, and rollback past 30 days is already impossible today.

**Fix:** publish the SPA to GHCR as an image too. §4.2.

### 1.3 The runner is the box

Every job — `ci.yml` on `pull_request` included — runs as `ghrunner` on the machine that serves the
application, with passwordless `sudo docker` (root-equivalent), in a workspace GitHub does not clean
between jobs. `install-runner.sh`'s own rule 2 ("`pull_request` must never target this runner") is
recorded as VIOLATED KNOWINGLY.

The reasoning given for accepting it is sound *as far as it goes*: on a private repo with no forks,
whoever can open a PR can already push. But it makes the box unshareable — the stated goal of
another project using this runner means that project's jobs get root on the TCMS production host —
and it means a Testcontainers run and the live Postgres compete for the same 14 GiB.

**Fix:** two runner classes. §3.

---

## 2. Target pipeline

Five lanes. Each names the failure it observes, because a stage that observes nothing is cost.

### Lane A — pull request (`ci.yml`, `on: pull_request`)

Runs on **`tcms-build`** (ephemeral containers). Nothing here can reach the host or production.

| # | Job | Gate | Why |
|---|---|---|---|
| A1 | `guard` | actionlint · gitleaks (full history) | Workflow typos queue forever instead of failing; a committed secret must be caught before it is a rotation. Fast — fails in ~20s before the expensive jobs. |
| A2 | `backend` | `./gradlew build` — **full suite** | The decision in §1 of `ci.yml` (2026-08-02, `-x test` for speed) was taken when no Linux observer existed. One exists. See §5. |
| A3 | `frontend` | `npm ci` · `build` · `lint` · `test:run` | Unchanged. |
| A4 | `sca` | Gradle + npm dependency vulnerability scan | Advisory-only at first (§8), gate after one clean pass. |

A2 and A3 do not gate each other — a frontend lint warning must not hide a backend compile error.
Unchanged from today and still right.

### Lane B — push to master (`ci.yml`, `on: push`)

A1–A4, then:

| # | Job | Runner | Notes |
|---|---|---|---|
| B1 | `image` | `tcms-build` | Build + push `tcms-backend:g<sha8>` **and** `:master`. **Outputs the digest.** |
| B2 | `web` | `tcms-build` | Build SPA, keep both bundle assertions, push `tcms-web:g<sha8>`. Outputs digest. |
| B3 | `scan` | `tcms-build` | Trivy on both images. Gate: fixable HIGH/CRITICAL. |
| B4 | `deploy-lab` | `tcms-deploy` | Promote both digests to shanks. |
| B5 | `smoke-lab` | `tcms-build` | §6. On failure → B6. |
| B6 | `rollback-lab` | `tcms-deploy` | `if: failure()` of B5. Re-tag to the previous digest, restart, re-smoke. |

**Without branch protection, `needs:` is the gate.** A direct push to master whose tests fail stops
at A2 and never reaches B1 — the deploy chain is the protection, not the branch rule. What is *not*
covered: a push that skips CI entirely (`[skip ci]`, or a workflow-file edit that removes the
`needs`). §9.

### Lane C — production release (`release.yml`, `workflow_dispatch` only)

**Builds nothing.** This is the whole change.

| # | Job | Notes |
|---|---|---|
| C1 | `resolve` | Input is a tag (`g<sha8>` or a semver alias). Resolve to a digest; **fail if it does not exist in GHCR**. |
| C2 | `verify-lab` | Assert this digest was deployed to lab and passed its smoke test. §4.3 — this is what makes "lab-verified" a fact rather than a hope. |
| C3 | `migration-guard` | Diff Flyway migrations between the digest currently on prod and the candidate. New migration → the run requires the backup in C4 to have succeeded, and the summary says so loudly. Flyway is forward-only: **re-tagging does not roll back a schema change.** |
| C4 | `backup` | `pg_dump` on the prod box, verify non-trivial size, record path. Runs whether or not C3 found migrations — cheap, and the one time you want it is the time you did not expect to. |
| C5 | `alias` | Optionally re-tag the digest as `x.y.z` in GHCR. `buildx imagetools create` — a new tag on the same manifest, **no rebuild**. |
| C6 | `deploy-prod` | Existing SSH sequence, digest-pinned. |
| C7 | `smoke-prod` → `rollback-prod` | As B5/B6, against the production origin. |

Environment `production`. **Required reviewers: recommend ON now.** The header's argument for
leaving them off — "a release that needs a human at a terminal is the thing this workflow exists to
remove" — was about not needing a *terminal*. A dispatch already needs a human at a browser; adding
an approver costs one click and buys a second pair of eyes on the one path that reaches real data.
Your call; it is a checkbox, not an edit.

### Lane D — nightly (`tests.yml`)

Keep it, repurposed. With A2 running the suite per-PR the nightly is no longer the only observer,
but it observes different things:

- The full suite again — catches flakes and **environment drift** (a Docker or kernel update on the
  box that no code change would reveal).
- **Trivy re-scan of the images actually running on lab and prod.** New CVEs are published against
  an image that has not changed. This is the highest-value nightly stage and does not exist today.
- Smoke both origins. A box that died at 03:00 should not be discovered by a user.

Its §12 open question — a red nightly emails one person, silently — stays open and is worth closing
alongside this. §8.

### Lane E — infrastructure (`infra.yml`)

- `schedule` → `ansible-playbook --check --diff` against both inventories. **Drift detection.**
  Today, if someone hand-edits `/srv/tcms/compose.yaml` or the sudoers rule, nothing notices.
- `workflow_dispatch` → apply, behind the `production` Environment for the prod inventory.

---

## 3. Runner isolation

Two classes on shanks. The split is the point: the thing that runs untrusted-ish code and the thing
that holds root are not the same thing.

### 3.1 `tcms-build` — ephemeral, containerised

```
deploy/runners/
  Dockerfile          # actions/runner base + JDK 21 (temurin) + Node from .nvmrc + gh + rsync
  compose.yaml        # N runner replicas, each with a DinD sidecar
  runner-entrypoint.sh
  .env.example        # REPO_URL, RUNNER_LABELS, RUNNER_COUNT, registration token source
```

- `config.sh --ephemeral` — the runner **de-registers and the container exits after one job**, and
  compose restarts it fresh. This deletes the "workspace is not cleaned between jobs" hazard that
  `install-runner.sh`'s hygiene note currently asks you to remember.
- **No host Docker socket.** Testcontainers needs a daemon, so each runner gets a **DinD sidecar**
  and `DOCKER_HOST=tcp://dind:2375` on an internal network. The sidecar is privileged; it is *not*
  the host daemon, so a job cannot see, stop, or exec into `tcms-backend-1`, and cannot read
  `/etc/tcms/tcms.env`.
- Labels are per-repo (`tcms-build`). Another project runs its own stack with its own labels, its
  own Unix user, and its own DinD. Nothing is shared but the kernel.
- Memory limits per replica, because this box also serves the app. Start at `RUNNER_COUNT=1`,
  4 GiB cap; the Gradle suite is the sizing constraint. **One runner takes one job at a time** —
  concurrency is runner count, which is why `image` and `web` in `deploy.yml` currently run in
  sequence rather than parallel. Size TCMS at 2 build + 1 deploy.
- **Ephemeral costs a credential at rest, and this was under-stated above.** Registration tokens
  expire in ~1 hour, so a container that re-registers after every job needs something on the box
  that can mint them continuously — `install-runner.sh` takes a one-shot token passed in by hand,
  which does not work for ephemeral. Use a **GitHub App** scoped to the repo (issues short-lived
  tokens) rather than a PAT. The trade is "a stale workspace between jobs" for "a signing key on
  the box"; still worth it, but it is not free and the App is the form that keeps it small.

**This is what makes `pull_request` on a self-hosted runner defensible** rather than knowingly
violating the installer's own rule. Worth recording in `install-runner.sh` when it is rewritten.

### 3.2 `tcms-deploy` — privileged, narrow

> **→ superseded by §11.2 item 1 (2026-08-07).** Under one rootless Docker daemon per project
> there is no privilege to narrow: the sudoers grant disappears and the deploy runner runs as the
> project's own unprivileged user. Keep this section as the fallback shape if rootless proves
> unworkable (see §12 q5), and as the record of why a *privileged* deploy runner was the previous
> answer.

The existing host runner (`ghrunner`, scoped sudoers, `/srv/tcms` access), relabelled, with one
change: **it is the only runner the deploy jobs target, and deploy jobs are the only jobs that
target it.**

> ~~Put it in a GitHub runner group restricted to the `homelab` and `production` Environments so a
> new workflow cannot pick it up by accident.~~ → **wrong, corrected 2026-08-07.** Runner groups
> are an organization feature (and require Team or higher for private repos). `akotrulev` is a
> **User** account — verified via `gh api users/akotrulev -q .type` — so there are no runner groups
> and no way to restrict which workflows may target a runner.
>
> **Routing is by label alone, and a label is not a security boundary:** any workflow in the repo
> can target any label its runners carry. The GitHub-level boundary that *does* exist is that
> runners are **repo-scoped** — a runner registered to `akotrulev/tcms` cannot run another repo's
> jobs, so projects cannot accidentally share one. Within a repo, isolation must come from the
> OS-level split in §11.2, never from GitHub.

Its sudoers stays as-is — `docker, rsync, mv /tmp/compose.yaml …, sed` — and §4.2 adds one entry for
extracting the web image.

**Cost, stated plainly:** DinD is a privileged container and a container escape from it reaches the
host. This is strictly better than today (where jobs are *already* root-equivalent via
`sudo docker`, with no escape required) but it is not a security boundary you should bet a
production database on. It is the right shape for a homelab; it is not the right shape for
untrusted contributors, and rule 1 — **the repo stays private** — still governs.

---

## 4. Promote by digest

### 4.1 Tagging

| Tag | Written by | Meaning |
|---|---|---|
| `g<sha8>` | Lane B, every master push | Immutable, the unit of promotion |
| `master` | Lane B | Moving pointer, convenience only — never deployed from |
| `x.y.z` | Lane C5 | An **alias** added to an existing manifest; no rebuild |

`preflight`'s two-shape regex (already landed) is exactly right and needs no change.

### 4.2 The SPA becomes an image

New `frontend/Dockerfile.dist` — a `scratch` image whose only content is `dist/`. Published as
`ghcr.io/akotrulev/tcms-web:g<sha8>`.

On the box, the deploy step becomes `docker create` + `docker cp` + `rsync --delete` into
`/srv/tcms/web/` — Caddy keeps serving static files off disk exactly as it does now, so the serving
path is unchanged and only the *transport* changes.

What this buys: promotion and rollback work at any age, `download-artifact` and its 30-day expiry
leave the deploy path, and `deploy.sh`'s break-glass lane stops depending on `gh run download`
resolving the right run.

Sudoers gains `/usr/bin/docker` coverage it already has — no new grant needed. Verify during
implementation.

### 4.3 "Lab-verified" must be a fact

C2 is the load-bearing new idea and it needs somewhere to read from. Simplest mechanism that works
without new infrastructure: **B5 writes a deployment record to the GitHub Deployments API** for the
`homelab` Environment, carrying **the release manifest** (§14.3 — not a bare digest, so a
multi-service release rolls back as a set), and marks it `success` only after the smoke test passes.
C2 queries it. No database, no file on a box, and it is what the API is for — the `homelab`
Environment already exists and is currently doing nothing but naming the job.

Fallback if that proves awkward: a `lab-verified` tag in GHCR moved by B5. Weaker (a tag is mutable
and carries no timestamp) but trivial.

---

## 5. Backend tests return to the PR gate

`ci.yml` currently records the 2026-08-02 trade — ~327s → ~60s, paid for by nobody running the
suite. The Linux observer that made that acceptable-in-principle now exists.

Restoring it means **A2 takes ~5–6 minutes** and gates every PR. That is the cost, and it is the
right one to pay: the alternative currently in the tree is that master continuously delivers to lab
with the suite having run *nightly, afterwards*.

Two mitigations, in order of preference:

1. **Gradle build cache persisted on the box**, mounted into the ephemeral runner as a named volume.
   Ephemeral containers otherwise start cold every job, which would make this far worse than 327s.
   This is a prerequisite, not an optimisation.
2. If it is still too slow: split A2 into `compile` (gates, fast) and `test` (gates, slower) so the
   `-Werror` diagnostic arrives early. Do **not** reintroduce `-x test`.

`tests.yml` keeps running the suite nightly for the reasons in Lane D.

---

## 6. Smoke test + auto-rollback

A deploy that reports success because a container reached `healthy` proves Spring started. It does
not prove the SPA was served, that Caddy is routing, that the DB has the right schema, or that a
login works. The health-check wait already in both deploy jobs stays; this is the layer above it.

`deploy/smoke.sh <origin>` — one script, run against lab and prod, so the two cannot drift:

1. `GET /actuator/health` → `UP`
2. `GET /` → 200, `text/html`, and the body contains the expected build id (**catches a stale SPA —
   the exact failure the `VITE_APP_VERSION` assertion was written for, but observed on the box
   instead of in the bundle**)
3. `GET /api/<unauthenticated endpoint>` → 200, correct shape
4. Authenticated read using a smoke-test API token (M3 slice 1 landed API tokens — use one, scoped
   read-only, in a smoke-only company) → proves the DB round-trip, RLS context, and the
   auth path together

On failure: restore the previous release manifest, `up -d`, re-smoke, and **fail the run anyway** so
a rollback is never silent.

The previous manifest comes from the Deployments API record in §4.3 — the box's own state holds
only the current one. **Roll back the manifest as a set, never a single service** (§14.3): with one
service today those are the same operation, which is exactly why it should be written the general
way now rather than discovered during an incident later.

---

## 7. Ansible

`deploy/ansible/`, replacing `provision-host.sh` (613 lines) and `install-caddy.sh` (178).

```
inventories/
  lab.yml           # shanks — via the `shanks` ssh alias, never a hardcoded IP
  prod.yml          # hetzner
group_vars/
  all.yml           # layout, versions
  lab.yml           # 14 GiB sizing, tailscale cert
  prod.yml          # its own sizing, public origin
roles/
  base/             # locale, unattended-upgrades, lid-and-sleep (lab only)
  docker/
  postgres/         # PGDG 17, sizing, the two roles, the bridge/pg_hba traps A/B/C
  app/              # /srv/tcms, /etc/tcms/tcms.env, compose, sudoers
  caddy/            # Caddyfile, cert acquisition + renewal
  runner/           # both classes from §3
site.yml
```

**Moving to a new VM becomes:** `ansible-playbook -i inventories/prod.yml site.yml` against a fresh
Ubuntu host. That is the deliverable you asked for.

Three rules the conversion must not lose — each is in the bash today and each was written for a
failure that happened:

1. **Never regenerate `/etc/tcms/tcms.env` if it exists.** `provision-host.sh` is explicit. In
   Ansible: `force: no` on the template, and secrets generated only on first create. A playbook that
   helpfully rewrites the file rotates every password and invalidates every outstanding invitation
   link.
2. **The Postgres bridge traps (A, B, C) are one step for a reason** — the listen address, the
   `pg_hba` entry, and the Docker bridge subnet must agree, and the failure when they do not is a
   container that cannot reach a database that is running fine. Keep them in one role with the
   comment intact.
3. **`visudo -c` before installing the sudoers file.** A malformed drop-in locks the box out of
   sudo entirely; the bash validates and removes on failure. Ansible's `validate:` parameter does
   this natively — use it.

`deploy.sh` stays as break-glass, updated for digest promotion. Its header rule — "when you change
one, change both" — now spans three files (`deploy.sh`, `deploy.yml`, `release.yml`); consider
extracting the shared remote sequence into `deploy/remote-deploy.sh` that all three invoke, which
would make the rule structural instead of remembered.

---

## 8. Security scanning

| Gate | Tool | Where | Initially |
|---|---|---|---|
| Secrets | gitleaks, full history | A1 | **Gate** — a leaked secret is a rotation, not a warning |
| Workflow lint | actionlint (`.github/actionlint.yaml` already declares `tcms-lab`) | A1 | Gate |
| SCA | Gradle + npm | A4 | Advisory for one week, then gate on HIGH+ |
| Image CVE | Trivy | B3, and nightly against running images | Gate on **fixable** HIGH/CRITICAL only |

"Fixable only" is deliberate: gating on unfixable base-image CVEs makes the gate unpassable, and an
unpassable gate gets disabled, which is worse than not having one.

`ConfigConventionTest` already covers the `${VAR:?…}`-is-not-Spring class of defect (§108) — that
stays where it is, in the backend suite, and A2 runs it.

---

## 9. What master having no branch protection costs

Recorded because it was an explicit decision, not an oversight, and because it should be revisitable
without re-deriving the reasoning.

**Covered by the `needs:` chain:** a push to master with failing tests, a compile error, a lint
failure, a secret, or a failing image scan does not reach the lab. B1 needs A1–A4.

**Not covered:**
1. A push that changes `ci.yml` itself to remove the `needs`. The workflow that validates the push
   is the workflow the push can edit.
2. Force-push to master. Nothing observes it.
3. Two people pushing at once — `concurrency` cancels the older CI run, and `deploy-${{ github.ref }}`
   with `cancel-in-progress: false` means two lab deploys queue and land in an order nobody chose.

None of these matter with one developer on a private repo, which is why the decision is reasonable
today. All three start mattering on the day a second person gets push access — that is the trigger
to revisit, not a date.

---

## 10. Sequencing

Each phase is independently shippable and leaves the pipeline working. Gates per `CLAUDE.md`
(`./gradlew build` / `npm run build` + `npm run lint`), commit per phase.

| Phase | Work | Unblocks |
|---|---|---|
| 1 | Commit the concurrent session's lab-CD work as-is | Everything — it is uncommitted |
| 2 | `deploy/runners/` — ephemeral container runners, registered alongside the existing one | Phase 4 |
| 3 | Ansible conversion, verified with `--check --diff` against shanks before any apply | VM portability |
| 4 | Move Lane A/B build jobs to `tcms-build`; restore the full suite to A2; persist the Gradle cache | §1.3, §5 |
| 5 | `tcms-web` image + digest promotion + `release.yml`; retire the artifact path. **Plus the three §14 changes: the release manifest replacing `TCMS_VERSION`, the health wait iterating over services, and Flyway as an explicit deploy step** | §1.1, §1.2, §14.2 |
| 6 | `deploy/smoke.sh`, Deployments API records (**carrying the manifest, not a digest**), auto-rollback | §6, §14.3, and C2 depends on it |
| 7 | Security gates, advisory first | §8 |
| 8 | `infra.yml` drift detection; nightly repurposed | Lanes D, E |
| 9 | Rootless per-project Docker daemon + port registry; retire the sudoers grant | §11.2 (1) |
| 10 | Caddy `sites.d/`; parameterise the Ansible `app` role by project | §11.2 (3)(4) |
| 11 | Postgres into compose, with a rehearsed data migration on lab first | §11.2 (2) |

| 12 | Migrate off Tailscale to WireGuard; Hetzner becomes the public entrypoint for both origins | §13 |

Phases 9–11 are the multi-project work and are **independent of 1–8** — the pipeline is correct
without them. Do them when project #2 is real, or sooner if the shared-daemon root grant in §11.1
is judged unacceptable on its own terms. Phase 11 is the highest-risk item in this plan and must be
rehearsed on lab, with a verified `pg_dump` restore, before production is touched.

Phase 3 is the largest and the one with the most that can go silently wrong — it rewrites the
mechanism that creates production's database roles and secrets. It should be driven through the
`devops` plugin skills (`devops-engineer` → `devops-infrastructure-as-code`), per `CLAUDE.md`, and
verified in check mode against the live box before it is ever applied.

---

## 11. Multi-project isolation on one box

Added 2026-08-07 at the user's direction: this VM may host other projects, and they must not
collide. §3 isolates the *runners*; this section is about everything else, because isolating the
runner while both projects share a root Docker daemon isolates nothing.

The box is already multi-tenant — Nextcloud's snap Apache owns `:80` (which is why the Caddyfile
disables the HTTP redirect) and microk8s/Calico is running. So this is a present condition, not a
future one.

### 11.1 Collision surfaces, as they stand

| Surface | State | Note |
|---|---|---|
| `/srv/tcms`, `/etc/tcms`, compose project name, container names | ✅ isolated | Already namespaced by project |
| Docker network subnet | ⚠️ | TCMS pins the whole `172.28.0.0/16`. Nothing records which /16s are taken |
| Host port `127.0.0.1:8080` | ❌ | Project #2's backend wants 8080 |
| Caddy | ❌ | `install-caddy.sh` writes `/etc/caddy/Caddyfile` wholesale — project #2 overwrites project #1 |
| Postgres | ❌ | One host cluster. `pg_hba` grants `172.17.0.0/16` — the **default docker0 bridge** — so any container from any project can reach the `tcms` database. scram-gated, not open, but network-reachable |
| The Docker daemon | ❌ | `/etc/sudoers.d/tcms-deploy` grants `ghrunner` passwordless `/usr/bin/docker`, which is root. Two projects with that grant are root on each other |

The daemon row is why §3 alone is insufficient: two runners in containers talking to one rootful
daemon are one `docker run -v /:/host` apart.

### 11.2 The design

1. **One rootless Docker daemon per project, one Unix user each.** The sudoers grant disappears
   entirely — there is no `sudo docker` to grant, because the daemon runs as the project's own
   user. A compromise of one project's runner cannot see another's containers, volumes or images.
   Testcontainers works under rootless. This replaces §3.2's "narrow privileged runner" with
   something better: a runner that needs no privilege at all.

   **The trade, stated because it is real:** rootless daemons are fully separate, so containers in
   different projects **cannot share a Docker network**. Cross-project traffic goes over host
   loopback, which means a **port registry** — an Ansible var (`tcms: 8080`, `projectb: 8081`) —
   rather than a shared ingress network. Slightly less elegant; worth it for the daemon split.

2. **Postgres moves into each project's compose stack**, on a named volume. Deletes the shared
   cluster, the `/16` `pg_hba` grants, **and the entire traps-A/B/C/D bridge step** in
   `provision-host.sh` — ~100 lines of `listen_addresses`, `ip_nonlocal_bind` and `ufw` machinery
   that exists *only* because a container must reach a host database.

   The host-Postgres decision was deliberate — compose.prod.yaml calls it "the one piece of state
   that must survive every redeploy of everything else" — and a named volume preserves that
   property exactly: `docker compose down` without `-v` keeps it. But this needs a **real data
   migration on both boxes** and is the highest-risk item in this plan.

   **Middle path if production's database should not move:** `pg_createcluster` — a separate
   cluster per project on its own port, natively supported on Ubuntu. Kills the shared-database
   exposure; keeps the bridge traps.

3. **Caddy becomes a shared ingress with per-project config.** `/etc/caddy/Caddyfile` reduces to
   `import /etc/caddy/sites.d/*.caddy`; each project's Ansible run owns exactly one file in
   `sites.d/`. One listener on `:443` is unavoidable — see 11.4 — so it is shared infrastructure,
   owned by a `caddy` role rather than by any project.

   **Tailscale Funnel cannot serve this shape** — it exposes the node's own name only, on ports
   443/8443/10000, with no vhosts. Resolved by §13: Hetzner becomes the public entrypoint over a
   WireGuard tunnel, and ACME runs there with plain **HTTP-01** (`:80` is free on that host).
   Phase 12 therefore unblocks this one — see §13.9 on ordering.

4. **Ansible's `app` role becomes parameterised by project**, so adding project #2 is an inventory
   entry (name, port, subnet, hostname) rather than new code. This is the test of whether §7 was
   done right.

5. **Resource limits, not isolation** — systemd slices per project user plus compose `mem_limit`
   and `cpus`. On 14 GiB shared between microk8s, Nextcloud, the app and a Testcontainers suite,
   this is the constraint that bites first, and it is a *scheduling* problem that no amount of
   namespacing solves.

### 11.3 Runner topology — per target, never a hub

Asked directly on 2026-08-07 ("can this runner deploy other projects to different VMs?"), and the
answer is no, for a reason worth stating as a rule.

**The self-hosted runner exists for exactly one reason: shanks is behind NAT and cannot accept
inbound SSH**, so its deploy must be pull-based. A VM with a public IP does not have that problem —
Hetzner is deployed *to*, over SSH, from a runner. Making shanks a deployment hub would accumulate
SSH keys to every project's production on one laptop, creating a single point of compromise for all
of them, in exchange for nothing.

| Target | Deploy from |
|---|---|
| Public IP | A GitHub-hosted runner over SSH. No shared tenancy at all — this is what the `production` job already does |
| NAT'd / tailnet-only | A runner **on that VM**, registered to that VM's repo |

The only case for a hub is a target that is tailnet-only *and* cannot host a runner. Accept that
knowingly and confine the key to an ephemeral container.

**E2E suites for another project** are the worst job type to co-locate, and the reason is not
primarily security: they carry the largest third-party-code-execution surface of anything you run
(browser binaries plus a deep npm tree) *and* they are the most timing-sensitive. Playwright
competing with a live app, microk8s, Nextcloud and a Testcontainers suite on 14 GiB will make them
flaky long before it makes them unsafe. Under §11.2 they are acceptable; under today's shared root
daemon they are not, because such a job can read `/etc/tcms/tcms.env` and `pg_dump` the database.

### 11.4 What cannot be isolated at any effort

The kernel. `:443` (one listener per address — hence shared ingress). RAM, CPU and disk. Say so
plainly rather than implying a container boundary is stronger than it is.

---

## 12. Public exposure — what Funnel changed

`deploy/shanks/install-caddy.sh` gained a Tailscale Funnel step on 2026-08-07 (uncommitted at the
time of writing, alongside the lab-CD work). The requirement it serves is standing and applies to
every host: **the app must be reachable from anywhere on the internet, not only from the tailnet.**

Hetzner already was — the runbook's Caddy owns `:80` + `:443` and gets its certificate from ACME.
Funnel brings shanks to the same posture by a different mechanism.

### 12.1 The access-control model changed and nothing downstream did

The Caddyfile's `bind` comment justified itself as *"access control is therefore tailnet
membership."* Funnel deletes that premise, and `install-caddy.sh` says so in its own output:
*"Tailnet membership is no longer the access control — the app's login is."*

**The login is not currently equipped to be the only gate:**

```
grep -rln "RateLimit|Throttl|LoginAttempt|lockout|failedAttempt" backend/src/main/java/
→ no matches
```

No rate limiting, no account lockout, no failed-attempt throttling. This is **not** a Funnel-only
problem — Hetzner has been publicly reachable since it was stood up, so Funnel removed the last
thing that was masking a gap that already existed on production. It should be fixed in the
application, not at the proxy, because only the application knows what a login attempt is.

### 12.2 TCP passthrough loses the client IP

`tailscale funnel --tcp 443 tcp://127.0.0.1:443` has tailscaled terminate the public TCP connection
and dial loopback, so Caddy sees `remote_addr = 127.0.0.1` for **every public request**.
Consequences, all of which bear on 12.1:

- IP-based throttling cannot be added later — there is no client IP to key on.
- Access logs in the journal cannot distinguish a credential-stuffing run from normal traffic.
- Nothing can be banned, by Caddy or by fail2ban.

Verify from a non-tailnet device: `journalctl -u caddy -f | grep remote_ip`.

The passthrough choice is well-reasoned in the script — letting tailscaled terminate TLS would mean
re-plumbing the Caddyfile onto a plain-HTTP port and maintaining two paths that must agree — but
this cost was not priced in. If IP-based controls are wanted, that is the trade to revisit
(tailscaled's HTTPS mode forwards `X-Forwarded-For`; raw TCP cannot).

### 12.3 Funnel does not scale to multiple projects

Bears directly on §11. Funnel serves **the node's own MagicDNS name only** — no virtual hosts — on
**ports 443, 8443 and 10000 only**. So on one box:

- Project #1 takes 443. Project #2 gets 8443, or path-based routing under the same hostname.
- Path-based breaks the SPA: the Caddyfile's `try_files {path} /index.html` fallback assumes it
  owns the origin, and a second app under a path prefix collides with it.

**For several publicly-reachable projects on one box, Funnel is the wrong ingress.** The answer is
a real domain with Caddy ACME vhosts, one site block per project in `sites.d/` (§11.2 item 3) — and
because Nextcloud owns `:80`, the challenge must be **DNS-01** (Cloudflare), not HTTP-01. That also
restores the client IP, which resolves 12.2.

~~Funnel remains the right answer while shanks hosts one project.~~ → **superseded 2026-08-07 by
§13**: Tailscale is being dropped for WireGuard, with Hetzner as the public entrypoint. Note that
this also **retires the DNS-01 requirement above** — it existed only because Nextcloud owns `:80`
on shanks, and ACME moves to Hetzner, where `:80` is free and plain HTTP-01 works.

### 12.4 What it improves

The smoke tests in §6 get better, not merely possible: a public origin can be exercised **from any
runner over the real public path** — the one users traverse and the one that just changed. A
tailnet-only origin could only be smoke-tested from a tailnet device, which proves nothing about
the path that Funnel added. `install-caddy.sh`'s closing note makes the same point about manual
verification.

It also makes **DAST genuinely applicable** where it was not before: add a ZAP or Nuclei baseline
scan against the lab origin to §8, and make `/actuator/*` returning 404 an assertion in
`deploy/smoke.sh` rather than a property of a config file nobody re-reads.

### 12.5 Ansible must own the Funnel state

`install-caddy.sh`'s own header is the argument: **Funnel is tailscaled state, not a file.** A
rebuilt host comes back tailnet-only, serving correctly, with no error anywhere — the URL works for
whoever is on the tailnet and for nobody else. That is precisely the failure shape §7 exists to
eliminate, so the `caddy` role must assert `tailscale funnel status` and the `infra.yml` drift check
(Lane E) must catch it having been turned off.

---

## 13. Migrate off Tailscale to WireGuard

**Decision: adopt.** Added 2026-08-07 at the user's direction. This supersedes §12.3's "keep Funnel
while shanks hosts one project" and resolves open question 7 (a real domain) in the same move.

Note first that **Tailscale *is* WireGuard** — same data plane. What is being dropped is the
coordination layer and, specifically, **Funnel as the ingress mechanism**. The win comes from moving
the *public entrypoint*, not from swapping the tunnel; WireGuard is chosen for what it does not
carry with it.

### 13.1 Target shape

```
                      :443
    internet ────────────────► Hetzner  ── Caddy, ACME (HTTP-01), vhosts
                                  │          ├── tcms.<domain>      → 127.0.0.1:8080   (prod, local)
                                  │          └── lab.tcms.<domain>  → 10.44.0.2:8080   (over wg0)
                                  │
                              wg0 │ WireGuard, UDP 51820
                                  │
                               shanks ── backend container on 127.0.0.1:8080
                                          Caddy on shanks: REMOVED or demoted to local-only
```

`shanks` dials **out** to Hetzner's fixed public endpoint with `PersistentKeepalive`, so NAT, a
dynamic IP and CGNAT are all still handled — the same property Funnel was bought for.

### 13.2 What this fixes, and what it costs

Fixes, all previously recorded as open:

- **§12.2 — the lost client IP.** Caddy on Hetzner terminates TLS and sets a real
  `X-Forwarded-For`. This is what makes instance-level login throttling (§12.1) implementable at
  all, and it is the strongest single reason to do this.
- **§12.3 — no vhosts, three ports.** Real domain, unlimited vhosts, one site file per project.
  Multi-project public exposure (§11.2 item 3) stops being blocked.
- **DNS-01 is no longer required.** That recommendation existed only because Nextcloud owns `:80`
  on shanks. ACME now runs on **Hetzner**, where `:80` is free, so plain **HTTP-01** works and no
  DNS provider credential is needed anywhere. §12.3 is amended accordingly.
- **Third party leaves the serving path.** Funnel routes public traffic through Tailscale's relays
  under non-configurable bandwidth limits. This removes both.
- **Licensing.** Tailscale's free Personal plan is "only suitable for non-commercial use". Today
  Tailscale touches only the homelab, which is defensible — but putting a *Tailscale* tunnel in
  production's serving path would convert a free homelab dependency into a commercial one at
  $8/user/month. WireGuard raises no such question.

Costs, stated plainly:

- **Hetzner becomes a single point of failure for lab ingress.** It is not one today. A production
  box outage now also takes the lab offline.
- **A network path is created from production to the CI laptop.** See 13.4 — this is the item that
  can actually hurt, and it is not optional to get right.
- **Key management becomes yours.** See 13.5.
- **Extra hop and latency** for lab traffic, routed via the VM's datacentre.

### 13.3 `TCMS_BASE_URL` changes — and that invalidates outstanding invitations

`https://clearstep.TAILNET.ts.net` becomes `https://lab.tcms.<domain>`. This is not a cosmetic
rename: `provision-host.sh` carries an explicit warning that the base URL is baked into **invitation
and activation links already sent**, and changing it leaves every outstanding link pointing at a
host that no longer answers.

Lab-only, so the blast radius is small — but re-issue any live invitations after the cutover, and
**do not repeat this migration on production without planning for it.** Production's origin does not
change here; it is already a real domain served by its own Caddy.

### 13.4 The tunnel must be firewalled in one direction only

The single most important constraint in this section. `shanks` runs CI, Nextcloud, microk8s and —
under §11 — other projects' test suites. It is the least-trusted machine in the estate. Production
holds real customer data. The tunnel connects them.

| Direction | Allowed |
|---|---|
| Hetzner → shanks | **TCP 8080 only** (the reverse-proxy target) |
| shanks → Hetzner | **Nothing.** Explicitly not 5432, not 22, not 443 |

Enforce on **both** ends — `wg0` interface rules in `ufw` on each host, plus `AllowedIPs` scoped to
single `/32`s rather than a subnet. `AllowedIPs` is a routing statement, not a firewall; it is
necessary and not sufficient. A WireGuard peer that can reach production's Postgres is strictly
worse than the shared-cluster problem §11.2 exists to fix.

### 13.5 Upkeep — what owning this actually costs

The honest answer to "what maintenance does this add". Most of it is one-time; the recurring column
is short, which is the argument for doing it at two hosts.

| Item | Cadence | Notes |
|---|---|---|
| **Keypair generation** | Once per host, again on rebuild | `wg genkey`/`pubkey`. Private keys are host secrets: `0600`, root-owned, and they belong in the same never-regenerate-on-re-run category as `/etc/tcms/tcms.env` (§7 rule 1). An Ansible run that helpfully regenerates a key silently breaks the tunnel |
| **Peer config** | On any key or endpoint change | Two files, `wg0.conf` on each end. Each lists the other's public key. **This is the whole configuration** — there is no directory service to consult |
| **Key rotation** | Annually, or on suspected compromise | Manual. There is no automatic rotation. Rotating means editing both ends; do the *listening* end last or you cut yourself off |
| **Firewall rules** | Set once, verify on every `infra.yml` drift run (Lane E) | 13.4. The rules are the security boundary, so drift here is a silent downgrade |
| **Hetzner endpoint stability** | Whenever the VM's public IP changes | shanks' config hardcodes it. A VM rebuild with a new IP requires editing shanks. Use a DNS name in `Endpoint` and this becomes automatic |
| **DNS** | Once | `A` record for `lab.tcms.<domain>` → Hetzner. Same zone as production |
| **TLS certificates** | **Automatic** | Caddy ACME on Hetzner. This *removes* upkeep: `tailscale cert` does not self-renew, which is why `install-caddy.sh` ships a weekly systemd timer. That timer, its unit, and the renewal trap all get deleted |
| **Tunnel liveness monitoring** | Continuous — **new requirement** | A dead tunnel means lab is unreachable and nothing currently notices. `wg show` last-handshake age, or simply let the §6 smoke test cover it, since a broken tunnel fails it. Feeds open question 2 (nowhere for alerts to go) |
| **MTU** | Once, if symptoms appear | WireGuard's overhead means 1420 is typical. HTTP over the tunnel usually just works; if large responses hang while small ones succeed, this is the cause |
| **Kernel module** | None | WireGuard is in-kernel since Linux 5.6. Ubuntu 26.04 needs no install |

**Interaction with this box's networking, which is unusual:** `shanks` runs **netplan +
systemd-networkd with no NetworkManager**. Either define the tunnel natively in netplan (it supports
WireGuard `tunnels:`) or use `wg-quick@wg0.service`. Prefer **netplan**, so all of the host's
networking stays described in one place and the Ansible `network` role owns it — a `wg-quick` unit
alongside netplan is the kind of split ownership that makes the next person guess.

### 13.6 What gets deleted

- `install-caddy.sh`: the `tailscale cert` step, the **weekly renewal timer and its systemd unit**,
  and the entire Funnel step
- `Caddyfile`: the `bind 100.64.0.10 127.0.0.1` line, the `tls <file> <file>` pair (Caddy manages
  the cert on Hetzner instead), the site address
- `provision-host.sh`: the `TAILNET` placeholder guard and its `--base-url` validation branch
- §12.5 entirely — there is no Funnel state for Ansible to assert
- The Tailscale account dependency, and the licensing question with it

Roughly 60 lines of scripts and a systemd timer, replaced by two `wg0.conf` files and a Caddy site
block. **Verify that count during implementation** rather than trusting it.

### 13.7 SSH access to shanks — check this before cutting over

If Tailscale is currently how you reach `shanks` **from outside the house**, dropping it removes
that. On the LAN, `ssh shanks` resolves via mDNS (`shanks.local`) and is unaffected.

The replacement is `ProxyJump` through Hetzner over `wg0` — but note that 13.4 forbids shanks →
Hetzner traffic, not Hetzner → shanks, so this works *and* requires opening TCP 22 in the
Hetzner→shanks direction. Decide deliberately whether remote SSH to the lab is worth that second
open port; the deploy path does not need it (the runner is pull-based and needs no inbound at all).

### 13.8 Migration order — lab must never be stranded

1. Register the domain and point `lab.tcms.<domain>` at Hetzner. **Do not** change anything yet.
2. Bring up WireGuard on both hosts. Verify with `wg show` and a curl from Hetzner to
   `10.44.0.2:8080`. Tailscale is still up and still serving — nothing user-visible has changed.
3. Apply the firewall rules from 13.4 and **test that the forbidden direction is actually blocked**,
   rather than assuming.
4. Add the `lab.tcms.<domain>` site block to Hetzner's Caddy. Both origins now work.
5. Confirm the client IP is real in Hetzner's Caddy logs — this is the thing the migration is for,
   and it is the point at which it is proven rather than hoped.
6. Update `TCMS_BASE_URL` on shanks, restart, re-issue any live invitations (13.3).
7. Run the §6 smoke test against the new origin.
8. `tailscale funnel --tcp=443 off`. **Leave Tailscale installed and logged in** for a fallback
   window.
9. After a week clean, remove Tailscale and delete the code in 13.6.

Steps 1–5 are all reversible and change nothing a user can see. The commit point is step 6.

### 13.9 Sequencing

Slots as **phase 12**, after the §11 multi-project work, because 13.1's vhost ingress is what
phase 10 (Caddy `sites.d/`) is building toward — doing them in the other order means writing the
`sites.d` layout twice.

**Do it earlier if login throttling (§12.1) is prioritised**, since a real client IP is a
prerequisite for doing that properly, and §12.1 is the most urgent finding in this document.

---

## 14. Does this accommodate a later move to microservices?

Asked 2026-08-07. **Mostly yes** — the artifact and promotion half generalises cleanly. Three things
do not, and **two of them are worth fixing regardless of whether the monolith is ever split**, which
is why they are folded into phases 5 and 6 rather than deferred to a hypothetical.

### 14.1 What survives unchanged

GHCR as the registry · promote-by-digest · ephemeral CI runners · per-image security scanning ·
Ansible provisioning · the WireGuard/Caddy ingress (vhost or path routing to N services is what
Caddy is for). None of these care how many services exist.

### 14.2 What breaks, and the change that fixes it

**1. `TCMS_VERSION` is a single variable, `sed`-edited in place.** The sharpest one. One env var
naming one image tag, with a read-back check grepping for one value. N services means N variables
and N `sed` calls, and — worse — no way to describe a release *atomically*: a partial deploy leaves
nothing recording what the intended set was.

> **Change, in phase 5:** replace `TCMS_VERSION` with a **release manifest** mapping service →
> digest. Today it is a one-line manifest. The deploy step renders compose's env from it instead of
> `sed`-editing a line, and the read-back check compares the rendered set rather than one string.

**2. The health wait hardcodes a container name.** `docker inspect -f '{{.State.Health.Status}}'
tcms-backend-1` is one container, by name, in two places (the `lab` and `production` jobs) plus
`deploy.sh`.

> **Change, in phase 5:** iterate over the compose project's services and require all healthy.
> Trivial now, mandatory later, and it removes a hardcoded name that is already duplicated three
> times.

**3. Flyway runs implicitly at application startup.** This is why the deploy waits on the
container's healthcheck rather than a sleep — the existing comment says so. With multiple services
it is an anti-pattern: replicas race for the Flyway lock, and a shared database migrated by
whichever service boots first is precisely the failure microservices are meant to eliminate.

> **Change, in phase 5 or 6:** run migrations as an **explicit deploy step** before traffic is
> flipped. This also makes Lane C's migration guard (C3) mean something — at present it *detects*
> new migrations while they still apply implicitly at boot, so the only evidence they ran is the
> container going healthy. Separated, a migration failure fails the deploy before anything serves.

### 14.3 The manifest is the load-bearing one

It changes §4.3. The deployment record written by B5 should carry **the manifest**, not a single
digest — which makes rollback of a whole release *set* atomic by construction rather than something
to be invented later, under pressure, during an incident. At one service the two are identical, so
this costs nothing today and cannot be retrofitted cheaply.

The tagging scheme in §4.1 survives a split only in the **monorepo, lockstep-versioned** case, where
`g<sha8>` still names one commit that produced every image. Under polyrepo with independent service
versions a single tag is meaningless and the manifest becomes the only release identity. Choosing
the manifest now means that fork does not require redesigning the pipeline.

### 14.4 The pipeline is not the real obstacle

Two things would bite before any of the above:

- **Compose on a single VM.** It handles perhaps 5–8 services before service discovery, independent
  scaling and zero-downtime rolling deploys run out. microk8s is already on the box, and §6
  constraint 3 says no Kubernetes for the application. **That constraint is what a microservices
  move would force a revisit of** — not the deploy logic.
- **RLS-based tenant isolation.** Isolation rests on a Postgres session GUC (`app.current_company`)
  set by a custom transaction manager, plus a deliberately non-inheritable `ThreadLocal`
  `CompanyContext`. Splitting services means each establishes that GUC on its own pool and
  propagates company context across service calls — preserving fail-closed behaviour, given the
  documented failure mode is silently returning **zero rows** rather than erroring.

**The codebase is better prepared for this than the pipeline is.** Spring Modulith with
ArchUnit-enforced module boundaries and cross-module domain events is exactly the "monolith you
could split later" pattern, and it is already in place. The three pipeline gaps above are small
changes; the architectural groundwork is the expensive part and it is done.

---

## 15. A standalone authentication service

Stated 2026-08-07: authentication is to be extracted into a **standalone service, reusable by future
projects that will connect to TCMS.**

This is the first concrete microservice, so **§14 stops being future-proofing and becomes a
requirement of phase 5.** It also has consequences the deploy plan cannot absorb silently, and one
decision that must be made before any of it is designed.

### 15.1 The shared secret must become a keypair — non-negotiable

TCMS signs sessions with `TCMS_JWT_SECRET`, a **symmetric** secret (`@Size(min = 32)`). Symmetric
signing means **anyone who can verify a token can also mint one.** The moment a second project
verifies these tokens, that project can forge a TCMS session for any user in any company.

So the auth service must sign **asymmetrically** (RS256/ES256) and publish a **JWKS** endpoint;
consumers verify with the public key and hold no signing capability at all.

This also retires the entire §108 defect class — the one where `${TCMS_JWT_SECRET:?…}` was read by
Spring as a *default* and signed production's sessions with a string from this repo for a year, past
a correct comment, a real `@Size` and 1000+ green tests. **There is no shared secret left to leak or
default.** `ConfigConventionTest` stays regardless.

### 15.2 The decision that must come first: does the IdP own tenants?

| | **(a) Pure identity provider** | **(b) Identity + tenant directory** |
|---|---|---|
| Owns | Users, credentials, MFA, sessions, token issuance | The above **plus** organizations and membership |
| Token carries | `sub` and little else | `sub` **and `org_id`** |
| TCMS does | Maps `sub` → its own user record → companies, roles, project grants | Trusts `org_id` from the token |
| Reuse across projects | Shared *users*, each product models its own tenancy | Shared *tenants* — one org spans products |

**Recommend (a), and the reason is specific to this codebase.** `company_id` is not an attribute —
it is the isolation boundary, enforced in Postgres by RLS on 56 tables, and the architecture rule is
that `CompanyContext` is populated *from the authenticated session* and **never** from a request
parameter, header or path segment. Under (b) the source of truth for `company_id` moves outside the
system that enforces isolation on it, and a bug in the auth service becomes a **cross-tenant data
breach** in TCMS rather than a login failure.

Note that (a) still satisfies the rule: a **verified** claim in a signed token is the authenticated
session, not an untrusted header. What must never happen is TCMS reading a company from an
unverified claim — which is the same defect the existing rule describes, wearing new clothes.

(b) is genuinely more powerful if the future projects are meant to share *customers* rather than
just *users*. It is a much larger change and should be a deliberate product decision, not a
side-effect of extracting a service.

### 15.3 Buy, don't build — probably

"Standalone auth service" usually means **adopt an IdP**, not write one. Keycloak, Zitadel and
Authentik are the self-hostable options; Auth0 and Cognito the hosted ones — and this repo's plugin
set already carries `be-java-auth0` and `be-java-cognito` skills, so that lane was anticipated.

The argument is not effort, it is §12.1. **Login today has no rate limiting, no lockout and no
failed-attempt throttling**, on two internet-facing instances — the most urgent finding in this
document. An off-the-shelf IdP ships throttling, lockout, MFA, password reset, session revocation
and an audit trail on day one. Writing those is the actual work of an auth service, and they are
precisely what is currently missing.

Against: an IdP is another stateful service to run, back up and upgrade on a 14 GiB box that is
already crowded (§11.2 item 5), and TCMS's invitation/activation flow is bespoke and would need
mapping onto it.

### 15.4 What it does to the deploy topology

The sharpest consequence, and it does not fit the model in §11:

**A shared service belongs to no project.** §11.2 puts one rootless Docker daemon and one Unix user
per project. Auth is consumed by TCMS *and* by future projects, so it is neither. It needs its own
tier — its own user, its own daemon, its own compose stack — and every consumer reaches it over
loopback through the port registry. Treat it as a **fourth project that happens to have no UI**,
rather than as part of TCMS.

Consequences for the pipeline:

- **Its own repository and its own pipeline.** It has an independent release cadence; TCMS depends
  on a *running instance*, not on a version bundled into TCMS's release manifest.
- **The manifest (§14.3) covers one deployable set, not the estate.** Auth's version is not in
  TCMS's manifest — but "which auth service was live when this TCMS release was verified" becomes a
  real question for C2, and nothing currently records it.
- **Token claims are a contract**, exactly like `openapi.yaml`. A breaking claim change affects
  every consumer at once. Version the claims and treat JWKS rotation as a rollout, not an edit.
- **Deploy ordering.** Consumers must tolerate auth being briefly unavailable — cached JWKS makes
  verification survive an outage, but login does not. The §6 smoke test must cover the
  cross-service round trip, or it proves less than the single-service one it replaces.
- **Auth's database is another stateful service.** It should not share TCMS's Postgres; under §11.2
  item 2 it gets its own, in its own stack.

### 15.5 Sequencing

Nothing here blocks phases 1–8, and phases 1–8 should not wait for it. But **the §14.2 changes
become mandatory rather than prudent**: with a second deployable, the single `TCMS_VERSION`
variable, the hardcoded `tcms-backend-1` health check and Flyway-at-startup all stop working as
written. Build phase 5 the general way.

The order that makes sense: pipeline (1–8) → decide 15.2 and 15.3 → extract auth → multi-project
isolation (9–11), since 15.4 is the strongest argument for the shared-tier model that §11 already
proposes.

---

## 16. Open questions

1. **Required reviewers on `production`** — recommend ON (§2, Lane C). Currently off by an explicit
   2026-08-03 decision whose stated reason no longer applies under promote-by-digest.
2. **Where a red nightly surfaces** — inherited open question from
   `2026-08-06-shanks-deployment.md` §12. Now also covers a failed auto-rollback, which is strictly
   more urgent than a failed test. A push notification or an issue opened by the workflow are both
   cheap; nothing is currently chosen.
3. **The smoke-test company and token** — §6 step 4 needs a seeded company on both lab and prod plus
   a read-only API token in an Environment secret. Provisioned by `provision-company`, or a
   dedicated Ansible step? It is state that must exist on a fresh VM, which argues for Ansible.
4. **DinD memory ceiling vs. the Testcontainers suite** on a box that also serves the app. Needs
   measuring in phase 2, not guessing.
5. **Does rootless Docker hold up here?** Nesting DinD inside a rootless daemon for the ephemeral
   runners (§3.1) is the part most likely to fight back, along with cgroup v2 delegation for
   `mem_limit`. Prove it in phase 9 on a throwaway project before migrating TCMS; §3.2 is the
   fallback if it does not.
6. **Whether production's Postgres moves at all** (§11.2 item 2), or only the lab's, with
   `pg_createcluster` as the production answer. Two boxes diverging in how the database is hosted
   is a cost of its own — the deploy sequence and the backup path would differ.
7. ~~**A real domain and DNS-01 (§12.3)**~~ → **decided 2026-08-07, see §13.** A real domain is
   adopted as part of the WireGuard migration, and DNS-01 is no longer needed — ACME moves to
   Hetzner where `:80` is free. **Still open: whose domain, and the registrar.** That is now the
   only blocker on phase 12, and it blocks nothing else.

### The authentication service (§15)

Grouped because they interlock: 9 and 10 together decide whether 11 is even a question.

8. **Symmetric → asymmetric signing.** *Recorded as settled, not open* (§15.1), because there is no
   defensible alternative: symmetric signing means anyone who can verify a token can mint one, so
   the first external consumer of a TCMS token could forge a session for any user in any company.
   Listed here so it is not rediscovered as a choice. **Open sub-question:** RS256 or ES256, and
   what the JWKS rotation procedure is — rotation is a rollout across all consumers, not an edit.
9. **Does the auth IdP own tenants (§15.2)?** Recommend **no** — users only. `company_id` is the
   RLS isolation boundary; moving its source of truth outside the system that enforces it turns an
   auth-service bug into a cross-tenant data breach. **This is a product decision, not a deployment
   one**, and it must be settled before the service is designed. It hinges on whether future
   projects share *customers* or merely *users*.
10. **Buy or build the auth service (§15.3)?** Recommend **buy** — Keycloak/Zitadel/Authentik
    self-hosted, or Auth0/Cognito hosted (this repo's plugin set already carries `be-java-auth0`
    and `be-java-cognito`). The argument is not effort: throttling, lockout, MFA, password reset,
    session revocation and audit *are* the work of an auth service, and they are exactly what is
    missing today. Against: another stateful service to run, back up and upgrade on a crowded
    14 GiB box, plus mapping TCMS's bespoke invitation/activation flow onto it.
11. **How soon is the extraction?** This is a scheduling question with a real consequence, which is
    why it is listed rather than left implicit. If it is **weeks** away, the throttling gap below
    can be closed once, by the IdP. If it is **quarters** away, close it in the monolith now — two
    internet-facing instances with no lockout must not wait on an architecture change.
12. **What records which auth service version was live when a TCMS release was verified?**
    (§15.4.) Auth has an independent release cadence and is not in TCMS's release manifest, so
    Lane C's `verify-lab` check (C2) currently has no way to express "verified against auth
    build X". Nothing records it today and nothing in this plan adds it.

### Questions for external review

`docs/plans/2026-08-07-cicd-external-review-brief.md` §7 carries ten questions framed for a DevOps
engineer — runner isolation, whether build jobs belong on self-hosted runners at all, the two-image
split, host-vs-container Postgres, multi-project on a personal GitHub account, ingress, whether
auto-rollback is wise, Ansible's proportionality, the nightly's value, and box sizing.

**Deliberately not duplicated here.** They overlap this list but are worded for someone without
repo context, and two copies of a question set is how one copy goes stale. The brief is the live
version; fold any answers back into this section.

---

**Not an open question — an open gap.** §12.1: both instances are internet-facing with no rate
limit, no lockout and no failed-attempt throttling on login. It is the only item in this document
that is not CI/CD work and **should not wait for this plan**. It belongs to the backend (`identity`
module), needs a decision on what a throttled attempt returns, and interacts with §12.2 — without a
client IP the only available key is the account, which protects an individual login but does not
protect the instance.

**→ Its resolution depends on question 11.** Adopting an IdP closes this gap as a side effect, so
the only reason to fix it in the monolith is that the extraction is far enough away that waiting is
not defensible. Answer 11 and this stops being a judgement call.
