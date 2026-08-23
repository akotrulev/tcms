# §252 — The page that tells you what you cannot do

**One phase, `frontend/` only.** No migration, no Java, no contract change, no `schema.d.ts`
regeneration, and nothing under `packages/`. Five new files, three edited.

v1 shipped with no user documentation reachable from the product. `docs/features.md` is a feature
inventory written for somebody deciding what the product does; the roles spec and
`client-distribution.md` are authoritative and live in a repository the users of a self-hosted
instance do not have. **A user signed into the app had nowhere to read what any of it meant.**

## What landed

`/help` and `/help/:sectionId` — code-split, behind `RequireAuth`, inside `AppShell`, ungated.
Eleven sections: getting started · roles and access · projects · writing test cases · suites, tags
and test plans · running tests · results from CI · documentation, artifacts and reports · people,
teams and API tokens · the command-line tool · AI agent access (MCP).

| File | What it is |
|---|---|
| `features/help/HelpPage.tsx` | Route, contents list, section body, pager |
| `features/help/sections.tsx` | The guide itself — the eleven `HelpSection`s |
| `features/help/RoleMatrix.tsx` | Who-can-do-what, read live from `GET /roles` |
| `features/help/prose.tsx` | `P` · `H` · `List` · `Code` · `Terminal` · `Note` · `Definitions` |
| `features/help/HelpPage.test.tsx` | 22 tests |

## The four decisions worth defending

**1. The role matrix is READ FROM THE SERVER, never written down.** This repository has paid for a
hand-copied permission matrix five times, and `packages/client/src/test/matrixCopies.test.ts` exists
because two copies drifted. A sixth copy on the one page whose whole purpose is telling users the
truth about their access would be the worst-placed of them all — the copy nobody would think to
check, read by the people least able to notice it is wrong. So the columns come from `GET /roles`
and the rows from `lib/permissions.ts`, the same catalogue `useCan` reads to phrase a disabled
control's tooltip. **A grant moved between roles by a migration moves here on the next page load,
with no commit**, and an instance running an older or newer seed shows *its* matrix.

`GET /roles` costs `company:read`, which every member holds through the non-assignable
`Company Member` baseline (`V15`) — so it renders for every signed-in reader, which is the audience
that was asked for. The test that matters asserts the table follows a response granting `Viewer`
a permission **the spec does not give it**: if that cell renders as held, the matrix cannot be a copy.

**It is not a second `RoleReference`.** That component answers *"if I grant this person Test Lead,
what have I just given them"* — one card per role, non-assignable roles dropped because it sits
beside a Grant button that would refuse them. This one answers *"who can do this, and who cannot"*,
which is a comparison across roles, and it deliberately KEEPS the credential-only roles because a
reader wondering what their CI token or their agent can reach has nowhere else to look.

**2. No markdown renderer.** The obvious build is markdown plus `react-markdown`, refused on two
counts: ~40 kB of parser and sanitizer shipped for content that is fixed at build time and never
user-supplied, on a page whose whole job is to be cheap; and it puts the copy outside TypeScript,
where a rename in `lib/permissions.ts` or a route change in `router.tsx` cannot reach it. Sections
are TSX, so a link to a route that stops existing is a compile error. The chunk is **38.8 kB /
13.0 kB gzip** and is paid only by readers who open Help.

**3. A section per URL, not one long page with anchors.** Eleven sections on one route is one render
of every table and code block on it — the role matrix's fifty-odd rows included — for a reader who
came to find out how to install the CLI. A section per URL means back works between them and a link
pasted into a ticket lands where it meant to. **`/help` redirects to the first section**; a contents
page whose only content is a list of links the sidebar already shows is a click that teaches nothing.

**4. Ungated, and that is the decision.** Every section describes capabilities some readers do not
hold — the role matrix most of all. A key invented for this would be seeded to nobody, which is a
refusal no administrator could grant their way out of (`BoardService.teamBoard`'s argument). It also
inverts the product's own rule: this is the screen that exists to tell somebody what they are
missing and what to ask for, so it is the last screen that should hide it from them.

## Where it is reachable from, and where it is not

**Not the rail.** Every rail item is a place in the *product*, and the rail's grouping tells the
reader whether the project switcher changes what they are looking at. Help is about the product
rather than a place in it, so it sits with the two other entries whose subject is the session
itself: the user menu (beside **Security**), and the footer.

**The footer link closes a recorded decision rather than reopening one.** `AppFooter` used to carry
"Documentation" and "Contact support" as `href="#"`, removed with the note *"they come back when
they have somewhere to point"*. Documentation now has somewhere to point, so it is back — this is
the condition being **satisfied, not waived**. **Contact support stays out**: its destination is
still per-deployment configuration this repository does not know.

## Two things the writing found, neither fixed here

1. **`/projects/:projectId/run-schedules` is reachable only by typing the URL.** No rail entry, and
   nothing in `RunListPage` or anywhere else links to it — grepped, not assumed. §247 landed the
   feature in a batch that ran no gates, and its own queue rows are unrun. The guide states the
   address and says plainly that nothing links to it; browser row **`§252 H-7`** exists to catch the
   note outliving the gap.
2. **`packages/cli`'s help text sends users to "Settings → API tokens".** The rail label is
   **Company → API tokens**, which is what `client-distribution.md` says and what this page says.
   A one-word string fix in a package this slice did not otherwise touch; recorded in `§252 H-6`
   rather than smuggled in.

Separately, `packages/mcp/src/tools.ts`'s module header still says *"The count is 21"*. It is 23,
counted from the built registry. Also unfixed here, and named so it is not found twice.

## Gates — all on shanks

| Gate | Result |
|---|---|
| `frontend npm run build` | green |
| `frontend npm run lint` | **0 warnings, 0 errors**, 360 files |
| `frontend npm run test:run` | **1732 across 129 files** (+22) |
| `packages npm run test:run --workspaces` | 341 unchanged (82 / 130 / 129) — run because `citedFiles.test.ts` reads `frontend/src/**` |

`backend` deliberately not run: nothing outside `frontend/src` changed.

**Built and unverified.** Browser rows `§252 H-1` … `H-8` are recorded in
`docs/verification/frontend-browser-checks.md` and **not run** — §65. No mutation rows, and that is
not an omission: the page issues one read and writes nothing.

## What it opens

- **The guide has no search.** Eleven sections is the size at which a contents list is still enough;
  it is not the size at which it stays enough.
- **Nothing checks the copy against the surfaces it describes.** The CLI command list, the MCP tool
  list and the four ingestion formats are hand-transcribed from `tcms help`, the built tool registry
  and the parsers. `matrixCopies.test.ts` is the precedent for what a check would look like, and the
  CLI and MCP lists are the two most mechanically comparable — both are exported arrays.
- **It is English only.** The product has no internationalisation layer at all, so this is the same
  gap one screen wider, not a new one.
