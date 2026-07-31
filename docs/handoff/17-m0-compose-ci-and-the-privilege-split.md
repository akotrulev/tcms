# §17. Backend M0 — Docker Compose, CI, and the deployment privilege split (2026-07-30)

Run through the **`devops:devops-engineer`** agent (discovery/confirmation gate → `devops/docker` +
`devops/github-actions` + `devops/environment-config`; no cloud, IaC or Kubernetes skill loaded,
because those rows were confirmed as **none**). Closes §15's next-step list item 2 — **M0 is now
complete.**

### The headline: the privilege split existed only in the test suite

`IntegrationTestBase` migrates as `tcms_owner` and connects the app as unprivileged `tcms_app`, with
that role created by a **test-only** Flyway callback whose own comment says *"the production install
guide must create an equivalent role."* No such path existed. Worse, `application.yml` gave Flyway no
credentials of its own, so it inherited the datasource and would have migrated **as `tcms_app`** — a
role that by design owns nothing and can create nothing. The first `docker compose up` would have
failed on V1's first `CREATE TABLE`, and both obvious repairs are catastrophic:

| Tempting repair | What it costs |
|---|---|
| Run the application as the schema owner | Hands it `ALTER POLICY` / `DROP POLICY` on all 56 tables — the isolation layer becomes writable by the thing it constrains. `FORCE` still holds, so **nothing looks wrong.** |
| Grant the app role `BYPASSRLS` or ownership | Voids isolation outright, silently, for every table at once. |

Fixed by giving Flyway its own `DB_MIGRATION_USER` / `DB_MIGRATION_PASSWORD` in `application.yml`
(URL by reference to `spring.datasource.url`, so a typo cannot point migration and application at
two different databases) and by making role creation a **deployment** artifact.

**This is the same family as §6 lesson 9 and §16's "a comment claiming a property is a claim":** the
test harness asserted the split, a comment said production must reproduce it, and nothing did.

### What shipped

