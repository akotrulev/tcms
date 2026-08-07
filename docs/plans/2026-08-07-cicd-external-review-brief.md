# TCMS — CI/CD review brief

## 1. What the system is

A self-hosted **test case management** product. One deployable serving multiple client companies
from one instance; companies are provisioned by an operator CLI, not by self-service signup.

| | |
|---|---|
| Backend | Java 21, Spring Boot 3.5.6, Gradle, Spring Modulith monolith |
| Frontend | React 19, Vite 8, TypeScript, MUI v7 — a static SPA, no SSR |
| Database | PostgreSQL 17, **Row-Level Security** enforced with `FORCE ROW LEVEL SECURITY` on 56 tables; schema owned by Flyway |
| Tests | ~1000+ backend integration tests, each booting a **real Postgres 17 via Testcontainers** (no H2 lane — RLS, `ltree` and `citext` have no H2 equivalent). ~954 frontend Vitest tests |
| Team | **One developer.** No org, no reviewers, no on-call |
| Repo | **Private**, on a GitHub **personal (User) account** — this matters, see §7.5 |

Two facts that constrain everything downstream:

- **Tenant isolation is enforced in Postgres**, not only in application code. The app connects as an
  unprivileged role (`tcms_app`) that owns nothing; Flyway migrates as a separate owner role
  (`tcms_owner`). Neither is a superuser. Collapsing them is the tempting simplification and it
  hands the running application `DROP POLICY` on every table.
- **Flyway is forward-only.** Re-tagging an image does not roll back a schema change. Any rollback
  story has to account for this.

---

## 2. The two environments

| | **production** | **lab** |
|---|---|---|
| Host | Hetzner CX23 VM, 4 GB, public IP | `shanks` — a **Lenovo laptop**, Ubuntu 26.04, 14 GiB, on a home LAN |
| Reachability | Public IP, real DNS | **Behind NAT, dynamic IP, possibly CGNAT.** No inbound connections possible |
| Ingress | Caddy on the host, TLS via **ACME** | Caddy on the host, TLS via `tailscale cert`, published with **Tailscale Funnel** |
| Deploy trigger | Manual `workflow_dispatch` | **Automatic on every merge to `master`** (landed today) |
| Deploy mechanism | GitHub Actions → **SSH** into the box | GitHub Actions job **runs on the box itself** |

**Both must be reachable from the public internet.** That is a product requirement, not a
convenience — lab included.

### What else lives on the lab box

This is not a dedicated CI host. `shanks` simultaneously runs:

- the lab instance of this app (Postgres + backend container + Caddy)
- **microk8s with Calico** (unrelated, pre-existing)
- **Nextcloud**, whose snap Apache **owns port 80** — so our Caddy cannot use HTTP-01 ACME and
  disables its HTTP→HTTPS redirect to avoid the collision
- the **nightly test suite** (Testcontainers, wants several GB in bursts)
- the **only self-hosted GitHub Actions runner**

---

## 3. How deployment works today

### 3.1 Runtime shape (identical on both hosts)

```
              :443
    internet ──────► Caddy (host-native, systemd)
                       ├── /api/*      → reverse_proxy 127.0.0.1:8080
                       ├── /actuator/* → 404 (deliberately not exposed)
                       └── /*          → static files from /srv/tcms/web
                                          (SPA fallback: try_files {path} /index.html)

    docker compose ──► backend container (single service)
                         └── connects OUT to Postgres on the HOST
                             via a pinned bridge gateway 172.28.0.1
```

Notable and deliberate:

- **Postgres runs on the host, not in compose.** Stated reason: it is "the one piece of state that
  must survive every redeploy of everything else."
- The backend publishes **`127.0.0.1:8080` only**. Binding `0.0.0.0` would have Docker write
  iptables rules that bypass `ufw` entirely.
- The container runs as **UID 10001, non-root**, from a multi-stage image (`temurin:21-jre-noble`),
  layer-extracted Spring Boot jar, with a Docker `HEALTHCHECK` and `mem_limit`.
- Because Postgres is on the host, `pg_hba.conf` grants access to whole Docker subnets:
  `172.28.0.0/16` (our pinned compose network) **and `172.17.0.0/16`, the default docker0 bridge**.
  Password-gated (scram-sha-256), but network-reachable by any container on the box.

### 3.2 Pipelines

