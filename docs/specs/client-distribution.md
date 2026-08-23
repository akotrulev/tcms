# How `@tcms/cli` and `@tcms/mcp` reach a user

**Decided 2026-08-23.** The two tools a person uses from outside the browser are served **by the
instance they talk to**, at `/downloads`, as npm tarballs built by the same release that built the
backend image and the SPA. Nothing is published to a public registry.

This file is the authority on that. `deploy/shanks/Caddyfile`, `deploy/deploy.sh`,
`.github/workflows/deploy.yml` and `packages/scripts/pack-release.mjs` all point here.

---

## For a user

Everything below needs only a URL and an API token. **Issue the token in the web UI** — the rail's
**Company → API tokens**, then *Issue token*; the secret is shown once. Both tools read
`TCMS_URL` and `TCMS_TOKEN` from the environment: there is no config file and no keychain (§137).

```bash
export TCMS_URL=https://tcms.example.com
export TCMS_TOKEN=<the token you were shown once>
```

### The CLI

```bash
npm install -g https://tcms.example.com/downloads/tcms-cli-latest.tgz
tcms help
```

### The MCP server, hosted — nothing to install

The instance serves it over Streamable HTTP. Point an MCP host at the URL and send the API token as
the bearer:

```
https://tcms.example.com/mcp
```

The server holds no credential of its own; the whole tool list and the instructions are built **per
session** from the caller's token, so what a session can see is what that token can reach (§140).
This is the shape to prefer — it is upgraded by the operator, with the instance, and cannot drift
from the API it adapts.

### The MCP server, local over stdio

For an agent that runs on the user's own machine:

```bash
npm install -g https://tcms.example.com/downloads/tcms-mcp-latest.tgz
```

```json
{
  "mcpServers": {
    "tcms": {
      "command": "tcms-mcp",
      "env": { "TCMS_URL": "https://tcms.example.com", "TCMS_TOKEN": "…" }
    }
  }
}
```

### Which copy to install

**The one from the instance you use.** Both tools are adapters over that instance's `openapi.yaml`,
and the tarball is built by the same release as the backend it sits beside — so a copy taken from
another instance is a contract nobody has run. `…-latest.tgz` always means "what this instance is
running now"; `…-<version>.tgz` pins a build. `/downloads/SHA256SUMS` covers both.

The tarballs **vendor every dependency**, so the install needs no registry access at all — it works
behind a proxy that blocks npmjs.org, and offline with `--offline`.

---

## For an operator

| Path on the box | What it is |
|---|---|
| `/srv/tcms/downloads/` | what `/downloads` serves. **A sibling of `/srv/tcms/web`, never inside it** |
| `/srv/tcms/web/` | the SPA, synced with `rsync --delete` on every release |

Both deploy paths ship the directory: `deploy.yml`'s `deploy` and `lab` jobs, and `deploy.sh` for
break-glass. `provision-host.sh` creates it on a fresh box. Caddy needs the `handle /downloads`
block in `deploy/shanks/Caddyfile` — **the production box's Caddyfile is not in this repository, so
that block has to be added there by hand** (runbook §9).

**The hosted MCP endpoint is behind a compose profile and is OFF unless the box's env file turns it
on.** Nothing in a deploy starts it:

```bash
# on the box, once
sudo sh -c 'echo COMPOSE_PROFILES=mcp >> /etc/tcms/tcms.env'
cd /srv/tcms && sudo docker compose --env-file /etc/tcms/tcms.env up -d
```

Turning it **off** again means naming the service — removing the line stops nothing, because compose
excludes profile-disabled services from orphan handling:

```bash
docker compose --env-file /etc/tcms/tcms.env rm -sf mcp
```

`deploy/compose.prod.yaml`'s `mcp:` service carries the full reasoning, and the shanks plan §14 has
the complete off procedure including the Caddy half.

### The downloads are unauthenticated

npm cannot present a credential to a tarball URL, so gating `/downloads` would mean users could not
install. On a box behind Tailscale Funnel that means the tarballs are readable from the internet.
They contain the compiled client, CLI and MCP server — no secret, no server code, nothing an
authenticated user could not already read — and `deploy.yml`'s `packs` job **asserts** that nothing
outside `dist/` ever gets into one. If that trade is not acceptable on some future host, the answer
is a separate authenticated origin for `/downloads`, not a partial gate: npm will not send a header.

---

## Why not a registry

| Channel | Why not |
|---|---|
| **public npm** | The plans assumed it — `npx @tcms/cli` in `docs/plans/2026-08-07-mcp-server-architecture-decision.md` §3 and the WIP plan §4 — and it was never done. It would publish the client, CLI and MCP source publicly and permanently, and needs the `@tcms` scope plus a license change from `UNLICENSED`. Neither is required by anything a user needs. |
| **GitHub Packages (npm)** | Requires a GitHub credential to install **even for public packages**. The users this exists for have no GitHub account for this repository at all. |
| **GHCR images** | Already how `@tcms/mcp` is *deployed*, and right for that. Wrong for a CLI: it makes Docker a prerequisite and turns reading and writing local files into a bind-mount problem. |

Serving from the instance also gets the version pairing for free, which every registry option would
have to reconstruct: the CLI you can download is by construction the one that matches the API you
downloaded it from.

---

## What the packaging has to do, and why each part exists

`packages/scripts/pack-release.mjs` builds the tarballs. Its header carries the detail; these are
the failures it exists to prevent, **every one of which packs green**:

1. **`@tcms/client` is a workspace member on no registry.** A plain `npm pack -w @tcms/cli` emits a
   manifest depending on `@tcms/client@0.1.0`, which resolves against npmjs.org and dies with E404
   on a scope nobody owns. The client is bundled instead.
2. **npm does not install a bundled dependency's own dependencies.** Bundling only the client
   produces a tarball whose `openapi-fetch` directory is created **empty** under `npm install -g`,
   and the binary dies with `ERR_MODULE_NOT_FOUND` on first run. The whole closure is vendored.
3. **`npm install -g` and `npm install` lay the tree out differently**, and a tarball can satisfy
   the second while failing the first — which is the one users are told to run. The script's
   `--verify` step installs **both ways** and runs the binary each time. It must stay on.
4. **The entry guard was wrong for a symlinked bin.** `import.meta.url === \`file://${process.argv[1]}\``
   compares a resolved path against an unresolved one, and `npm install -g` installs the bin as a
   symlink — so `tcms help` ran nothing, printed nothing, and **exited 0**. Fixed 2026-08-23 by
   `isProcessEntryPoint` in `@tcms/client`, shared by both binaries.
5. **`/srv/tcms/web` is synced with `--delete`.** Tarballs placed under the web root survive until
   the next release and then 404 with nothing in any log to say why. Hence a sibling directory.
6. **The SPA catch-all answers a missing path with `index.html` and a 200.** Without its own
   `handle` block, a mistyped `/downloads` path serves npm a web page, which it reports as an
   invalid gzip header. Same trap the `/mcp` block already warns about.

`ci.yml`'s `packages` job runs the pack-and-verify on every push, because 1–4 are properties of the
**source**, not of a release step.
