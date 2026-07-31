# §13. Backend M0.5 — the identity module and the permission evaluator (2026-07-30)

Run through the **`be-java`** skill (agent definition + `pattern-writer` + `database-code` + `code-review`), Mode B — extend. Closes §11's next-step list items 2 and 5's `identity` entry.

### What shipped

| Path | Purpose |
|---|---|
| `identity/{AppUser,CompanyMembership,Team,TeamMembership,Role,RoleAssignment,InstanceRoleAssignment,Permission}.java` | The nine entities, plus `UserStatus` / `MembershipStatus` / `RoleScope` and two `@IdClass` keys. Plain UUID FKs, no associations — matching `Project`. |
| `identity/PermissionEvaluator.java` | **The single place effective permissions are computed.** Nothing else may answer "may this user do X". |
| `identity/PermissionCatalogue.java` · `PermissionSet.java` | The 55 valid keys, loaded once; an immutable result whose `has()` **throws on a key that is not in the catalogue**. |
| `identity/*Repository.java` (8) | Including the two native union projections the evaluator reads. |
| `test/identity/PermissionEvaluatorTest.java` | **Personas 3 and 4**, the gate matrix, the Test Engineer→Test Lead boundary. 24 assertions. |
| `test/identity/AppUserEmailCaseTest.java` | Pins `citext` semantics through the JPA mapping. |
| `test/tenancy/CompanyFilterTest.java` | **The entity-level `company_id` check — it did not exist.** |

### `CompanyFilterTest` was documented but never written

`Project.java` has claimed since M0 that "`CompanyFilterTest` [enforces the filter] over every entity". It did not exist. Four more company-owned entities arrived this session, which is exactly the case it was meant to cover. It now derives the company-owned set from **`pg_class.relrowsecurity`** — the live truth — rather than a fourth hand-maintained copy of the list, and checks **both directions**: an RLS-protected table's entity must carry the filter, and an instance-level entity must *not* (a filter on `app_user` would break the login path). Same family as §6 lesson 9.

### The spec was ambiguous in exactly one place, and it mattered

`role_assignment`'s table comment lists the membership gate above the SYSTEM term, reading as though it gates everything. **It cannot**, and the evaluator resolves it deliberately: SYSTEM-scope grants are **not** behind the company-membership or company-status gate, because

- an Instance Admin **provisions** a company — there is no membership row yet, so gating would lock them out of the company they just created;
- suspension is recoverable only if SYSTEM survives it, and the policy is *suspend, never delete*.

The **account** gate does cover SYSTEM: a DEACTIVATED operator holds nothing, or deactivation is not the instance-wide off switch it is documented to be. All three are pinned by tests.

### Two real defects, both found by a test failing on its first run

| # | Defect | Why it mattered |
|---|---|---|
| 1 (HIGH) | **COMPANY-scope grants leaked onto a foreign project id.** `evaluate(user, projectId)` never checked that the project was in the *active* company. COMPANY-scope rows carry no `project_id`, so asking about a Globex project while Acme was active unioned in Acme's Company Admin grant and answered **yes** — i.e. it answered "does this user hold X *somewhere* in Acme". | The evaluator *is* the authorization gate. RLS still protects the row at read time, so this was defence-in-depth rather than a live leak — but a caller resolving a project id from a URL would get a false allow. Fixed by resolving the project through the company-filtered lookup first; unreachable and non-existent are now indistinguishable (§6t). |
| 2 (HIGH) | **`findByEmail` was case-sensitive.** Hibernate binds the parameter as `varchar`, so `email = ?` against a `citext` column resolves through `text` and compares case-sensitively — while the unique index stays case-insensitive. | The two disagree: `Alice@corp.test` cannot be found by `alice@…` **and** cannot re-register, because the index refuses the duplicate. A user who can neither sign in nor sign up. Invisible to any fixture that uses one casing throughout. Fixed with an explicit `CAST(:email AS citext)`. |

### `citext` vs `ddl-auto: validate` — and the tempting wrong fix

The context refuses to start: *"found [citext (Types#OTHER)], but expecting [varchar(255)]"*. **`@JdbcTypeCode(SqlTypes.OTHER)` is the obvious fix and is wrong** — Hibernate resolves `OTHER` to a binary descriptor and fails with *"Could not convert 'java.lang.String' to '[B'"*. The right lever is `@Column(columnDefinition = "citext")`: Hibernate's validator falls back to comparing the SQL type **name** when the codes differ. Binding stays `setString`, and Postgres coerces on assignment.

### Gate evidence

`./gradlew build` clean — **75/75 tests** (45 pre-existing + 27 evaluator/email + 3 filter).

**Mutation-verified.** Ten targeted mutations, one narrow edit each; nine failed exactly and only what they should:

| Mutation | Tests failed |
|---|---|
| membership gate never shuts | 1 — the INVITED gate |
| SYSTEM term put behind the company gate | 3 — both Instance Admin tests + the suspended-operator one |
| project-in-company check removed | 2 — both foreign-project tests (this is defect 1 reproduced) |
| company SUSPENDED ignored | 1 |
| account gate removed | 1 — the DEACTIVATED operator |
| team branch dropped from the PROJECT query | 2 — team grant + the Lead/Engineer boundary |
| `citext` cast removed from `findByEmail` | 1 (defect 2 reproduced) |
| company filter removed from `CompanyMembership` | 1 — `CompanyFilterTest` |
| `hasAny` short-circuits on first match | 1 |

**The tenth survived, and that is reported rather than hidden:** removing `company_id = :companyId` from the PROJECT-scope query breaks **nothing**, because RLS independently scopes `role_assignment`. That is defence in depth working, not dead code — but it means **no test covers the configuration V1 explicitly permits**, where a deployment disables RLS for planner cost. Noted in `RoleAssignmentRepository`'s javadoc so nobody deletes the predicate on the evidence that it is untested.

### One pre-existing test was changed, deliberately

`RlsIsolationTest` asserted `count(*) FROM instance_role_assignment` **is zero**, which was incidental to the table being empty rather than the property it names ("readable, not blocked"). The new personas seed operator grants, so it went stale. It now seeds its own SYSTEM grant and asserts **the app role sees exactly what the owner sees** — the real claim, and one that cannot go stale as fixtures grow. Verified passing standalone, so no test-ordering dependency was introduced.

### Deliberately not done

- **The `OWN` qualifier** (roles spec A5) — `case:edit` / `case:delete` / `evidence:delete` are OWN-qualified for Test Engineer, resolved against `created_by` at the call site. The evaluator cannot see the row being acted on, so it does not belong here.
- **Caching.** The table comment specifies a cache keyed on `(company_id, user_id, project_id)`. Correctness first: an uncached evaluator is slow, a wrongly-keyed one is a cross-company leak.
- **The JWT grant claims + `pv` revocation epoch** (§10) — still unlanded, and the evaluator is what they would be derived from.

### Still not started on the backend

~~The async-context test,~~ (**done, §14**) `provision-company`, Docker Compose, CI, ArchUnit/Modulith verification — and now also: the evaluator's cache, the `OWN` qualifier, and an RLS-disabled lane for the surviving mutation above.
