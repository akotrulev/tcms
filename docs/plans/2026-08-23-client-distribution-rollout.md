# Rolling out `/downloads` — the operator steps §251 could not take

**Written 2026-08-23, immediately after §251 landed. Nothing here has been run.**

§251 built the delivery path for `@tcms/cli` and `@tcms/mcp` and stopped at the boundary of what a
repository can do. Three things remain, and all three are on a box rather than in a commit:

1. the lab has to actually deploy the change, and `/downloads` has to be observed working;
2. the **hosted** MCP endpoint is behind a compose profile whose state is an operator fact;
3. **production's Caddyfile is not in this repository** — its `handle /downloads` block has to be
   added by hand, and until it is, `/downloads` on that box answers the SPA's `index.html` with a
   200 and npm reports an invalid gzip header.

The design and its reasoning are in `docs/specs/client-distribution.md`. This file is the ordered
procedure. **Do stage 1 before stage 3**: production is the box with real users on it, and the lab
is where a mistake costs nothing.

---

## Stage 0 — the release that carries it

The commit is on `master`, so `ci.yml` already dispatched a lab deploy. Watch it rather than
assuming it:

```bash
gh run list --limit 5
gh run watch <id>            # the `ci` run for the §251 commit
```

**What must be green, and what each one proves:**

| Job | Proves |
|---|---|
| `packages` | the pack-and-verify passed — both tarballs install **both ways** and their binaries run |
| `CLI + MCP tarballs — pack and install-verify` | the same, at the release tag, plus the assertion that nothing outside `dist/` is in a tarball |
| `deliver master to the lab` | `/srv/tcms/downloads/` now holds four tarballs, `SHA256SUMS` and `index.html` |

If `packs` is red, read the failing step's message before anything else — the script's failures name
which of §251's four defects came back, and none of them is fixed on the box.

---

## Stage 1 — verify the lab, unauthenticated

These are rows `§251 D-1`, `D-2` and `D-3` of `docs/verification/frontend-browser-checks.md`.
`<host>` is the lab's name — `clearstep.TAILNET.ts.net`.

```bash
# 1a. the page renders, and names THIS host rather than the placeholder
open https://<host>/downloads/

# 1b. a real tarball, and a missing one
curl -sI https://<host>/downloads/tcms-cli-latest.tgz | grep -Ei 'HTTP/|content-type|cache-control'
curl -sI https://<host>/downloads/nonexistent.tgz     | grep -Ei 'HTTP/|content-type'

# 1c. the digests
curl -s https://<host>/downloads/SHA256SUMS
curl -sO https://<host>/downloads/tcms-cli-latest.tgz && shasum -a 256 tcms-cli-latest.tgz
```

**Expected:** `200` + `application/gzip` + `Cache-Control: no-cache` for the first; **`404` for the
second.** A `200 text/html` there is the SPA catch-all answering, which means the Caddy block is
absent or misspelled — and it is the failure that misleads, because the download "works" and npm
fails later with a gzip error.

If 1b is wrong on the lab, the Caddyfile on the box is stale. `install-caddy.sh` copies
`deploy/shanks/Caddyfile` to `/etc/caddy/Caddyfile`; the deploy does **not**:

```bash
ssh shanks
sudo cp /path/to/checkout/deploy/shanks/Caddyfile /etc/caddy/Caddyfile
sudo caddy validate --config /etc/caddy/Caddyfile      # never reload an invalid config
sudo systemctl reload caddy
```

---

## Stage 2 — install both tools from a machine with no checkout

Row `§251 D-4`. **The point of this stage is the machine, not the command** — running it in this
repository's tree proves nothing, because every failure §251 fixed is a failure of the *installed*
copy. Use another laptop, a fresh container, anything without the repo:

```bash
docker run --rm -it node:22-alpine sh
# inside:
npm install -g https://<host>/downloads/tcms-cli-latest.tgz
export TCMS_URL=https://<host> TCMS_TOKEN=<a token issued in the UI>
tcms whoami
```

**Expected:** the token's user and what it can reach. **Silence with exit code 0 is §251's fourth
defect returning** — that is exactly what a symlinked bin did before `isProcessEntryPoint`, and it
is indistinguishable from a command that succeeded and had nothing to say. Check `echo $?` and
insist on output.

Then prove the vendoring claim, which is what makes this installable behind a corporate proxy:

```bash
npm cache clean --force
npm install -g --offline https://<host>/downloads/tcms-mcp-latest.tgz   # must still succeed
tcms-mcp --help
```

---

## Stage 3 — turn on the hosted MCP endpoint

Row `§251 D-5`, second half. The image has shipped at every version since §142; the container is
inert until the box's env file names the profile. **Check before setting** — it may already be on:

```bash
ssh shanks
sudo grep -c COMPOSE_PROFILES /etc/tcms/tcms.env || true
sudo docker inspect tcms-mcp-1 >/dev/null 2>&1 && echo "already running" || echo "not running"
```

