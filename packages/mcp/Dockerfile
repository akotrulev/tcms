# syntax=docker/dockerfile:1.7
#
# @tcms/mcp — the remote MCP server, as deployed. WIP plan §4 and §142.
#
# BUILD FROM THE WORKSPACE ROOT, not from this directory:
#
#     cd packages && docker build -f mcp/Dockerfile -t tcms-mcp:dev .
#
# The context has to be `packages/` because this package is a workspace member: it depends on
# @tcms/client through the root's `node_modules`, and a context rooted at `mcp/` cannot see
# either the client's sources or the lockfile that pins both. The file lives with the package it
# builds rather than at the workspace root because the root builds three packages and only one of
# them is ever containerized — @tcms/cli is `npx`-published (WIP plan §4: *"nothing to deploy"*).
#
# WHAT THIS PROCESS IS, because it decides everything below: one long-running HTTP server that
# holds NO credential of its own. Under `--http` the bearer is each request's `Authorization`
# header, and the whole MCP `Server` — instructions and tool list — is built per session from it
# (§140). So the image carries no secret, needs no secret at build time, and a compromise of it is
# a compromise of whatever sessions are live rather than of a stored key.

# ---- Build stage ----
FROM node:22-alpine AS build
WORKDIR /src

# Node 22, not 24, and the reason is not conservatism. `ci.yml`'s `packages` job installs
# `node-version-file: frontend/.nvmrc`, which is 22.12.0 — so 22 is the runtime the 211 tests
# actually run on. Shipping a 24 image would deploy a major nobody has run this code on, and the
# gap would be invisible: both are green, on different runtimes. Move this when `.nvmrc` moves.
#
# The tag pins a major and floats the patch, which is what every other image in this repository
# does (`postgres:17`, `eclipse-temurin:21-jre-noble`) — a rebuild picks up base-image CVE fixes
# without a commit, and the thing that must not drift (the major) cannot.

# Manifests first, so the install layer is keyed on the lockfile rather than on source edits.
# All four are needed even though only two packages end up in the runtime image: `npm ci` refuses
# to run against a lockfile whose workspace set it cannot reconstruct, and omitting cli/package.json
# fails with a lockfile-mismatch error that reads like a corrupt lockfile.
COPY package.json package-lock.json ./
COPY client/package.json client/
COPY cli/package.json cli/
COPY mcp/package.json mcp/

# `ci`, not `install`: install mutates the lockfile, so the image would be built from a dependency
# tree no developer and no CI run has.
RUN --mount=type=cache,target=/root/.npm npm ci

COPY client client
COPY cli cli
COPY mcp mcp

# THE ROOT'S BUILD SCRIPT, not `npm run build -w @tcms/client && … -w @tcms/mcp`. The order
# client → cli → mcp is load-bearing (§139: `--workspaces` runs them alphabetically, so `cli`
# typechecked against a `dist/` that did not exist and **CI's packages job had never once been
# green**), and the whole point of that fix was to have the order written down in exactly one
# place. Spelling a second, shorter order here would be a second copy of it — and the copy that
# rots is always the one nobody runs locally. Building `cli` costs a few seconds and buys the
# guarantee that this image and the CI gate compile the same way.
#
# Tests are NOT run here. `ci.yml`'s packages job is the test gate; this stage only packages.
RUN npm run build

# Drop devDependencies from the tree the runtime stage copies. `--omit=dev` at the workspace root
# prunes every member's dev tree at once — typescript, vitest and oxlint are the bulk of it.
RUN npm prune --omit=dev

# A COMPUTED CHECK, because the runtime stage copies `node_modules` from the ROOT ALONE.
#
# npm hoists a workspace's dependencies to the root unless a version conflict forces a private
# copy into `<member>/node_modules`. Today nothing conflicts, so those directories hold nothing
# but vitest scratch and the root copy is complete. That is a property of the current dependency
# graph, not of npm — one member pinning a different `openapi-fetch` would silently put runtime
# code in a directory the runtime stage does not copy, and the image would fail at `import`
# rather than here. Reading the COPY list would never catch it; this does.
RUN set -eu; \
    stray="$(find client/node_modules mcp/node_modules -mindepth 1 -maxdepth 1 ! -name '.*' 2>/dev/null || true)"; \
    if [ -n "$stray" ]; then \
      echo "npm did not hoist everything to the root — these must be COPYed into the runtime stage too:" >&2; \
      echo "$stray" >&2; \
      exit 1; \
    fi

