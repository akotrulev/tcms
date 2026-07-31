# §31. Frontend — the caller's effective permissions, and disable-don't-hide everywhere at once (2026-07-31)

Run through the **`fe-react`** skill (Mode B — extend, re-entering Phase 2). Plan:
`docs/plans/2026-07-31-effective-permissions-disable-dont-hide.md`.

### The orientation pass changed the slice's PREMISE, and this is the third slice running

§30 named this first and described it as **"one contract change (the caller's effective permissions
on a project)"**. The orientation pass found that premise wrong in the most useful direction:

- **`GET /me/permissions` was already in the contract** — since the mock era — and **already
  implemented**. `MeController.permissions()` answers the three genuinely different questions
  (SYSTEM only · SYSTEM+COMPANY · SYSTEM+COMPANY+PROJECT), refuses a `userId` parameter on purpose,
  and `AuthEndpointTest` covers it. The mock handler existed and had been aligned in an earlier slice.
- **Nothing in the SPA had ever called it.** No hook, no key, no consumer.

So the "contract change" was one `required` array, and the slice was entirely frontend. Same lesson
as §28 and §29 in a third costume: **the previous session's next-step list describes the intent, not
the state.** Here the list was pessimistic rather than optimistic — it named work already done — but
it was wrong in the same way, and one grep found it.

| Path | Purpose |
|---|---|
| `docs/specs/openapi.yaml` | Named `EffectivePermissions` **with `required`**; `projectId` nullable. |
| `src/auth/useCan.ts` | **New.** The loading/failed policy, in one place. |
| `src/components/PermissionGate.tsx` | **New.** A denied action → a disabled control with a why-tooltip. |
| `src/lib/permissions.ts` | `permissionLabels`, `satisfies Record<PermissionKey, string>`. |
| `src/api/queries.ts` | `keys.permissions` + `useMyPermissions`. |
| `features/cases/*` · `features/runs/ExecutionPage.tsx` | Eleven affordances gated. |
| `src/mocks/handlers.ts` | Answers from the project's own `myRole` instead of always Test Lead. |

### Unknown is DENIED, failed is ALLOWED — the asymmetry is the design

Three states, three answers, and only two of them are obvious:

- **Loading → disabled** ("Checking your permissions…"). Disabled-then-enabled costs a grey moment.
  Enabled-then-disabled is *the defect itself*: the click lands in the window before the answer does.
- **Failed → enabled.** Fail-open, and it is the only rule here that looks wrong. Hidden is not
  protected (§6b) — the server rejects independently — so a wrong *enable* costs one 403 in a dialog,
  which is exactly how the app has behaved its whole life. A wrong *disable* tells a Test Lead they
  may not do their job, with no retry path and no error anywhere, because the failure is in a query
  that renders nothing. The cheap failure is the enabled one.

### The gate is one component, and the browser proved why in the first five minutes

`PermissionGate` owns the mechanic because MUI sets `pointer-events: none` on a disabled control, so
a `Tooltip` wrapped directly around one **never fires** — the button greys out and explains nothing,
which is the *worse* half of the original defect. The denied branch wraps the child in a span.

Then the first browser pass found the cost of that span: **the suite tree's kebab menu rendered its
three items side by side on one line.** `MenuList` is a plain `<ul>`, and an `inline-flex` anchor
inside it makes the items flow inline instead of stacking. Fixed with a `wrapper="block"` prop —
one prop, in the one component, rather than a layout patch at three call sites. The wrapper also
carries `role="none"`, because a generic between `role="menu"` and `role="menuitem"` can break the
relationship some screen readers rely on. **Both gates were green while the menu was unreadable.**

### Every write affordance, not just the suite ones

§30 refused to gate suites alone precisely so this slice could sweep the class. Derived from the
mutation hooks in `queries.ts` and their consumers, not from memory:

| Affordance | Permission |
|---|---|
| Suite tree: New · New suite inside · Rename | `suite:manage` |
| Suite tree: Delete | `suite:delete` |
| Case list: New case (header + empty state) | `case:create` |
| Case list: Edit selected (bulk) | `case:edit` |
| Case detail: Edit case | `case:edit` |
| Case editor: Save | `case:create` / `case:edit` |
| Execution: Passed · Failed · Blocked · Skipped | `result:submit` |

Two consequences worth keeping:

