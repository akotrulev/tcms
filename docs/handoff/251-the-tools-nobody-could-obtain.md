# §251 — The tools nobody could obtain

`@tcms/cli` shipped in §137 and §139. `@tcms/mcp` shipped in §140 and was deployed in §142. On
2026-08-23 a question — *how would a user access the MCP and the CLI?* — turned up the answer that
**there was no way at all**, for anyone without a checkout of this repository.

## The rule to carry

> **"Nothing to deploy" is not a delivery mechanism, and it is the one gap no job can fail.**

The WIP plan §4 said the CLI is *"published as `npx @tcms/cli`. Nothing to deploy."* Nothing ever
published it. There is no `npm publish` step in `ci.yml` or `deploy.yml`, no `publishConfig`, and
both packages are `UNLICENSED`. Every other artifact in this release has a job that would go red if
it stopped arriving — the backend image, the MCP image, the SPA. This one had no job, so there was
nothing to fail, and the gap survived two weeks of green runs and a live pass.

The comments made it worse rather than better: `ci.yml` said *"@tcms/client and @tcms/cli are
published to a registry"*, `cli/src/main.ts` said *"it is published as `npx @tcms/cli`"*, and the
MCP `Dockerfile` said *"@tcms/cli is `npx`-published"*. Three files stating a fact about the world
that was never true. All three are struck through now, in place, with what replaced them.

## What now exists

**The instance serves its own tools**, at `/downloads`, built by the release that built everything
else it ships. `docs/specs/client-distribution.md` is the authority; the short version:

| | |
|---|---|
| CLI | `npm install -g https://<host>/downloads/tcms-cli-latest.tgz` → `tcms help` |
| MCP, hosted | point an MCP host at `https://<host>/mcp`, API token as the bearer — nothing to install |
| MCP, stdio | `npm install -g https://<host>/downloads/tcms-mcp-latest.tgz` → `tcms-mcp` in a host config |

Both read `TCMS_URL` and `TCMS_TOKEN`; the token is issued in the UI under **Company → API tokens**.

Serving from the instance was chosen over public npm, GitHub Packages and GHCR. The reasons are in
the spec's table, but the one that decided it is not in the table: **the copy you can download is by
construction the one that matches the API you downloaded it from.** Every registry option would have
had to reconstruct that pairing; this gets it for free, because the tarball is built at the release
tag beside the backend image, by the same run.

## Four defects, all of which pack green

`packages/scripts/pack-release.mjs` is 200 lines to produce two tarballs, and the length is the
finding. Each of these produces an artifact that builds, packs and — for three of the four —
*installs* without an error:

1. **`@tcms/client` is a workspace member on no registry.** A plain `npm pack -w @tcms/cli` emits a
   manifest depending on it; npm resolves that against npmjs.org and dies with **E404 on a scope
   nobody owns**. The client is bundled into the tarball instead.
2. **npm does not install a bundled dependency's own dependencies.** Bundling the client alone gives
   a tarball whose `openapi-fetch` directory is created **empty**, and the binary dies at its first
   import. The whole closure is vendored — which also makes the install work with **no registry
   access at all**, the right property for users behind a corporate proxy.
3. **`npm install -g` and `npm install` lay the tree out differently.** Defect 2 was found by a
   local install, *fixed*, and the fix passed — while the global install, **the command every
   document hands out**, still failed. The verify step now drives both shapes.
4. **The entry guard was wrong for a symlinked bin.** Both binaries compared
   `import.meta.url` against `` `file://${process.argv[1]}` `` — a resolved path against an
   unresolved one. `npm install -g` installs the bin as a symlink, so **`tcms help` ran nothing,
   printed nothing, and exited 0.** A silent success, on the first command a new user types.

Defect 4 is the one to remember. It was shipped in §137, survived §139's rework, the §143 live pass
and 341 tests, because every one of those runs the module by its real path. **The install shape was
never the tested shape.** It is fixed by `isProcessEntryPoint` in `@tcms/client` — one
implementation, shared, because the wrong spelling was already duplicated in two files.

`--verify` in the pack script is what turns all four from arguments into observations: it installs
each tarball **both ways** and runs the binary. `ci.yml`'s `packages` job runs the whole
pack-and-verify on every push, because 1–4 are properties of the *source*, not of a release step.

## Two placement traps, both of which look like a 404 and are not

- **`/srv/tcms/downloads` is a SIBLING of `/srv/tcms/web`, never inside it.** The SPA is synced with
  `rsync --delete`, so tarballs under the web root would be served until the next release and then
  vanish, with nothing in any log. A sibling directory cannot be reached by that `--delete`, and
  needs no `--exclude` that a later edit could drop from one of the three places that sync.
- **The Caddy block is load-bearing in the way missing blocks usually are not.** Without it,
  `/downloads/...` falls through to the SPA catch-all, which answers **200 with `index.html`** — so
  npm reports an invalid gzip header on a tarball that is really a web page. The `/mcp` block above
  it carries the same warning for the same reason; this is now the second instance.

## What is built and NOT yet verified

Per §65 this landed with its automated gates run — `npm run build`, `npm run lint --workspaces`,
341 tests and the pack-and-verify, all on shanks — and **nothing driven against a deployed
instance.** Specifically:

- No release has been dispatched, so **`/downloads` does not exist on any box yet.** It appears on
  the next deploy from master.
- **The production box's Caddyfile is not in this repository.** Its `handle /downloads` block has to
  be added by hand (runbook §9), or the trap above is exactly what a user will hit.
- **The hosted `/mcp` endpoint is still behind `COMPOSE_PROFILES=mcp`**, which is set in
  `/etc/tcms/tcms.env` on the box and is not visible from here. §142 wired the image, the route and
  the healthcheck; whether the profile is on is an operator fact this repository cannot answer.

The rows for the browser half are in `docs/verification/frontend-browser-checks.md` under §251.
