# §133 — The eighth role, and the flag that could only carry two of five checks

**Date:** 2026-08-08 · **Three phases, one migration (`V40`), one contract change** ·
**Plan:** [`docs/plans/2026-08-08-agent-role.md`](../plans/2026-08-08-agent-role.md), executing
[`2026-08-08-mcp-server-wip-plan.md`](../plans/2026-08-08-mcp-server-wip-plan.md) §7 phases **2 and
3**, both `[SETTLED]`. §132 landed that plan's phase 1.

**Why it was not blocked by that plan's "do not start phase work until §8 is closed".** §8's own
text narrows what is open: question 1 is *"the only genuinely blocking one"* and it is the **tool
and command surface** (§6), which gates phases 4 onward. Phases 2–3 are the role and the issue-path
rule, and §2.5's whole argument is that the ceiling must live in the **role** rather than in a tool
list — so it cannot be derived from a tool list that does not exist yet. Phase 1 was landed on the
same reading.

## What it does

`V40` seeds **`Agent`** — PROJECT scope, carried by API tokens, never held by a person — as the
capability ceiling for agent access to the repository. **Test Engineer minus four**: `library:manage`
(roles spec §A3 gates it by an *impact modal*, and an API caller never sees one), `case:delete` and
`evidence:delete` (both `OWN`, and `OWN` follows `api_token.owner_id`, so the agent would delete what
the **human** authored), and `company:read` (a token is not a member). 23 keys, enumerated once in
roles spec **§A6a**.

**`PermissionEvaluator` is unchanged.** The intersection that already caps a credential —
`evaluate(ownerId, projectId) ∩ permissionsOf(token.roleId) ∩ [the token's project]` — is the only
narrowing point, so a Viewer's agent token resolves to Viewer's set with no branching written
anywhere. That is the reason the ceiling is a **role**: it appears in the permission matrix and
`SeedDataTest` can derive it, where a ceiling held in validation code appears in neither.

## THE FINDING: `assignable` is one of three questions that had always had the same answer

There was no `assignable` flag. Every exclusion of `Company Member` was a hardcoded
`Role.COMPANY_MEMBER_ID.equals(...)`, in **five** places — and the obvious reading of that is "five
copies of one rule, replace them with a column". It is not. The two non-assignable roles differ
exactly where three of those five sites look:

- **`Company Member`** — non-assignable **and** non-issuable **and** never listed.
- **`Agent`** — non-assignable, **but issuable, and it must be listed**, because the token-issue
  form is the only place in the product it is ever chosen.

So the flag drives the two `RoleGrantService` enforcement points and **nothing else**. The three
that stay baseline-specific are each one line, each looks like a leftover, and each has a distinct
failure:

| Site | If it reads `assignable` |
|---|---|
| `AdminDirectoryService` `GET /roles` filter | `Agent` vanishes from the token form — the role becomes unobtainable through the product |
| `ApiTokenService.validate` | an `Agent` token cannot be issued — **the one thing the role exists for** |
| `AdminDirectoryService` `GET /role-assignments` filter | harmless today, and only because an `Agent` grant cannot exist |

None of the three would look wrong afterwards: every other role keeps issuing, every grant keeps
being refused, and nothing in 2217 tests changes colour except the two assertions written for it.
`V40` §2 is the long form, both sites carry a comment refusing the cleanup, and
`ApiTokenEndpointTest.theAgentCeilingIsIssuable` is the check.

## The second finding: `RoleReference`'s own javadoc had already made the argument, about the other axis

`RoleReference` filters by **scope**, and says why: offering a reference card *"next to a Grant
button that will refuse it is the mis-teaching"*. That sentence is about assignability and was
written about scope, because until `V40` every listed role was grantable somewhere. `Agent` is
PROJECT-scope and grantable nowhere — the first role where the two axes disagree — so the existing
filter passed it straight through onto both screens' reference cards and into `GrantRoleDialog`.

Three of the four SPA consumers of `GET /roles` filter on the flag now. The fourth,
`IssueTokenDialog`, must not, and carries a comment saying so: it is the same predicate, on the same
data, in a file two directories away, and applying it there is the tidy-up that breaks the feature.

## Phase 3 — the first per-role rule in the issue path

**`expiresAt` is required for an `Agent` token and optional for every other role.** Open-ended is
the default for the reason `ApiToken.issue` already records — a CI credential that dies mid-release
without anybody deciding to withdraw it is an outage. An agent token inverts it: it lives on a
developer's machine, inside an MCP client's configuration, where the realistic end is that nobody
revokes it because nobody remembers it exists, and **an optional field is a field nobody fills in**.

In `ApiTokenService`, **not** in the form (§2.5): the `tcms_` bearer authenticates against `/api/*`,
so a rule a client holds is a rule `curl` does not. The form pre-fills 90 days so the server's rule
is a nudge rather than an obstacle — in **local wall clock**, because `datetime-local` wants the
browser's own time and an ISO string would show a UTC value labelled as the user's, and **only into
a blank field**, because overwriting a date somebody typed swaps a decision for a default.

## Also

- **No red run in any of the three phases**, which is unusual enough to state. The one failure was
  a test of my own: `optionNames` in the admin dialog tests leaves the MUI menu open, and an open
  menu makes the rest of the document inert — so the next `chooseFrom` reports *"no accessible
  roles"* on an element the failure dump plainly shows has one.
- **`SeedDataTest.agentIsTestEngineerMinusThree` asserts the delta in both directions** rather than
  transcribing 23 keys. A subset check would let a permission a future migration adds to Test
  Engineer land silently in a credential's ceiling; a delta check fails.
- **The policed-table count stays 62.** `role` and `role_permission` are instance-level reference
  data on the ERD §0.1 exception list, so `V40` adds no policy.

## Not built, and why

- **`Automation Service` is still assignable to a person**, although `V3`'s description has said
  *"attached to API tokens, never people"* since M0 and nothing has ever refused such a grant —
  **§100's rule, eighth sighting**. It is a behaviour change for an existing role with a live-data
  question behind it (what happens to grants that already exist), so it belongs in its own slice
  with that question answered, not as a side effect of this one.
- **Nothing an `Agent` token can be used *by*.** The CLI, the MCP server and the tool surface are
  that plan's phases 4 onward and are blocked on its §8 question 1.
- **The per-token permission picker** (MCP §8.3) — its own join table, a third intersection term.

**Built and unverified** — 9 mutation rows `AG-1`…`AG-9` and 7 browser rows `A3-18`…`A3-24`,
**none run**, per §65. `AG-9` is the highest-value one and is a **missing test, not a mutation**:
nothing in the suite drives the intersection end-to-end for an `Agent` token, which is §132's
observation about `IngestionEndpointTest.Attribution` one role over.

**Gates.** `./gradlew build` BUILD SUCCESSFUL — **2217 tests across 472 result classes, 0 failures**
(phase 2's 2216, phase 1's 2216). `npm run build` clean · `npm run lint` 0 warnings ·
`npm run test:run` **1177/1177 across 90 files** (five added).
