# The MCP server — plan phase 8

**Status: SHIPPED 2026-08-08**, three phases. See
[`docs/handoff/140-the-mcp-server.md`](../handoff/140-the-mcp-server.md) for what it found.

**Specified by** [`2026-08-08-mcp-tool-and-command-surface.md`](../specs/2026-08-08-mcp-tool-and-command-surface.md)
(the 21 tools, the gate rule, the cross-cutting rules) and
[`2026-08-08-mcp-server-wip-plan.md`](2026-08-08-mcp-server-wip-plan.md) §4 (transport and
deployment) and §6.2 (the capability summary). This document is the build plan and the record of
what it cost; where it disagrees with either of those, they win and this is the bug.

**No migration, no contract change, no backend and no frontend code.** `packages/mcp` only.

---

## 1. Shape

```
@tcms/client  ──────────────►  @tcms/cli      (§137 reads, §139 writes)
   the only thing that     └─►  @tcms/mcp      (this)
   makes a request
```

The MCP server is a **thin adapter**, per surface spec §1: it contains no request, no URL, no key
generation and no refusal copy. What it does contain is everything about *being an MCP server* — the
session, the tool registry, the filtering, the instructions, the two transports — which is exactly
the set of things the CLI has no equivalent of.

| File | Holds |
|---|---|
| `session.ts` | One credential, probed once. The project rule, the permission set with its qualifiers, and the refresh. |
| `instructions.ts` | §6.2's capability summary, fitted to the host's 2KB ceiling by dropping whole sections. |
| `tool.ts` | What a tool is: gate, arms, schema, `_meta`, and the per-session description. |
| `reads.ts` · `writes.ts` · `whoami.ts` | The 21 tools. |
| `tools.ts` | The registry the conformance tests derive from. |
| `result.ts` | A value or a refusal, in the envelope the host reads. |
| `server.ts` | One `Server` per session: filtering, dispatch, arm resolution, `listChanged`. |
| `http.ts` · `main.ts` | The remote shape, and the entry point. |

## 2. Decisions taken here, that neither source document had taken

1. **One `Server` per session, not per process.** Forced by `instructions` being a constructor
   argument and by §4's remote-HTTPS deployment. The slice's central finding; handoff §140 §1.
2. **The gate filters on keys, the description discloses the qualifier.** `GET /me/permissions`
   answers `{key, qualifier}` pairs and the surface spec reasons only in keys. Hiding a narrowly-held
   tool would withhold something the caller can do; listing it silently produces the retry loop the
   filtering exists to prevent. Handoff §140 §2.
3. **`TCMS_PROJECT` binds a session the operator configured; nothing else may choose.** §4.1 forbids
   a project *tool argument*; the CLI's `--project` is a person naming one, and the equivalent here
   would be the forbidden thing. With several projects visible and no `TCMS_PROJECT`, the session is
   unscoped and says so — and it needs no special-casing downstream, because an unscoped
   `GET /me/permissions` answers SYSTEM and COMPANY terms only, so every project-scoped tool falls
   out of `tools/list` on its own gate.
4. **A refusal is a result, never a protocol error.** Otherwise the model never reads the text.
5. **Two arms are dropped and their permissions with them** — evidence and reviewer assignment. See
   the surface spec's amended §3 and handoff §140 §3.
6. **Loopback by default, TLS out of process.** Caddy already terminates it for `/api/*`.
   `MAX_BODY_BYTES` is the one limit that could not wait for phase 9.

## 3. What this does not do

Phase 9 (rate limiting, REST side) and phase 10 (image, compose, Caddy route, runbook) are
untouched. OAuth 2.1 is §4's own slice. And **nothing in `packages/` has been run against a real
TCMS** — the standing gap, now three packages wide, named in `docs/HANDOFF.md`.
