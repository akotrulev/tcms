# §223 LDAP / AD slice — resume prompt

**Written 2026-08-16, mid-slice, for a fresh context.** Paste the "Prompt" section below into a new
session. Everything after it is the evidence that prompt rests on.

---

## Prompt

> Continue implementing `docs/plans/2026-08-15-m4-external-identity-ldap-ad.md` using the
> `ep-executing-plans` skill. **Tasks 1–5 are committed and green. Task 6 is in progress and has one
> failing test.** Read `docs/plans/2026-08-16-ldap-slice-resume-prompt.md` first — it records what
> landed, the four places the plan was wrong, and exactly where Task 6 is stuck. Do not re-derive it.
>
> Start by fixing the single failing test in `backend/src/test/java/com/tcms/auth/LdapDirectoryTest.java`
> (the TLS certificate has no SAN matching the host — see "Where Task 6 is stuck"), then carry on
> through Tasks 7–12. Checkpoint after each task.
>
> Migration numbering: **this slice owns `V57`**, already written. Handoff section is **§223**.

---

## What is committed and green

| Commit | Task | Gate actually run |
|---|---|---|
| `253e0df` | 1 — transport decision (D2) recorded in the OIDC plan | docs only |
| `06c7db6` | 2 — the contract + both `schema` copies | packages 325 green; frontend 1411 green, build + lint clean |
| `22ab09b` | 3 — `V57__ldap_identity_provider.sql` + `LdapSchemaTest` | 15 green (`LdapSchemaTest` 5, `SchemaConventionTest`, `SchemaCommentTest`) |
| `3263521` | 4 + 5 — entity/input/view, kind-aware validation | `IdentityProviderEndpointTest` 23 green, `FederatedIdentityServiceTest`, `SsoFlowIntegrationTest` green |

`./gradlew build -x test` is currently **SUCCESSFUL**. The only red thing in the tree is one test in
`LdapDirectoryTest`.

## Uncommitted working tree (all of it Task 6)

```
backend/build.gradle.kts                                  spring-ldap-core + unboundid (test)
backend/gradle/libs.versions.toml                         the two entries, with the reason
backend/src/main/resources/application.yml                tcms.ldap.* (4 keys)
backend/src/main/java/com/tcms/auth/LdapProperties.java   new
backend/src/main/java/com/tcms/auth/LdapDirectory.java    new — compiles, diagnostics removed
backend/src/test/java/com/tcms/auth/LdapDirectoryTest.java  new — 6/7 green
backend/src/test/resources/ldap/f3-directory.ldif         new
```

Nothing else is dirty except `docs/audits/2026-08-15-flow-review-and-improvements.md`, which belongs
to **another lane — do not stage it.**

---

## Where Task 6 is stuck (the only open problem)

`LdapDirectoryTest.aCorrectBindResolvesAnEntry` fails. Six others "pass" — but read the next
paragraph before trusting them.

**The chain of causes already worked through, so you don't repeat it:**

1. `spring-boot-starter-ldap` **does not exist**. Boot ships `spring-boot-starter-data-ldap`, which
   drags in Spring Data LDAP and auto-configures ONE context source from `spring.ldap.urls` — wrong
   shape for a product with one directory per provider per company. **Resolved:** depend on
   `org.springframework.ldap:spring-ldap-core` alone. Already done, don't revisit.
2. `new SSLUtil(new TrustAllTrustManager())` gives the in-memory server a *trust* manager and **no
   certificate**, so every handshake failed. **Resolved:** `SelfSignedCertificateGenerator`
   + `KeyStoreKeyManager`. Already done.