| Path | Purpose |
|---|---|
| `backend/Dockerfile` | Multi-stage temurin 21, Boot 3.3+ layered extraction (`-Djarmode=tools`), non-root **UID 10001**, OCI labels, `HEALTHCHECK` on `/actuator/health/readiness`. **147 MB.** The CLI path is preserved deliberately — no server-only entrypoint wrapper. |
| `backend/.dockerignore` | Small context. Carries no `*.jar` rule: excluding `gradle-wrapper.jar` breaks `./gradlew` inside the build stage with an error that reads like a network fault. |
| `deploy/postgres/initdb/10-tcms-roles.sql` | **The split, as the deployment owns it.** `tcms_owner` (non-superuser schema owner, Flyway only) + `tcms_app` (DML only). Passwords via psql `\getenv`, so no credential is committed. |
| `compose.yaml` (repo root) | Local stack. Passwords are `${VAR:?}` — required, never defaulted, so the file cannot be copied to a server with a forgotten password. |
| `.env.example` · `.gitignore` | The shape is committed; the file is not. |
| `deploy/compose.prod.yaml` | The VM. Backend container → **host-native** Postgres over the Docker bridge; `127.0.0.1:8080` publish; `mem_limit`; log rotation. |
| `backend/src/main/resources/application-prod.yml` | Layer 2 of the config model: Hikari at 10 for a 4 GB box, `forward-headers-strategy: framework` (Caddy terminates TLS), graceful shutdown. **No secrets, by construction.** |
| `.github/workflows/ci.yml` | Two independent jobs — backend `./gradlew build` on `ubuntu-latest` (Testcontainers needs the runner's Docker daemon), frontend `npm ci && build && lint`. Gradle wrapper validation. No deploy lane. |
| `frontend/package.json` | `engines: node >=22.12.0`, matching CI. **Note: the local machine is on v20.20.0, below the declared floor** — npm warns, does not fail. |
| `docs/plans/2026-07-30-tcms-hetzner-deployment-runbook.md` | The corrected runbook (below). |

### The Hetzner runbook corrected five things, four of them silent

The supplied runbook is sound generically and wrong for *this* codebase in five places:

1. **Ubuntu 24.04's `postgresql` is 16; everything here is tested on 17.** Now PGDG + `postgresql-17`.
   `ltree`/`pg_trgm`/`citext` exist on 16, so it would probably work — "probably" being the thing
   §6's lessons are about.
2. **`CREATE DATABASE appdb OWNER appuser` is the single-role collapse above.** Now both roles.
3. **`/api/health` does not exist** — it is `/actuator/health/readiness`. A checklist item that 404s
   teaches you to ignore the checklist.
4. **`SPRING_DATASOURCE_*` env names bind, but have no `DB_MIGRATION_*` equivalent** — using them
   silently gives Flyway the application's credentials, i.e. defect 2 by another route.
5. **`-Xmx1g` on a systemd unit** — the app runs containerized now, so heap is sized from the
   container limit via `MaxRAMPercentage`, with box tuning in `JDK_JAVA_OPTIONS`.

Decisions taken with it: **app containerized, Postgres and Caddy host-native** (the DB keeps host page
cache, apt security patches and plain `pg_dump` backups on a 4 GB box); **~~SPA served from the boot
jar (§7)~~ → superseded — Caddy serves `frontend/dist`**, keeping the Gradle and npm builds
independent as `settings.gradle.kts` already argued for.

### Gate evidence — run, not just built

`./gradlew build` **124/124 green** (test XMLs timestamped after the `application.yml` edit, so the
task genuinely re-ran) · `npm ci && npm run build && npm run lint` clean, 0 warnings · both compose
files parse · workflow carries every pinned action, no `@main`/`@latest`, no tabs.

`docker compose up` came up healthy first try, and the properties were then **proved against the live
stack rather than assumed** — this is the first time the isolation model has run outside the test
harness, and against a **non-superuser owner**, which is *stricter* than `IntegrationTestBase` (where
the owner is a superuser):

| Assertion | Result |
|---|---|
| All five migrations' `installed_by` | `tcms_owner` |
| `ltree` / `pg_trgm` / `citext` installed by a non-superuser | yes — they are *trusted* extensions (PG13+), which is what makes the whole split viable |
| `tcms_owner` / `tcms_app` `rolsuper`, `rolbypassrls` | `f`, `f` for both |
| Objects owned by `tcms_app` | **0** |
| Schema | 65 tables (64 + Flyway), 56 policies, 56 forced |
| Pool's actual role (`pg_stat_activity`) | `tcms_app` |
| Trap 2 — no context | **0 rows** |
| Correct company / foreign company | **1** / **0** |
| V4's reset-GUC case on a warm connection | `current_setting(…) = ''` is **true**, query returns 0 — not the pre-V4 error |
| `tcms_app` disabling RLS / dropping a policy / `TRUNCATE` | `must be owner of table` / `does not exist` / `permission denied` |
| Container user | `uid=10001(app)` |
| `provision-company` from the image | exit **2** on usage error, **0** on success, idempotent re-run reports `existed` for all four parts |

### Four things worth not re-learning

1. **A comment saying "the deployment must do X" is not a deployment doing X.** The test callback has
   said so since M0. Compose is the first thing that ever had to, and it found that `application.yml`
   made it impossible. Grep the repo for "the production install must" before trusting any of them.
2. **`docker images` is not the image size.** It reported **545 MB** for a 147 MB image — it counts
   the multi-platform manifest list and attestation artifacts. `docker image inspect --format
   '{{.Size}}'` is the arbiter. This is §6 lesson 8 (measure the bundle change) in a new costume, and
   it nearly bought a needless switch to an Alpine base.
3. **Ubuntu 24.04 base images already have a user at UID/GID 1000**, so the conventional
   `groupadd --gid 1000 app` fails outright. Failing is the *good* case: silently reusing that account
   would run the application as an identity the base image controls. UID 10001.
4. **The guidance said temurin ships no wget; measured, `21-jre-noble` does.** Rather than
   `apt-get install` it (an extra layer and an unpinned package pulled at build time), the Dockerfile
   **asserts** its presence and fails the build if a future base tag drops it. An install would have
   hidden exactly the change the guidance was warning about.

### Deliberately not done

- **No image CVE scanning.** Trivy blocking on criticals belongs in CI, and the gate *policy* is
  `qa-security`'s (`qa-security-image-infra-scan`), not this lane's. The image build it scans is now
  wired and waiting.
- **No deploy lane in CI**, by the confirmed scope. It needs an SSH deploy key, a GitHub Environment
  with required reviewers, and a rollback story — and the box does not exist yet. `deploy.sh` in the
  runbook §8 is the manual path to walk first.
- **No registry.** `docker save | ssh docker load`. Switching `image:` to GHCR is a one-line change.
- **Base images pinned by tag, not digest.** `eclipse-temurin:21-jre-noble` and `postgres:17` float
  their patch level. For `postgres:17` that is deliberate and load-bearing — it must match the tag
  `IntegrationTestBase` boots, and pinning a patch here would let local and CI drift apart.
- **Single instance, so a restart is visible downtime**, and the SPA production build has never been
  verified with MSW off against a real backend. Both are flagged in the runbook's §13.

### ⚠ The one thing that blocks a usable deployment

`provision-company` leaves the first Company Admin **`INVITED`**, and the invitation/activation path
(table, token, email) **still does not exist** (§15's next-step item 4). The runbook therefore stands
up infrastructure that has **no usable login**. That is now the first thing M0.5 needs, and it is
worth more than any remaining M0 polish.

### Next on the backend, in order

1. **The invitation/activation path.** Nothing is reachable without it.
2. The evaluator's cache, the `OWN` qualifier, an RLS-disabled lane for §13's surviving mutation.
3. When the box exists: walk `deploy.sh` by hand once, then move it into `deploy.yml` behind a
   GitHub Environment. Add the Trivy gate with `qa-security`.