- **The editor's Save is gated as well as the button that navigates to it**, because `/cases/new`
  and `/cases/:id/edit` are real URLs. Gating only the entry point makes a permission *look*
  enforced while the form stays fully usable up to the 403.
- **A denied "Edit case" renders a plain `<button>`, not a disabled link.** MUI's `disabled` on
  `component={RouterLink}` only adds `aria-disabled` and kills pointer events — the anchor keeps its
  `href`, so Enter on a focused link and a middle-click both still navigate. Verified in the DOM:
  `{tag: "BUTTON", href: null, disabled: true}`. A disabled-looking link that opens the editor
  anyway is worse than no gate, because it looks like the gate worked.

**Empty-state copy follows the verdict too.** "Create the first test case for this project" beside a
button the reader cannot press is an instruction they cannot follow; a Viewer now reads "Ask a test
engineer or lead to add the first case."

### The mock had to be made less permissive before it could prove anything

The handler answered **Test Lead for every project**, which was harmless while nothing called it and
became actively misleading the moment something did: a mock that grants everything can only render
the enabled branch, so the whole feature would have been unprovable in the mode the app is developed
in. It now answers from the project's own `myRole` — and the fixtures already carried MOB, where the
caller is a **Viewer**, seeded that way in an earlier slice because read-only is the default on
joining. No new fixture was needed; the awkward row was already there.

### Gate evidence — run, not just built

`npm run build` clean, `npm run lint` 0 warnings, console clean (no MUI warnings).

**Mock**, both branches on real screens:

| Assertion | Result |
|---|---|
| MOB (Viewer): New case · suite New | **disabled**, tooltip "You don't have permission to create test cases in this project." |
| MOB: kebab menu | three items **stacked** and greyed; hovering Delete shows "…delete suites in this project." |
| MOB: `/cases/new` reached by URL | **Create case disabled** — the deep link does not bypass the gate |
| MOB: case detail Edit | `{tag: "BUTTON", href: null, disabled: true}` |
| OLB (Test Lead): the same controls | **enabled**, Delete still danger-red, and creating a suite still works end to end |

**Live**, against `docker compose up` and a real session (`ops@acme.test`, Company Admin):

| Assertion | Result |
|---|---|
| `GET /me/permissions?projectId=…` | 200, **48 permissions, sorted**, `projectId` echoed |
| `GET /me/permissions` (no project) | 200, **`projectId: null`** — which is why the contract needed `nullable` *and* `required` |
| Affordances | enabled, matching the 48 |
| Role temporarily switched to **Viewer** in Postgres, page reloaded | **New case and suite New both disabled, with the tooltip** — the list still loads, because `case:read` survives |
| Role restored | enabled again |

Flipping the row in Postgres is how the denied branch was proved *live*: the only other account with
a Viewer grant on that project (`viewer@acme.test`) has a password nobody recorded, and there is no
password-reset path. **The assignment was restored immediately** and re-verified.

### Two things this slice observed and did not fix

1. **`provision-company` creates no project** — a fresh company's admin lands on an empty project
   list with `project:create` and **no UI that creates one**. Every earlier live pass worked around
   it with hand-written SQL, which is also how §30's `key_sequence` collision was born. This is the
   next real gap in the live loop.
2. **Board drag-and-drop is ungated** (`workitem:manage`). The affordance is a drag, not a control,
   so disabling it needs a different mechanic than a tooltip — named rather than half-done.

### Dead hooks, now four and counting

`useCaseVersions` (five slices), plus `useDeleteCase`, `useCreatePlan`, `useCreateRun` and
`useCreateWorkItem` — all with zero consumers. Listed here rather than given affordances they have
not earned. `useCreateSuite` left this list in §30; nothing left it this slice.

### Next

1. **`DELETE /cases/{caseId}`** — `useDeleteCase` has no consumer *and* no endpoint, so the slice owes
   both halves, plus the `OWN` qualifier `case:delete` carries. The gate is already there to hang it on.
2. **A project-creation path** — the finding above. Without it, "provisioned company" still means
   "provisioned company plus some SQL", and every live pass pays for it.
3. **`GET …/cases/{caseId}/versions` with the detail page's history panel** — still only worth doing
   as one slice, and still the oldest dead hook.
4. **The `execution` module** — unblocks `/history`, the one screen with a live link that 404s.
5. Frontend **Phase 4 (Vitest + RTL)** — `useCan`'s three-state policy is now the best first target in
   the codebase: pure, branchy, and load-bearing for eleven affordances.