Three workflows:

| Workflow | Trigger | What it does |
|---|---|---|
| `ci.yml` | push to master, PR | Backend `./gradlew build -x test` (**compile only — tests deliberately skipped**), frontend build + lint + Vitest. **Since today:** on master push, also builds and auto-deploys to lab |
| `tests.yml` | nightly cron | The full backend suite on Linux — the gate `ci.yml` gave up |
| `deploy.yml` | `workflow_dispatch` (+ now `workflow_call`) | Builds backend image → GHCR, builds SPA → **GitHub Actions artifact**, then deploys to `lab`, `production`, or `both` |

**The backend tests were removed from CI on 2026-08-02** to cut push feedback from ~327s to ~60s.
The consequence was accepted and written down: for a period, nothing anywhere ran them except a
developer's Mac — and a macOS machine provably cannot observe some defects here (a real one:
Postgres round-trips `Instant` at microsecond precision, macOS's `Clock.systemUTC()` already
truncates to microseconds, so the assertion passes locally and fails on Linux; master was red for
seven consecutive runs on it).

### 3.3 Artifacts and versioning

- Backend → **GHCR image**, tagged with a hand-typed semver (`0.1.3`) or, for lab, `g<sha8>`.
- SPA → **GitHub Actions run artifact, 30-day retention**, `rsync`'d to `/srv/tcms/web/`.
- The deployed version is a line in `/etc/tcms/tcms.env` (`TCMS_VERSION=…`), `sed`-edited in place
  by the deploy job, then `docker compose pull && up -d`.
- Deploy waits on the **container's own healthcheck**, not a fixed sleep, because Flyway may have
  migrations to apply and "started" is not "serving".

### 3.4 The runner

**One** self-hosted runner, on `shanks`, registered to this repository:

- Runs as a dedicated user `ghrunner`, in the `docker` group
- `/etc/sudoers.d/tcms-deploy` grants `ghrunner` **passwordless `sudo` for `docker`, `rsync`,
  `mv`, `sed`** — `sudo docker` is root-equivalent
- Labels: `self-hosted, linux, x64, tcms-lab`
- **Not ephemeral.** The workspace is not cleaned between jobs
- **`pull_request` targets it**, which the installer's own documentation records as a knowingly
  accepted rule violation (justified as: private repo, no forks, so PR authors can already push)

### 3.5 Host provisioning

Three hand-written bash scripts, run over SSH:

- `provision-host.sh` — **613 lines.** Docker, PostgreSQL 17 from PGDG, Postgres sizing, the two DB
  roles, the container→host-Postgres network bridge, `/srv/tcms` + `/etc/tcms` layout, **secret
  generation**, the sudoers rule
- `install-caddy.sh` — **178 lines.** Caddy, `tailscale cert`, a weekly renewal timer, Tailscale
  Funnel
- `install-runner.sh` — **193 lines.** The runner

They are idempotent and unusually well-commented (each carries the failure it was written to
prevent). They are still imperative bash, and re-pointing at a new VM means running them and
trusting them.

---

## 4. What we already believe is wrong

Found while reviewing; we would like these confirmed or corrected.

**4.1 The production version number is decorative.** `deploy.yml`'s deploy job depends on build jobs
that build **from the checkout the dispatch resolved** — i.e. the tip of master *at dispatch time*.
So `-f version=0.1.3` does not deploy 0.1.3; it deploys whatever master is now and labels it 0.1.3.
Production has therefore never run an artifact that lab verified.

**4.2 The SPA cannot be promoted or rolled back past 30 days**, because it is a run artifact while
the backend is an immutable registry image.

**4.3 The runner is the box.** Any job — including PR jobs — has root on the host serving the
application, in a workspace shared between jobs. It can read `/etc/tcms/tcms.env` (DB passwords,
JWT signing secret) and `pg_dump` the database.

**4.4 Login has no rate limiting, lockout, or failed-attempt throttling** — verified by grep across
the backend. Both instances are internet-facing. Until recently the lab was tailnet-only, so
membership *was* the access control; Funnel removed that and nothing replaced it. Production never
had it.

**4.5 Tailscale Funnel is configured as raw TCP passthrough**
(`tailscale funnel --tcp 443 tcp://127.0.0.1:443`), so tailscaled terminates the public TCP
connection and dials loopback. We believe this means **Caddy sees `127.0.0.1` as the client IP for
every public request** — which would make IP-based throttling impossible and access logs useless
for distinguishing an attack. *We have not yet confirmed this by observation.* **Please sanity-check
the reasoning.**