3. **Still open — the certificate has no SAN matching the host.** With `ldaps://localhost` the JVM
   says *"No subject alternative DNS name matching localhost found"*; with `ldaps://127.0.0.1` it
   says *"No subject alternative names matching IP address 127.0.0.1 found"*. The generator builds
   its SAN list from `StaticUtils.getAllLocalAddresses` / `getAvailableCanonicalHostNames` and
   silently swallows a DNS-name validation failure (there is a `Debug.debugException` right after the
   `--subject-alternative-name-dns` argument is pushed), so `localhost` never makes it in.
   **Argument order is `generateTemporarySelfSignedCertificate(cn, keyStoreType)`** — confirmed by
   the error message when reversed; the current call is correct.

   **Suggested fix:** call `com.unboundid.util.ssl.cert.ManageCertificates` directly with explicit
   `--subject-alternative-name-dns localhost` and `--subject-alternative-name-ip-address 127.0.0.1`,
   instead of the convenience generator. That is the only remaining piece.

   **Do not "fix" this by disabling hostname verification.** The whole argument for D2 is that these
   tests drive the encrypted path production drives.

**A trap worth knowing:** every refusal in `LdapDirectory.authenticate` is an empty `Optional`, and an
unreachable directory is one of them — so a broken *test harness* is indistinguishable from a correct
refusal, and **six of these seven tests were green against a server that had never completed a TLS
handshake.** When this class goes green, confirm `aCorrectBindResolvesAnEntry` specifically passes;
the negative tests prove nothing on their own.

Unverified once it connects: whether UnboundID returns `entryUUID` (an operational attribute) for the
fixture entries. If `subjectOf` gets nothing, `findEntry` refuses. If that bites, the fixture or the
`userIdAttribute` in `target()` is where to look — **not** a DN, per D3.

---

## The four places the plan was wrong (all already handled — do not "restore" them)

1. **`V56` → `V57`.** §225 landed `V56__role_scope_agrees_with_the_grant_shape.sql` while this lane
   was paused. Plan and code are renumbered; the plan's lane-ownership section records it.
2. **`AuditEvent.jsonString` NPEs on null.** Every field the provider audit document carried was
   `NOT NULL` until V57; `clientId` is now null on directories and the five directory fields are null
   on issuers. This made **every OIDC registration NPE inside its own 201** — 13 pre-existing tests
   went red. Fixed with a local `orNull` in `IdentityProviderService`; the shared helper is untouched
   because its other callers all pass non-null.
3. **Three `applyEdit` call sites in existing tests** needed the eight new arguments
   (`FederatedIdentityServiceTest`, `SsoFlowIntegrationTest` ×2). The plan didn't mention them.
4. **`LdapSchemaTest` cannot use `(SELECT id FROM company LIMIT 1)`** — nothing seeds a company in
   that suite, so the subquery is NULL and the insert fails on `company_id NOT NULL` instead of the
   constraint under test, passing for the wrong reason. It seeds its own company now.

Also: `SchemaCommentTest` rejected `jwks_uri` in a V57 column comment (not a schema identifier). The
sentence was rewritten rather than adding an exemption — that test's javadoc argues the list should
stay at one entry.

## Two deviations from the plan already made deliberately

- **Task 2 committed the frontend too.** The plan commits the contract alone, but the new required
  fields broke `tsc` in three places, and CLAUDE.md says the contract and both sides land in one
  commit. Minimum adaptation only (fixtures + the dialog sending the directory half as nulls); an
  `aDirectoryProvider` fixture was added, which **Task 10 needs**.
- **`SsoProviderChoice.kind` uses `allOf: [{ $ref: … }]`** rather than a bare `$ref` with a sibling
  description, matching this file's house style (48 uses).

## Standing constraints for the rest of the slice

- Work on **`master`**, do not branch. Stage only this slice's paths (`git add <paths>`, never `-A`)
  — another lane is active in this tree.
- Commit per phase, after that phase's gate passes. Push after the lane.
- Tasks 10–11 are frontend: `npm run test:run` **and** `npm run build` **and** `npm run lint` (0
  warnings) are all gates.
- Task 12 wants the full gates on shanks via `~/bin/tcms-remote` (absolute path; not on PATH).
- Browser and backend-mutation checks are **deferred and batched** — Tasks 9 and 11 add rows to
  `docs/verification/*.md` and run nothing.
- Task 11's four browser rows need an LDAP server the compose stack does not have; the plan says to
  record that in the queue with the two options rather than skip silently.
