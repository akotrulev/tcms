# §20. Frontend — the two halves meet: live API mode, real login, accept-invitation (2026-07-31)

Run through the **`fe-react`** skill (agent definition + `forms` + `data-fetching` + `routing` +
`test-ids` + `ui-rules`), **Mode B — extend**. Closes §18's next-step item 2, which §19 left as the
highest-value work: *"the SPA still runs entirely on MSW, so the two halves have never met."*

**They have now met.** A company provisioned by the CLI, an invitation redeemed in the browser
against Postgres, a session issued by the real `AuthController`, and a page rendering a display name
that came out of `company_membership` — all through the SPA, for the first time.

### The slice

| Path | Change |
|---|---|
| `src/app/apiMode.ts` | **New.** `VITE_API_MODE=mock` (default) `\| live`, read once at module load. A mode that could change at runtime means a session authenticated against one API reading from the other. |
| `src/main.tsx` | MSW starts only in mock mode. **The literal `import.meta.env.DEV` test stays the first statement** — see the defect below. |
| `vite.config.ts` | Dev **proxy** `/api` → `VITE_API_PROXY_TARGET` (default `localhost:8080`). A proxy, not a CORS grant: the shipped product serves the SPA from the same jar as the API, so dev now matches that arrangement instead of inventing a looser one. |
| `.env.example` · `.gitignore` | Every variable documented with its default; `.env*` ignored except the example. |
| `src/auth/{authContextValue,AuthContext}.tsx` | **Session restore.** The stored user is a cache of the last `/me`, not a session — on mount with a token, the server is asked. A **401 clears**; a 500/timeout does **not** (a thirty-second backend restart must not sign everyone out). |
| `src/auth/SessionGate.tsx` · `src/app/router.tsx` | **New.** Holds the router until `/me` answers, so no route fires queries with a token the server has already rejected. Only waits when a token exists — public routes are unaffected. |
| `src/features/auth/AcceptInvitationPage.tsx` · route | **New public route** `/accept-invitation?token=…`, matching `tcms.invitation.accept-path`. Password 12–72 **bytes** (BCrypt's real limit, counted in bytes or the limit lands elsewhere for non-Latin scripts), confirm field, 422 mapped onto the field, **one** non-committal panel for every 404. |
| `src/features/auth/LoginPage.tsx` | Success banner + address carried from a redemption; the seeded mock address no longer prefills in live mode; **401 renders one fixed sentence** rather than the server's reason phrase. |
| `src/api/{client,queries,types}.ts` | Invitation preview/accept hooks (`meta: handled`, `staleTime: Infinity`); `ApiError.detail` gets a floor when `statusText` is empty. |
| `src/mocks/{db,handlers}.ts` | Two seeded invitation tokens (`new-user-token`, `existing-user-token`) covering both accept shapes, single-use; `/me/permissions` reshaped to the contract. |

### Two decisions worth not re-litigating

| Decision | Why |
|---|---|
| **Mock stays the default; live is opt-in** | `npm run dev` needing no Docker, no Postgres and no provisioned company is worth keeping — it is the loop that built ten screens. Live mode is what *proves* the two agree, not what replaces the fast loop. |
| **The router waits for `/me` rather than rendering optimistically** | Optimistic render costs one round trip less and buys a race: the shell paints from the cached user, a dozen route queries 401 in parallel, and the session-expired dialog appears *during first paint* — for a session that simply ended. Waiting removes the state instead of handling it. Verified: a tampered token now lands on `/login` with **no dialog and no notification**. |

### One defect, found by the build output rather than by reading

**Routing the `import.meta.env.DEV` check through the imported `IS_MOCK_API` constant shipped MSW to
production** — handlers, fixtures and all, a **485 kB chunk** in `dist`. Vite substitutes
`import.meta.env.DEV` in place, so Rollup can prove the branch unreachable and drop the dynamic
import; behind an imported binding it cannot. The guard is back as the first statement with the
reason written above it. §6 lesson 8 ("measure the bundle change before believing it") caught this
the same way it caught the DataGrid split — total JS is **1,407,520 → 1,413,895 bytes**, +6.4 kB for
a whole new page, and the entry chunk is unchanged at 124 kB gzip.

### Gate evidence

`npm run build` clean · `npm run lint` 0 warnings · **driven in a real browser in both modes.**

*Mock:* preview → client validation (12-byte floor and confirm-mismatch, both before any request) →
accept → redirect to sign-in with the success banner and the address prefilled · an unknown token →
the one rejection panel · the no-password (second-company) variant renders without a password field.

*Live, against `docker compose up` + a provisioned `northwind`:* the CLI's link previewed
**`O. Persson / ops@northwind.test` read out of Postgres** → password set → 204 → sign-in rejected
once with the single fixed sentence → signed in → **reload kept the session** and the rail showed
the membership display name → **a tampered token signed out cleanly to `/login`, no dialog**.

Everything past authentication 404s in live mode, correctly: the backend has exactly three
controllers (`Auth`, `Invitation`, `Me`), so `/projects` does not exist yet. The app degrades the way
Phase 3 built it to — failed region, one notification, rail and chrome still rendering.

### Two things worth not re-learning

1. **A stale `node_modules/.vite` after an in-place restart renders a blank page with no error at
   all.** Changing `.env.local` makes Vite restart itself; the optimize-deps hash goes stale and
   module fetches fail as *"Failed to fetch dynamically imported module"* with an empty console and a
   200 on every request when curled. Ten minutes went into hunting an app defect that was a dev-server
   cache. **Kill vite and `rm -rf node_modules/.vite` after an env change** — it is in §7 now.
2. **Drift in an unused mock handler is still drift.** `/me/permissions` returned an array of
   per-project entries that no endpoint has ever produced and nothing consumed. It was invisible
   while MSW was the only API and would have failed the first consumer written against it.

### Found, not fixed — a backend defect this pass surfaced → **FIXED in §21**

**`docker compose up` reports `/actuator/health` DOWN on every default deployment.** Both probe
groups are UP and the container healthcheck is green, which is why nobody noticed; the aggregate is
DOWN because the **`mail` health indicator** is trying to reach `:1025`:

```
"mail": { "status": "DOWN", "details": { "location": ":1025",
          "error": "MailConnectException: Couldn't connect to host, port: localhost, 1025" } }
```

Same family as §18 defect 3 and one level up from it: that fix stopped the *custom mailer* from
selecting SMTP on an empty `SPRING_MAIL_HOST`, but Spring's own mail auto-configuration still sees
the property as *present* and registers its health indicator. Anything polling the documented
`http://localhost:8080/actuator/health` sees a healthy instance reporting DOWN. Left for a backend
slice because the right fix is a decision (disable the indicator, or stop passing an empty host at
all) and this was a frontend pass — reproduced with
`docker compose run --rm -e MANAGEMENT_ENDPOINT_HEALTH_SHOW_DETAILS=always -p 127.0.0.1:8099:8080 backend`.

### Deliberately not done

- **No runtime response validation**, still §5's largest resilience gap — and live mode makes it
  matter more, since a real API can now drift from the contract in a way MSW never could.
- **No admin-initiated invitation UI.** The route redeems a link; issuing one is still the CLI's job
  (`POST /companies/{id}/invitations` does not exist — §18).
- **No `/me/permissions` consumer.** The hook is not written; `lib/permissions.ts` still reads from
  the fixture role catalogue, which is honest only in mock mode.
- **Live mode past authentication is untestable** until the backend grows the project/case endpoints.
  Everything in §4's ten screens still runs on MSW, and that is not a frontend gap.

### Next

1. ~~**Backend M1** is now the constraint: `/projects` and the repository endpoints~~ → **first
   slice DONE, §22.** `/projects` and `/projects/{id}` serve live; the rest of M1 remains.
2. ~~The `mail` health defect above~~ → **DONE, §21.**
3. Frontend **Phase 4 (Vitest + RTL)** is unchanged and still unblocked; `AcceptInvitationPage`'s
   byte-counted password rule and the `SessionGate`/restore matrix are now on its target list, both
   pure and both load-bearing.