# ---- Runtime stage ----
FROM node:22-alpine AS runtime

ARG VERSION="0.0.0"
ARG REVISION="dev"
LABEL org.opencontainers.image.title="tcms-mcp" \
      org.opencontainers.image.description="TCMS — the MCP server, exposing the test repository to an MCP host as permission-filtered tools" \
      org.opencontainers.image.source="https://github.com/akotrulev/tcms" \
      org.opencontainers.image.version="${VERSION}" \
      org.opencontainers.image.revision="${REVISION}" \
      org.opencontainers.image.licenses="UNLICENSED"

# tini as PID 1, and this one is not boilerplate. A process running as PID 1 gets NO default
# signal dispositions, so a SIGTERM to a Node process that installs no handler — and this one
# installs none — is discarded. Every `docker compose up -d` over a running container would wait
# out the full 10s stop timeout and then SIGKILL a process holding live SSE sessions, on every
# release, looking like a slow deploy rather than a bug.
#
# Measured on this image rather than assumed — `docker stop` on the same container, twice:
#   with tini (ENTRYPOINT below)   0.3s
#   node as PID 1 (--entrypoint)  10.2s   ← the stop timeout, in full, every time
RUN apk add --no-cache tini \
    && addgroup -S -g 10001 app \
    && adduser -S -u 10001 -G app -s /sbin/nologin app

WORKDIR /app

# The workspace layout is REPRODUCED rather than flattened, because `node_modules/@tcms/client` is
# a symlink to `../../client` — resolving it needs `client/` to sit beside `node_modules/` exactly
# as it does in the workspace. Flattening the two dists into one directory would need the symlink
# rewritten, which is a second layout to keep true.
COPY --from=build --chown=10001:10001 /src/package.json      ./
COPY --from=build --chown=10001:10001 /src/node_modules      ./node_modules
COPY --from=build --chown=10001:10001 /src/client/package.json ./client/
COPY --from=build --chown=10001:10001 /src/client/dist       ./client/dist
COPY --from=build --chown=10001:10001 /src/mcp/package.json  ./mcp/
COPY --from=build --chown=10001:10001 /src/mcp/dist          ./mcp/dist

USER 10001:10001

# 8081, one above the backend's 8080, and published to the host's loopback only — see
# deploy/compose.prod.yaml.
EXPOSE 8081

ENV NODE_ENV=production

# THE PROBE ASSERTS A 405, AND THAT IS THE POINT.
#
# This surface has no unauthenticated health route and must not grow one: with Tailscale Funnel
# on, every unauthenticated endpoint here is an internet-facing endpoint. Every credentialed path
# mints a session. So a probe has exactly two credential-free answers available to it:
#
#   GET /anything-else   404   the process is listening
#   GET <configured path> 405  the process is listening AND is serving the path it was configured
#                              with (`http.ts` refuses a non-POST with no session)
#
# The second is the one worth checking, because `--path` can disagree with Caddy's `handle` block
# and that disagreement is invisible from outside: Caddy's SPA catch-all answers a missed path
# with index.html and a 200, which the Caddyfile already warns about in those words.
#
# `node -e`, not the busybox `wget` this image does ship: the assertion is a SPECIFIC non-2xx
# status, and wget/curl model every non-2xx as failure — `--spider` exits non-zero on a 405, so
# the healthy case and the broken case would be the same exit code.
HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
  CMD node -e "require('http').get({host:'127.0.0.1',port:8081,path:'/mcp'},r=>process.exit(r.statusCode===405?0:1)).on('error',()=>process.exit(1))"

ENTRYPOINT ["/sbin/tini", "--"]

# `--host 0.0.0.0`, DELIBERATELY, and it does not widen anything.
#
# `BIND_DEFAULT` is 127.0.0.1 and its comment gives the right reason for a process on a developer's
# machine. Inside a container that default is not a tighter boundary, it is an outage: a published
# port is served by docker-proxy, which dials the CONTAINER'S NETWORK ADDRESS, and a process bound
# to the container's own loopback refuses that connection. The container would start, report itself
# up, and answer nothing.
#
# The restriction moves to the publication address instead (`127.0.0.1:8081:8081`). The container's
# network namespace is the wall; that published port is the only door through it. This is the same
# confusion `host.docker.internal` cost this deployment one service over.
CMD ["node", "mcp/dist/main.js", "--http", "--host", "0.0.0.0", "--port", "8081"]
