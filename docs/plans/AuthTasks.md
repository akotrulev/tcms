he eight tasks

  ┌─────┬────────────────────────────────────┬──────────────────────────────────────┬────────────────────────────────────────────────────┬───────────────────────────────────┐
  │  #  │                Task                │               Produces               │                    Gate to run                     │  Safe while org-chart lane runs?  │
  ├─────┼────────────────────────────────────┼──────────────────────────────────────┼────────────────────────────────────────────────────┼───────────────────────────────────┤
  │     │                                    │                                      │                                                    │ ⚠️ shared doc — re-read           │
  │ 1   │ ERD decision for sso_login_attempt │ One row in the §0.1 exception list   │ none (docs)                                        │ 2026-07-30-tcms-erd.md before     │
  │     │                                    │                                      │                                                    │ editing                           │
  ├─────┼────────────────────────────────────┼──────────────────────────────────────┼────────────────────────────────────────────────────┼───────────────────────────────────┤
  │     │ The migration V53 + schema-test    │ 3 company-owned tables + RLS, 1      │                                                    │ ❌ holds V53— — confirm the other  │
  │ 2   │ exception                          │ instance-level lookup, 1 SECURITY    │ --tests '*SchemaConventionTest'                    │ lane took V54                     │
  │     │                                    │ DEFINER resolver                     │                                                    │                                   │
  ├─────┼────────────────────────────────────┼──────────────────────────────────────┼────────────────────────────────────────────────────┼───────────────────────────────────┤
  │ 3   │ Entities + repositories            │ IdentityProvider, UserIdentity,      │ --tests '*RepositoryTransactionTest'               │ ✅ new files only               │  │
  │     │                                    │ their repos                          │                                                    │                                   │
  ├─────┼────────────────────────────────────┼──────────────────────────────────────┼────────────────────────────────────────────────────┼───────────────────────────────────┤
  │ 4   │ FederatedIdentityService — link /  │ The §6r policy engine + 6 unit tests │ --tests                                            │ ✅ new files only               │  │
  │     │ provision / refuse                 │                                      │ 'com.tcms.identity.FederatedIdentityServiceTest'   │                                   │
  ├─────┼────────────────────────────────────┼──────────────────────────────────────┼────────────────────────────────────────────────────┼───────────────────────────────────┤
  │ 5   │ OIDC protocol — discovery, token   │ 3 classes + 5 unit tests             │ --tests 'com.tcms.auth.OidcIdTokenVerifierTest'    │ ✅  new files only                 │
  │     │ exchange, id_token verify          │                                      │                                                    │                                   │
  ├─────┼────────────────────────────────────┼──────────────────────────────────────┼────────────────────────────────────────────────────┼───────────────────────────────────┤
  │ 6   │ The endpoints                      │ 3 routes + openapi.yaml + both       │ --tests '*EndpointAccessTest'                      │ ❌  holds the contract             │
  │     │                                    │ schema.d.ts                          │                                                    │                                   │
  ├─────┼────────────────────────────────────┼──────────────────────────────────────┼────────────────────────────────────────────────────┼───────────────────────────────────┤
  │ 7   │ Provider CRUD for Company Admin    │ company:idp's first consumer, secret │ ./gradlew build (full)                             │ ✅  new files only                 │
  │     │                                    │  encryption                          │                                                    │                                   │
  ├─────┼────────────────────────────────────┼──────────────────────────────────────┼────────────────────────────────────────────────────┼───────────────────────────────────┤
  │ 8   │ Keycloak + close-out               │ compose target, verification rows,   │ ./gradlew build (full)                             │ ⚠️ be-java.md + HANDOFF.md are    │
  │     │                                    │ cache, handoff                       │                                                    │ shared                            │
  └─────┴────────────────────────────────────┴──────────────────────────────────────┴────────────────────────────────────────────────────┴───────────────────────────────────┘

  Order is fixed 1 → 8. Each depends on the one before. Tasks 3, 4, 5 and 7 are the only ones that touch nothing shared.

  ---
  Prompt template
  
  Paste this and change the two bracketed bits:

  Adopt the be-java role: read the agent definition at
  ~/.claude/plugins/cache/skills-library/be-java/0d8474f66507/backend/java/agents/be-java.md
  and follow it, including the always-loaded ep-agent-orientation-cache and
  ep-code-comments skills, and run the orientation gate against
  docs/agent-context/be-java.md before touching anything.

  Then load ep-executing-plans and execute ONLY **Task [N]** of
  docs/plans/2026-08-15-m4-external-identity-oidc.md.

  Do not start Task [N+1]. Do not do work from any other task, even if it looks
  like a one-line prerequisite — tell me instead.

  Rules that bind this run:
  - Run every gate on shanks: ~/bin/tcms-remote backend ./gradlew <task>. Never
    claim a gate passed that you did not run.
  - This lane holds openapi.yaml and V53; the org-chart lane holds V54.
  - Stage only the files this task touches (git add <paths>, never git add -A).
    A second lane is live in this tree.
  - Commit at the end of the task, after its gate passes, using the plan's commit
    message. Push after.
  - Verification is deferred and batched (§65): write the queue rows, run none of
    them. No browser pass.
  - Emit no inline comments; a doc comment only where the name cannot carry the
    meaning.

  Report using the be-java brownfield output contract, and state plainly if
  anything in the plan turned out to be wrong rather than working around it.                                             

  ---
  Two things to set before Task 2

  Confirm the org-chart lane took V54. If both lanes write a V53, the filenames don't collide, Testcontainers stays green because it migrates a fresh container every time, and
  the second one to land is refused on any already-migrated database — including yours and production.

  Pick the handoff section number. The plan's commit messages say §NNN because sections are allocated in landing order and the org-chart lane may take the next one. Decide it
  when Task 2 starts and tell the agent, or it will guess.

  One adjustment worth making
  
  Task 2 is by far the largest — a ~210-line migration plus a deliberate red-then-green cycle on SchemaConventionTest. If you'd rather not hand that to a single run, say so and
  I'll split it into 2a (write the migration, confirm the schema test fails naming sso_login_attempt) and 2b (add the exception, go green, commit). That's the one task where an
  agent is most likely to "helpfully" add company_id to sso_login_attempt to make the test pass — which would silently break the entire pre-authentication path the table exists
  to serve.