---

## 5. What we plan to change

Eleven phases, each independently shippable. Phases 1–8 are the pipeline; 9–11 are multi-project
isolation and are not required for the pipeline to be correct.

| # | Change |
|---|---|
| 1 | Commit the in-flight lab-CD and Funnel work |
| 2 | **Ephemeral containerised runners** — `actions/runner --ephemeral`, fresh workspace per job, no host Docker socket, **DinD sidecar** so Testcontainers still works |
| 3 | **Convert the bash provisioning to Ansible**, with `lab`/`prod` inventories, so moving to a new VM is one command |
| 4 | Move build jobs onto the isolated runners; **restore the full backend suite to the PR gate**; persist the Gradle cache as a named volume so ephemeral containers don't start cold |
| 5 | **Promote by digest**: master builds `g<sha8>` once; production dispatch re-deploys an existing, lab-verified digest and never rebuilds. SPA becomes a **second GHCR image** so both halves promote identically. Semver becomes a tag alias added to an existing manifest |
| 6 | **Post-deploy smoke test** against the real public origin (health, SPA build id, an unauthenticated read, an authenticated read via a scoped API token), with **auto-rollback** to the previous digest on failure |
| 7 | Security gates: gitleaks, actionlint, dependency SCA, **Trivy on the built image** (gate on *fixable* HIGH/CRITICAL only), plus DAST against the lab origin |
| 8 | Nightly repurposed to re-scan the **running** images for newly published CVEs; `ansible --check` on a schedule for **drift detection** |
| 9 | **One rootless Docker daemon per project**, one Unix user each — retires the `sudo docker` grant entirely |
| 10 | Caddy becomes a shared ingress with one config file per project; Ansible's app role parameterised by project |
| 11 | **Postgres moves into each project's compose stack** on a named volume — deletes the shared cluster, the `/16` `pg_hba` grants, and ~100 lines of host-bridge machinery that exists only because a container must reach a host database |

Also planned for production releases: **`pg_dump` before every deploy**, and a **migration guard**
that detects when a release contains new Flyway migrations and says so loudly, since re-tagging
cannot undo them.

---

## 6. Constraints — please design within these

Not up for debate; stated so review time isn't spent on them.

1. **Budget is effectively zero.** Hardware is already paid for. No managed CI, no paid SaaS.
2. **One developer.** Anything requiring a second reviewer or a rota won't happen.
3. **No Kubernetes for the application.** microk8s exists on the box for unrelated reasons. The app
   is `docker compose` on a VM and we want it to stay that way.
4. **No H2 / in-memory test lane.** Tests must run against real Postgres.
5. **Flyway is forward-only.**
6. **`master` is not branch-protected**, by explicit decision. With one developer, the `needs:`
   chain in the workflow is the gate. Revisit when a second person gets push access.
7. **The repo must stay private** — it is the load-bearing assumption behind self-hosted runners.
8. Both environments must be **publicly reachable**.

---

## 7. What we want you to validate

### 7.1 Is the runner isolation plan sound, or fantasy?

Phase 2 + 9 propose **DinD nested inside a rootless Docker daemon**, so that build jobs (which run
Testcontainers, i.e. arbitrary container workloads) cannot touch the host or other projects.

- Does that actually work, or does rootless + nested DinD + cgroup v2 delegation for memory limits
  fall apart in practice?
- Testcontainers specifically — any known sharp edges under rootless?
- Is there a materially better pattern we're missing for "untrusted-ish CI on a box that also runs
  the thing being deployed"?

### 7.2 Should build jobs be on self-hosted runners at all?

The **only** technical reason we have a self-hosted runner is that `shanks` is behind NAT and
cannot accept inbound SSH — so the deploy must be pull-based. Build and test have no such
requirement; they're on it because the hardware is free.

Is the right answer simply: **GitHub-hosted for build/test, self-hosted for deploy only** — and
skip phases 2, 4 and 9 entirely? What would you weigh?

### 7.3 Promote-by-digest — is the two-image split right?

We plan `tcms-backend:g<sha8>` **and** `tcms-web:g<sha8>` (a `scratch` image containing only the
built SPA, extracted on the box with `docker create` + `docker cp`), so both halves of a release
promote and roll back identically.