To turn it on:

```bash
sudo sh -c 'echo COMPOSE_PROFILES=mcp >> /etc/tcms/tcms.env'
cd /srv/tcms && sudo docker compose --env-file /etc/tcms/tcms.env up -d
sudo docker inspect -f '{{.State.Health.Status}}' tcms-mcp-1     # want: healthy
```

The container's healthcheck asserts a **405** on its configured path, which is the only
credential-free answer that proves it is serving the path Caddy proxies to. Confirm the same from
outside — `405`, not `200`:

```bash
curl -sI https://<host>/mcp | head -1
```

A `200` here means Caddy's `/mcp` block is missing and the SPA answered: the same trap as 1b, and
`deploy/shanks/Caddyfile` warns about it in those words.

**Turning it off again means naming the service.** Removing the line stops nothing — compose
excludes profile-disabled services from orphan handling, so the container keeps serving an
internet-facing route while the line authorising it is gone, and the next deploy reports success:

```bash
cd /srv/tcms && sudo docker compose --env-file /etc/tcms/tcms.env rm -sf mcp
```

Then point a real MCP host at `https://<host>/mcp` with an API token as the bearer, and at a local
`tcms-mcp` with the same token in `TCMS_URL`/`TCMS_TOKEN`. **Both must list the same tools** — the
list is built per session from the credential (§140), so a difference between the transports is a
session-construction defect, not a transport one.

---

## Stage 4 — production

Only after stages 1–3 are green on the lab.

### 4a. Add the Caddy block by hand

Production's Caddy config lives on the box (runbook §9) and no deploy touches it. Copy the block
from `deploy/shanks/Caddyfile` — everything from the `# THE DOWNLOADS` comment through the closing
brace of `handle /downloads /downloads/*` — into the site block, **beside the existing
`handle /api/*`**, keeping the comment: it records the two traps that block prevents.

```bash
ssh tcms
sudo cp /etc/caddy/Caddyfile /etc/caddy/Caddyfile.bak-$(date +%Y%m%d)
sudo vi /etc/caddy/Caddyfile
sudo caddy validate --config /etc/caddy/Caddyfile
sudo systemctl reload caddy
```

`/srv/tcms/downloads` does not need creating — the deploy's rsync makes it — but it must not be
inside `/srv/tcms/web`, which is synced with `--delete`.

### 4b. Dispatch a release

Production does not auto-deploy; that is deliberate (`deploy.yml`'s header: a release keeps a human
at the other end).

```bash
gh workflow run deploy.yml -f version=<semver> -f target=production
gh run watch <id>
```

The `deploy` job now waits on `packs`, so a tarball that fails install-verify stops the release
before anything reaches the box.

### 4c. Repeat stages 1 and 2 against production

Same commands, `<host>` changed. **Stage 2 in particular** — it is the only check that speaks to
what a user actually experiences, and production is the instance they are on.

---

## Stage 5 — tell users

Give them the URL of their **own** instance's page and nothing else; it fills in its own host and
carries the current version:

> Install the TCMS command line client and MCP server from `https://<your-host>/downloads/`.
> Issue an API token first: **Company → API tokens → Issue token**. The secret is shown once.

The page names the hosted MCP endpoint too, so an agent user needs to install nothing at all.

---

## If something is wrong

| Symptom | Cause | Fix |
|---|---|---|
| `npm ERR! invalid gzip header` | `/downloads` served by the SPA catch-all — the response is HTML | the Caddy block (1b, 4a). Confirm with `curl -sI` and read the **content-type** |
| `404` on `/downloads/tcms-cli-latest.tgz` after a deploy that was green | the tarballs landed under the web root and the SPA's `rsync --delete` erased them | they belong in `/srv/tcms/downloads`, a **sibling**. Check the deploy job's "Ship the downloads" step ran |
| `tcms` prints nothing, exit 0 | §251 defect 4 — the entry guard | the installed copy predates `isProcessEntryPoint`. Reinstall from `-latest`; if it persists, `packs` shipped a stale build |
| `ERR_MODULE_NOT_FOUND` on first run | a dependency is not in the tarball | the pack script's vendoring. This cannot happen through CI — `--verify` runs both install shapes — so suspect a hand-built tarball |
| MCP host reports a protocol/parse error | `/mcp` answered `200 text/html` | the `/mcp` Caddy block, or the container is not running (stage 3) |
| `docker compose` says nothing about `mcp` | the profile is off | `COMPOSE_PROFILES=mcp` (stage 3). A green deploy proves nothing about this |

**Rolling back the tools alone is not a thing.** They are packed per release, so an older CLI means
an older release: set `TCMS_VERSION` back and redeploy, exactly as for the backend. The version-
pinned tarballs on the box are the previous release's only until the next deploy's `--delete`
removes them, so a user who needs to stay on an old CLI should keep their own copy.