Alternatives we see: bake the SPA into the backend image and serve it from Spring; or keep Caddy
serving from disk but push the SPA as an OCI artifact rather than an image. Which would you pick?

### 7.4 Host Postgres → containerised Postgres: worth it?

Phase 11 is the highest-risk item. Moving it kills a whole class of problem (a shared cluster
reachable from any container on the box, plus ~100 lines of `listen_addresses` / `ip_nonlocal_bind`
/ `ufw` bridge machinery). But it needs a real data migration on a live production database.

- Worth doing, or is `pg_createcluster` (a separate host cluster per project, own port) the right
  amount of change?
- If we do move it — anything you'd insist on beyond "rehearse on lab, verify a `pg_dump` restore"?
- Is a named Docker volume an acceptable home for a production database, in your view?

### 7.5 Multi-project on one box, given a personal GitHub account

The box will host other projects. We've established:

- **No runner groups** — those are an org feature; this is a User account. So there is no way to
  restrict which workflows may target a given runner.
- **Labels are not a security boundary** — any workflow in a repo can target any label.
- Runners **are** repo-scoped, so cross-*repo* job theft isn't possible.

Given that, is per-project OS-level separation (own user, own rootless daemon, own ephemeral
runners) sufficient in your judgement? Or is the honest answer "move to an organisation" / "don't
co-locate projects"?

### 7.6 Ingress for multiple public projects

Tailscale Funnel serves **the node's own hostname only**, on **ports 443 / 8443 / 10000**, with no
virtual hosts. A second public project on the same box therefore gets `:8443` or path-based
routing — and path-based collides with our SPA's `try_files {path} /index.html` catch-all.

Our plan: a real domain, Caddy ACME vhosts, one site file per project, using **DNS-01** (because
Nextcloud owns `:80`, so HTTP-01 is unavailable). That would also restore the real client IP,
fixing 4.5.

Is that the right call, or would you keep Funnel and solve it differently?

### 7.7 Is auto-rollback a good idea here?

Phase 6 auto-rolls-back to the previous image digest when the post-deploy smoke test fails. The
counter-argument we're aware of is that roll-forward is usually preferred, and that with a
**forward-only Flyway schema** an automatic image rollback can leave the code older than the schema
it's pointed at.

Should we do it, gate it on "this release contained no migrations", or drop it for alerting only?

### 7.8 Is Ansible the right tool for a two-host fleet?

Two hosts, one developer, no existing config management. Is Ansible proportionate, or is a
well-structured idempotent shell setup (what we have) genuinely fine and this is ceremony? If
Ansible, anything you'd insist on for handling **secrets that must not be regenerated on re-run**?
(`/etc/tcms/tcms.env` holds generated DB passwords and a JWT signing secret; rewriting it rotates
every password and invalidates every outstanding user invitation link.)

### 7.9 Does the nightly suite still earn its keep?

If phase 4 puts the full backend suite in the PR gate, the nightly becomes a repeat. We plan to
keep it for flake detection, environment drift (a kernel or Docker update no code change would
reveal), and CVE re-scanning of running images. Agree, or cut it?

### 7.10 Sizing — is this box overcommitted?

14 GiB, shared between: the lab app + Postgres, microk8s/Calico, Nextcloud, the Testcontainers
suite (several GB in bursts), and — under the plan — ephemeral CI runners and possibly another
project's E2E browser tests.

What would you cap, and how? We currently have `mem_limit` on the app container and nothing else.

---

## 8. Also worth your eye, if you have time

- **4.4 (no login throttling) is our most urgent finding** and is application work, not CI/CD. Any
  view on where it belongs given 4.5 — if we genuinely have no client IP, per-account throttling
  protects an individual account but not the instance.
- **Secrets today live in `/etc/tcms/tcms.env`** (root-owned, `0640`) and in GitHub Environment
  secrets (SSH deploy key, known_hosts, host address). No secret manager. Proportionate?
- **Ephemeral runners require a long-lived GitHub credential on the box** to mint registration
  tokens (they expire in ~1 hour). We plan a GitHub App rather than a PAT. Agree?
- **No alerting of any kind.** A failed nightly emails one person; a failed auto-rollback would
  notify nobody. We know; we haven't chosen a mechanism.
