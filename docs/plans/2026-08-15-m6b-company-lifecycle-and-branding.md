# M6 Plan B — Suspension, Branding, and the Company's Own Settings

**Date:** 2026-08-15 · **Written by:** `ep-writing-plans` · **Master plan:** `docs/plans/2026-08-15-m6-multi-company-activation.md`

> **For agentic workers:** REQUIRED SUB-SKILL: Use `ep-executing-plans`. **Checkpoint after every
> task.** Backend work loads `/be-java`; frontend work loads `/fe-react`, plus
> `fe-react-styling` for Task 6 and `fe-react-accessibility` for the contrast rule. `ep-code-comments`
> governs every comment.

**Depends on:** Plan A (`CompanyRef`, `CurrentUser.company`). Do not start until Plan A's Task 3 has
landed — Tasks 5 and 6 here read `CurrentUser.company`.

**Goal:** Give three `company` columns their first reader in the product's history — `status`,
`brand_*` and `logo_storage_key` — and make the company a thing its own administrator can configure
and its own users can see.

**Architecture:** Suspension is a servlet filter after `CompanyContextFilter`, so it reads the
company from the same signed claim everything else does and Instance Admins stay exempt. Branding is
two colours in, a whole MUI palette out, with contrast computed rather than configured and validated
at *save* time so a client cannot ship themselves an unreadable execution screen. Semantic
Pass/Fail/Blocked colours are structurally unreachable from branding, not merely undocumented as
brandable.

**Tech Stack:** Spring Boot 3.5.6 · Java 21 · Flyway (**V54**, pre-assigned — see Task 3 for whether
any DDL is actually owed) · Micrometer · React 19 · MUI v7 · Vitest + RTL

**Fixture UUID prefix for this plan:** `d1000000-…`. **Slug namespace for new tests:** `m6b-`.

---

## Orientation — four facts, verified 2026-08-15 at `8c2bacb`

Do not re-derive these.

1. **`Company` maps six columns and only six**: `id`, `slug`, `displayName`, `status`, `createdAt`,
   `lockVersion` (`Company.java:28-50`). `logo_storage_key`, `brand_primary_color`,
   `brand_accent_color`, `favicon_emoji` and `storage_quota_bytes` **exist in `V1` and are unmapped**
   (`V1:35-39`). `ReportHeader.java:55-56` says so in as many words. So branding needs **no
   migration** — it needs five `@Column` fields and a writer.
2. **`suspend()` and `reinstate()` exist on the entity** (`Company.java:83-89`) and **nothing calls
   either** anywhere in `src/main`. Every other `SUSPENDED` hit is a comment, including
   `PermissionEvaluator.java:384-388`, which records that the permission gate deliberately does not
   honour it.
3. **Suspension is already half-enforced, at login only.** `tcms_companies_for_user`'s loop is
   `SELECT id FROM company WHERE status = 'ACTIVE'` (`V8:66`), so a suspended company is not offered
   as a default company and — after Plan A — is not in `GET /me/companies` either. **A token minted
   before the suspension still carries that `co` claim and still works.** That gap is Task 1, and it
   is the whole reason a filter is needed on top of a status column.
4. **`CompanyContext` exposes `current()` returning `Optional<UUID>`** (`CompanyContext.java:98`) and
   `require()` (`:117`). There is **no `currentOrNull()`** — the operator-console plan's Task 1
   snippet names one that does not exist. Use `CompanyContext.current().orElse(null)`; do not add a
   getter, because the guard shape is what stops a company outliving its block.
5. **The filter chain order** is `apiTokenFilter` → `permissionVersionFilter` → `rateLimitFilter` →
   `companyContextFilter` (`SecurityConfiguration.java:106-109`). The new filter goes after the last
   of those.
6. **There is no custom Micrometer instrumentation at all.** `spring-boot-starter-actuator` is a
   dependency (`build.gradle.kts:23`) and `MeterRegistry` appears nowhere in `src/main`. Task 7 is a
   first meter, not an extra tag.

---

## Preconditions and gates

Identical to Plan A: `docker compose up` for every backend test; work in
`.claude/worktrees/m6-multi-company`; stage only the paths each task names.

```bash
cd backend  && ./gradlew build
cd frontend && npm run build && npm run lint && npm run test:run
cd packages && npm run build && npm run lint --workspaces && npm run test:run --workspaces
cd frontend && npm run gen:api && cd ../packages/client && npm run gen:api   # after ANY openapi edit
```

---

## File Structure

**Backend — create**

| File | Responsibility |
|---|---|
| `backend/src/main/java/com/tcms/auth/SuspendedCompanyFilter.java` | Honours `status = SUSPENDED` on every authenticated request. |
| `backend/src/main/java/com/tcms/tenancy/CompanyBranding.java` | The four branding values, as one embeddable/record — and the place the contrast rule is enforced. |
| `backend/src/main/java/com/tcms/tenancy/BrandColor.java` | A validated `#rrggbb`, with relative luminance and a contrast ratio. |
| `backend/src/main/java/com/tcms/tenancy/CompanySettingsService.java` | Read and write the company's own settings, gated. |
| `backend/src/main/java/com/tcms/tenancy/CompanySettingsController.java` | `GET/PUT /api/v1/company`, `PUT/DELETE /api/v1/company/logo`. |
| `backend/src/main/java/com/tcms/tenancy/CompanyMetrics.java` | Per-company Micrometer tags on request, ingestion and report timers. |

**Backend — modify**

`tenancy/Company.java` (five columns get mapped, plus `rebrand`), `auth/SecurityConfiguration.java`
(register the filter), `docs/specs/openapi.yaml`.

**Backend — test**

`auth/SuspendedCompanyFilterTest.java`, `tenancy/BrandColorTest.java`,
`tenancy/CompanySettingsEndpointTest.java`, `tenancy/CompanyMetricsTest.java` — all new.

**Frontend — create**

`src/app/brandedTheme.ts` (the palette factory), `src/features/settings/BrandingSettingsPage.tsx`
+ its `.test.tsx`, `src/features/settings/BrandColorField.tsx`.

**Frontend — modify**

`src/app/theme.ts` (extract the base palette so the factory can reuse it), `src/app/Providers.tsx`
(the theme becomes a function of the session's company), `src/api/queries/keys.ts`, a company query
module, `src/lib/labels.ts` (the suspended-company copy).

---

## Task 1: `SuspendedCompanyFilter` — the column gets a reader

Adapted from the operator-console plan's Task 1, corrected in three places (the `CompanyContext`
accessor, the error-body shape, and the second test, which that plan could not compile).

**Files:**
- Create: `backend/src/main/java/com/tcms/auth/SuspendedCompanyFilter.java`
- Modify: `backend/src/main/java/com/tcms/auth/SecurityConfiguration.java`
- Test: `backend/src/test/java/com/tcms/auth/SuspendedCompanyFilterTest.java`

- [ ] **Step 1: Write the failing test**

`backend/src/test/java/com/tcms/auth/SuspendedCompanyFilterTest.java`. It owns the **`m6b-susp-`**
slug namespace. Build fixtures the way `AuthEndpointTest` does (`:363-400`): provision with
`CompanyProvisioningService`, accept with `InvitationService.accept`, log in over
`POST /api/v1/auth/login`.

```java
package com.tcms.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.tcms.IntegrationTestBase;
import com.tcms.tenancy.Company;
import com.tcms.tenancy.CompanyRepository;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The status column, honoured.
 *
 * <p>{@code Company.suspend()} has carried the sentence "must be honoured in code … the status
 * column alone enforces nothing" since V1, and had no reader anywhere in {@code src/main} until
 * this filter. Owns the {@code m6b-susp-} slug namespace.
 */
@AutoConfigureMockMvc
@DisplayName("A suspended company")
class SuspendedCompanyFilterTest extends IntegrationTestBase {

    @Autowired private MockMvc mvc;
    @Autowired private CompanyRepository companies;

    @Test
    @DisplayName("refuses an ordinary member's request with a 403 that says why")
    void refusesAnOrdinaryMembersRequest() throws Exception {
        Member member = anActivatedMemberOf("m6b-susp-a");
        // The token is minted BEFORE the suspension — which is the whole gap. Login already
        // refuses to pick a suspended company (V8's loop filters on status = 'ACTIVE'), so a
        // fresh sign-in would never produce this token and the filter would never be reached.
        String token = member.token();

        suspend(member.companyId());

        var response = mvc.perform(get("/api/v1/projects")
                .header("Authorization", "Bearer " + token)).andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString())
                .as("the caller can do nothing about this and needs to know who can")
                .contains("suspended");
    }

    @Test
    @DisplayName("still admits an Instance Admin, or the suspension is unrecoverable")
    void stillAdmitsAnInstanceAdmin() throws Exception {
        Member operator = anInstanceAdminInsideCompany("m6b-susp-b");
        suspend(operator.companyId());

        assertThat(mvc.perform(get("/api/v1/projects")
                        .header("Authorization", "Bearer " + operator.token()))
                .andReturn().getResponse().getStatus())
                .isEqualTo(200);
    }

    @Test
    @DisplayName("does not touch a company-context-less operator session")
    void aCompanyLessSessionPassesThrough() throws Exception {
        // An Instance Admin who belongs to no company has no `co` claim at all. There is
        // nothing to suspend, and the instance console must keep working.
        assertThat(mvc.perform(get("/api/v1/me")
                        .header("Authorization", "Bearer " + aCompanyLessOperatorToken()))
                .andReturn().getResponse().getStatus())
                .isEqualTo(200);
    }

    private void suspend(UUID companyId) {
        Company company = companies.findById(companyId).orElseThrow();
        company.suspend();
        companies.save(company);
    }
}
```

> `Member`, `anActivatedMemberOf`, `anInstanceAdminInsideCompany`, `aCompanyLessOperatorToken` are
> private helpers you write here. For the operator ones, `GrantInstanceAdminService.grant(email,
> actorUserId)` is the writer of `instance_role_assignment` and takes the actor as a parameter with
> `null` documented as legitimate.
>
> **`companies.save(company)` runs under RLS with no company context open in a test method** — check
> whether the surrounding tests wrap such writes in `CompanyContext.open(...)`. `company` carries no
> RLS policy (it is an ERD §0.1 instance-level table), so this should work; if it does not, that is a
> finding worth reporting, not a fixture to work around.

- [ ] **Step 2: Run it and watch it fail**

```bash
cd backend && ./gradlew test --tests 'com.tcms.auth.SuspendedCompanyFilterTest'
```
Expected: FAIL — the first test gets `200`, because nothing reads the status.

- [ ] **Step 3: Write the filter**

`backend/src/main/java/com/tcms/auth/SuspendedCompanyFilter.java`:

```java
package com.tcms.auth;

import com.tcms.tenancy.CompanyContext;
import com.tcms.tenancy.CompanyRepository;
import com.tcms.tenancy.CompanyStatus;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Honours {@code company.status = SUSPENDED}, which until now nothing did.
 *
 * <h2>Why a filter, when login already refuses a suspended company</h2>
 *
 * <p>{@code tcms_companies_for_user} walks {@code company WHERE status = 'ACTIVE'} (V8), so a
 * suspended company is never picked as a session's default and never offered by
 * {@code GET /me/companies}. That closes the front door and leaves every token minted
 * <em>before</em> the suspension working perfectly — for as long as its TTL, which for a
 * remember-me session is not short. Suspension is the offboarding answer (§6s); an offboarding
 * that takes effect whenever the client next happens to sign in is not one.
 *
 * <h2>Instance Admins are exempt, deliberately</h2>
 *
 * <p>The operator is who reverses a suspension. Locking them out of the company they just
 * suspended would make the state unrecoverable through the very console that exists to manage it.
 * This is the one place in the product where {@code isSystemAdmin} gates a request rather than an
 * affordance, and it is doing the opposite of granting reach: it is declining to remove it.
 *
 * <h2>Placement</h2>
 *
 * <p>Registered after {@link CompanyContextFilter}, so the active company is already resolved from
 * the signed claim rather than from request input (ERD §0.1 point 2). A company-context-less
 * session — an operator belonging to no company — passes straight through: there is nothing to
 * suspend, and the instance console must keep working while every company on the instance is
 * suspended.
 */
@Component
public class SuspendedCompanyFilter extends OncePerRequestFilter {

    private final CompanyRepository companies;
    private final CurrentUserService users;

    public SuspendedCompanyFilter(CompanyRepository companies, CurrentUserService users) {
        this.companies = companies;
        this.users = users;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        UUID companyId = CompanyContext.current().orElse(null);
        if (companyId == null) {
            chain.doFilter(request, response);
            return;
        }

        boolean suspended = companies.findById(companyId)
                .map(company -> company.getStatus() == CompanyStatus.SUSPENDED)
                .orElse(false);

        if (suspended && !callerIsAnInstanceAdmin()) {
            refuse(response);
            return;
        }
        chain.doFilter(request, response);
    }

    private boolean callerIsAnInstanceAdmin() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof Jwt jwt)) {
            return false;
        }
        return users.describe(UUID.fromString(jwt.getSubject()), null).isSystemAdmin();
    }

    private void refuse(HttpServletResponse response) throws IOException {
        // …
    }
}
```

- [ ] **Step 4: Write the refusal body the way this repo writes refusal bodies**

**Do not hand-write a JSON literal.** §146 deleted one, §163 declined to reintroduce one, and
`ErrorBodyPolicyTest` pins the shape of both envelopes. Open
`backend/src/main/java/com/tcms/api/Problem.java` and `ProblemEnvelope.java` and emit through them —
this filter runs outside the `@ControllerAdvice` chain, so it must serialize a `Problem` itself, the
way `RateLimitFilter` already does for its 429. **Read `RateLimitFilter` and copy its mechanism
exactly**; it is the existing precedent for a filter-written refusal and it is already covered by the
error-body rules.

The `detail` says what to do, per §141:

> `This company is suspended. Its data is intact; contact your instance operator to have access restored.`

- [ ] **Step 5: Register it**

In `SecurityConfiguration.java`, inject `SuspendedCompanyFilter` alongside the other four filter
parameters (`:75-78`) and add to the chain after the `companyContextFilter` line (`:109`):

```java
                .addFilterAfter(suspendedCompanyFilter, CompanyContextFilter.class)
```

- [ ] **Step 6: Run the test**

```bash
cd backend && ./gradlew test --tests 'com.tcms.auth.SuspendedCompanyFilterTest'
```
Expected: PASS, all three.

- [ ] **Step 7: Run the whole suite**

This filter sits in front of every authenticated request, so a mistake fails broadly and you want to
know now.

```bash
cd backend && ./gradlew build
```
Expected: BUILD SUCCESSFUL. **`ErrorBodyPolicyTest` is the one to watch** — it asserts each
envelope's serialized key set is a subset of what the contract declares.

- [ ] **Step 8: Correct the two comments that say nothing reads the column**

`Company.java:76-84` ("the status column alone enforces nothing") and
`PermissionEvaluator.java:384-388` (which explains why the permission gate deliberately does not
honour suspension). The first becomes a pointer to the filter; the second **stays true and stays**,
because it is about the *evaluator*, not about the product — add one clause naming where the check
now lives.

- [ ] **Step 9: Commit**

```bash
git add backend/src/main/java/com/tcms/auth/SuspendedCompanyFilter.java \
        backend/src/main/java/com/tcms/auth/SecurityConfiguration.java \
        backend/src/main/java/com/tcms/tenancy/Company.java \
        backend/src/main/java/com/tcms/identity/PermissionEvaluator.java \
        backend/src/test/java/com/tcms/auth/SuspendedCompanyFilterTest.java
git commit -m "feat(tenancy): honour company suspension on every authenticated request

The status column has had a writer since V1 and no reader anywhere in src/main.
Login already declined to pick a suspended company; every token minted before the
suspension kept working, which is an offboarding that takes effect whenever the
client next happens to sign in. Instance Admins stay exempt so the state remains
reversible from the console that manages it.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

## Task 2: `BrandColor` — a colour that can prove it is readable

§6u's constraint is the whole feature: *"a client-chosen brand colour is exactly how [the WCAG AA
commitment] silently breaks — a light-on-light accent renders a run's Pass/Fail buttons
unreadable."* So the colour type carries the contrast arithmetic, and the validation is a property
of the value rather than a check somebody remembered to call.

This is a pure-logic task with no Spring context — `be-java-unit-testing`'s lane.

**Files:**
- Create: `backend/src/main/java/com/tcms/tenancy/BrandColor.java`
- Test: `backend/src/test/java/com/tcms/tenancy/BrandColorTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.tcms.tenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("A brand colour")
class BrandColorTest {

    @ParameterizedTest
    @ValueSource(strings = {"#2e6e8e", "#FFFFFF", "#000000"})
    @DisplayName("accepts six-digit hex, case-insensitively")
    void acceptsSixDigitHex(String value) {
        assertThat(BrandColor.of(value).hex()).isEqualToIgnoringCase(value);
    }

    @ParameterizedTest
    @ValueSource(strings = {"2e6e8e", "#2e6e8", "#gggggg", "rgb(1,2,3)", "red", "#2e6e8e80"})
    @DisplayName("refuses anything else, including a form a browser would accept")
    void refusesEverythingElse(String value) {
        // Three digits and eight digits are both valid CSS and neither is storable in a
        // column this product renders through MUI palette generation, which needs a
        // literal opaque value to compute contrast text from.
        assertThatThrownBy(() -> BrandColor.of(value))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("computes the WCAG contrast ratio against a surface")
    void computesContrast() {
        // The reference pair: pure black on pure white is exactly 21:1.
        assertThat(BrandColor.of("#000000").contrastAgainst(BrandColor.of("#ffffff")))
                .isCloseTo(21.0d, org.assertj.core.data.Offset.offset(0.01d));
        assertThat(BrandColor.of("#ffffff").contrastAgainst(BrandColor.of("#ffffff")))
                .isCloseTo(1.0d, org.assertj.core.data.Offset.offset(0.01d));
    }

    @Test
    @DisplayName("picks the readable foreground rather than accepting one")
    void picksAReadableForeground() {
        assertThat(BrandColor.of("#1b2a41").readableForeground().hex())
                .isEqualToIgnoringCase("#ffffff");
        assertThat(BrandColor.of("#f5f7fa").readableForeground().hex())
                .isEqualToIgnoringCase("#000000");
    }

    @Test
    @DisplayName("knows when it cannot meet AA on a surface it will actually be used on")
    void knowsWhenItFailsAa() {
        // 4.5:1 is AA for normal text; 3:1 is AA for large text and UI components.
        assertThat(BrandColor.of("#f2e6b8").meetsAaAgainst(BrandColor.of("#ffffff"))).isFalse();
        assertThat(BrandColor.of("#2e6e8e").meetsAaAgainst(BrandColor.of("#ffffff"))).isTrue();
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

```bash
cd backend && ./gradlew test --tests 'com.tcms.tenancy.BrandColorTest'
```
Expected: FAIL — `BrandColor` does not exist.

- [ ] **Step 3: Implement it**

```java
package com.tcms.tenancy;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * A brand colour that can answer whether it is readable.
 *
 * <h2>Why the arithmetic lives on the value</h2>
 *
 * <p>§6 commits to WCAG 2.1 AA on the execution screens and §6u names the way that commitment
 * silently breaks: a client picks a light accent, and a run's Pass/Fail buttons stop being
 * readable — on the one screen where misreading a colour has a cost. A validation that lives in a
 * service is one an endpoint can forget to call; a value that cannot be constructed without being
 * measurable cannot be stored unmeasured.
 *
 * <h2>Six digits, and nothing else</h2>
 *
 * <p>{@code #rgb} and {@code #rrggbbaa} are both valid CSS and neither is accepted. MUI computes
 * contrast text and alpha channels from a literal opaque value, so a three-digit or alpha form
 * would have to be normalised somewhere — and "somewhere" is how two normalisations end up
 * disagreeing about what the client chose.
 */
public record BrandColor(String hex) {

    private static final Pattern SIX_DIGIT_HEX = Pattern.compile("^#[0-9a-fA-F]{6}$");

    /** AA for normal text. The stricter of the two thresholds, and the default. */
    public static final double AA_NORMAL_TEXT = 4.5d;

    /** AA for large text and non-text UI components — buttons, chips, focus rings. */
    public static final double AA_LARGE_TEXT = 3.0d;

    public BrandColor {
        if (hex == null || !SIX_DIGIT_HEX.matcher(hex).matches()) {
            throw new IllegalArgumentException(
                    "A brand colour must be six-digit hex like #2e6e8e, not '" + hex + "'");
        }
        hex = hex.toLowerCase(Locale.ROOT);
    }

    public static BrandColor of(String hex) {
        return new BrandColor(hex);
    }

    /**
     * Relative luminance, per WCAG 2.1's definition — the sRGB channels linearised with the
     * 0.03928 / 12.92 piecewise transfer function, then weighted 0.2126 / 0.7152 / 0.0722.
     *
     * <p>Written out rather than approximated: the common shortcut (a plain average, or
     * luminance without linearisation) is wrong by enough to pass a colour that fails AA, which
     * makes the check worse than absent because it is believed.
     */
    public double relativeLuminance() {
        return 0.2126d * linear(channel(1)) + 0.7152d * linear(channel(3)) + 0.0722d * linear(channel(5));
    }

    public double contrastAgainst(BrandColor surface) {
        double a = relativeLuminance();
        double b = surface.relativeLuminance();
        double lighter = Math.max(a, b);
        double darker = Math.min(a, b);
        return (lighter + 0.05d) / (darker + 0.05d);
    }

    public boolean meetsAaAgainst(BrandColor surface) {
        return contrastAgainst(surface) >= AA_NORMAL_TEXT;
    }

    /** Black or white, whichever reads better on this colour. Computed, never configured. */
    public BrandColor readableForeground() {
        BrandColor white = new BrandColor("#ffffff");
        BrandColor black = new BrandColor("#000000");
        return contrastAgainst(white) >= contrastAgainst(black) ? white : black;
    }

    private int channel(int offset) {
        return Integer.parseInt(hex.substring(offset, offset + 2), 16);
    }

    private static double linear(int eightBit) {
        double c = eightBit / 255.0d;
        return c <= 0.03928d ? c / 12.92d : Math.pow((c + 0.055d) / 1.055d, 2.4d);
    }
}
```

- [ ] **Step 4: Run the test**

```bash
cd backend && ./gradlew test --tests 'com.tcms.tenancy.BrandColorTest'
```
Expected: PASS. If `knowsWhenItFailsAa`'s two literals do not land either side of 4.5:1 on your
implementation, **check the arithmetic before changing the literals** — `#000000` on `#ffffff` being
exactly 21.0 is the reference that says the transfer function is right.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/tcms/tenancy/BrandColor.java \
        backend/src/test/java/com/tcms/tenancy/BrandColorTest.java
git commit -m "feat(tenancy): a brand colour that can prove it is readable

WCAG 2.1 relative luminance and contrast on the value itself, so a colour cannot
be stored unmeasured. Six-digit hex only: #rgb and #rrggbbaa are valid CSS and
would each need a normalisation, which is how two normalisations disagree.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

## Task 3: The `Company` entity grows its branding columns

**No migration is owed.** The columns are in `V1:35-39`. `V54` stays reserved by the master plan in
case this task discovers otherwise — if it does, take `V54` and say why in its header.

**Files:**
- Modify: `backend/src/main/java/com/tcms/tenancy/Company.java`
- Create: `backend/src/main/java/com/tcms/tenancy/CompanyBranding.java`
- Test: `backend/src/test/java/com/tcms/tenancy/CompanyBrandingTest.java`

- [ ] **Step 1: Write the failing test**

```java
package com.tcms.tenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("A company's branding")
class CompanyBrandingTest {

    @Test
    @DisplayName("refuses a primary colour that cannot meet AA on the light surface")
    void refusesAnUnreadablePrimary() {
        assertThatThrownBy(() -> CompanyBranding.of("#f2e6b8", "#2e6e8e", "🏢"))
                .isInstanceOf(UnreadableBrandColorException.class)
                .hasMessageContaining("#f2e6b8")
                .as("§6u: tell the admin what was rejected; do not silently ignore their choice");
    }

    @Test
    @DisplayName("accepts a readable pair and derives the foregrounds rather than storing them")
    void derivesForegrounds() {
        CompanyBranding branding = CompanyBranding.of("#2e6e8e", "#1b2a41", "🏢");

        assertThat(branding.primary().hex()).isEqualTo("#2e6e8e");
        assertThat(branding.primaryForeground().hex()).isEqualToIgnoringCase("#ffffff");
    }

    @Test
    @DisplayName("is entirely absent rather than partly set")
    void isAllOrNothing() {
        // A company with a primary colour and no accent is a theme half-derived from client
        // input and half from the default — two colour systems on one screen, which is the
        // failure branding exists to avoid.
        assertThatThrownBy(() -> CompanyBranding.of("#2e6e8e", null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(CompanyBranding.none().isSet()).isFalse();
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

```bash
cd backend && ./gradlew test --tests 'com.tcms.tenancy.CompanyBrandingTest'
```
Expected: FAIL — neither `CompanyBranding` nor `UnreadableBrandColorException` exists.

- [ ] **Step 3: Write `CompanyBranding` and the exception**

`CompanyBranding` is a record of `(BrandColor primary, BrandColor accent, String faviconEmoji,
String logoStorageKey)` with a `none()` empty instance, an `isSet()`, and `primaryForeground()` /
`accentForeground()` delegating to `BrandColor.readableForeground()`. Its factory validates **both**
colours against the two surfaces they will actually be used on — the light background `#f5f7fa` and
the dark background `#0f141b`, both read from `theme.ts`'s palette (see the note in Task 6 about
those literals living in two files) — and throws `UnreadableBrandColorException` naming the colour
and the ratio it achieved.

```java
/**
 * A colour a client chose that cannot be rendered readably.
 *
 * <p>Thrown at save time, not at render time. §6u: "validate contrast at save time and reject or
 * auto-adjust a colour that can't meet AA against the surfaces it will be used on. Tell the admin
 * what was adjusted; don't silently ignore their choice." This product rejects rather than
 * auto-adjusts, because an auto-adjusted brand colour is a client's brand rendered wrong with no
 * indication that it happened — and the admin can pick another shade in five seconds.
 */
public class UnreadableBrandColorException extends RuntimeException { … }
```

> Map it to a 422 (or whatever this repo's validation-failure status is — **check
> `openapi.yaml` and the existing `@ControllerAdvice` for the convention**) with the offending colour
> in `errors`, per §141: `detail` says what to do, `errors` says which field.

- [ ] **Step 4: Map the columns on `Company`**

Add to `Company.java`, beside the six existing fields:

```java
    @Column(name = "logo_storage_key")
    private String logoStorageKey;

    @Column(name = "brand_primary_color")
    private String brandPrimaryColor;

    @Column(name = "brand_accent_color")
    private String brandAccentColor;

    @Column(name = "favicon_emoji")
    private String faviconEmoji;
```

and the writer beside `rename`:

```java
    /**
     * Sets or clears the company's branding (§6u).
     *
     * <p>Takes a {@link CompanyBranding} rather than four strings, so the contrast validation
     * cannot be bypassed by a caller that sets the columns one at a time — the whole point of
     * validating at save time is that there is one gate and it is not optional.
     */
    public void rebrand(CompanyBranding branding) { … }

    public CompanyBranding branding() { … }
```

> Leave `storage_quota_bytes` unmapped. It is on §6s's *"ship if storage becomes contended"* list and
> the master plan keeps quotas deferred; mapping it now would create a column with a reader, no
> writer and no enforcement — the exact shape `status` has been in since V1.

- [ ] **Step 5: Run the test, then the suite**

```bash
cd backend && ./gradlew test --tests 'com.tcms.tenancy.CompanyBrandingTest'
cd backend && ./gradlew build
```

Expected: BUILD SUCCESSFUL. **`hibernate.ddl-auto: validate` means booting the context is itself the
gate on these mappings** — a column name typo fails at startup, not at first read.

- [ ] **Step 6: Correct `ReportHeader`'s comment**

`reporting/ReportHeader.java:55-56` says the branding columns *"are unmapped and have no writer
anywhere (branding is M6)"*. Half of that is now false. Rewrite it to say what is true — mapped, with
a writer, and **still not read by the report header**, which is Plan B Task 5's or a later slice's
job. Do not quietly delete it; a stale comment deleted without replacement loses the pointer.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/com/tcms/tenancy/Company.java \
        backend/src/main/java/com/tcms/tenancy/CompanyBranding.java \
        backend/src/main/java/com/tcms/tenancy/UnreadableBrandColorException.java \
        backend/src/main/java/com/tcms/reporting/ReportHeader.java \
        backend/src/test/java/com/tcms/tenancy/CompanyBrandingTest.java
git commit -m "feat(tenancy): the branding columns get mapped and a validated writer

Four columns from V1 that have never been mapped. rebrand() takes a validated
CompanyBranding rather than four strings, so the contrast gate is not something a
caller can go around by setting columns one at a time.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

## Task 4: `GET/PUT /api/v1/company` — the company's own settings

**Files:**
- Create: `backend/src/main/java/com/tcms/tenancy/CompanySettingsService.java`, `CompanySettingsController.java`, `CompanySettingsView.java`, `CompanyBrandingInput.java`
- Modify: `docs/specs/openapi.yaml`
- Test: `backend/src/test/java/com/tcms/tenancy/CompanySettingsEndpointTest.java`

- [ ] **Step 1: Write the failing tests**

Owns the **`m6b-set-`** slug namespace.

```java
@Test
@DisplayName("every member may read the company, because company:read is the baseline")
void anyMemberMayRead() throws Exception { … expect 200 for a Viewer … }

@Test
@DisplayName("only company:settings may write it")
void onlyAnAdminMayWrite() throws Exception { … expect 403 for a Test Engineer … }

@Test
@DisplayName("refuses an unreadable brand colour and names it")
void refusesAnUnreadableColour() throws Exception {
    var response = putBranding(adminToken, "#f2e6b8", "#2e6e8e");
    assertThat(response.getStatus()).isEqualTo(422);
    assertThat(response.getContentAsString()).contains("#f2e6b8");
}

@Test
@DisplayName("a wrong lock_version loses to the writer who got there first")
void concurrentEditsAreRefused() throws Exception { … expect 409 … }
```

> **`company:read` is held by every member through the non-assignable `Company Member` baseline
> (`V15`), not through the six roles** — and it is answered from the SYSTEM and COMPANY terms only.
> A Viewer's `company:read` therefore resolves through the baseline. Assert it as a *member*, not as
> a Viewer-the-role, or you are asserting the thing §43 already found to be broken and repaired.

- [ ] **Step 2: Run and watch fail** — `cd backend && ./gradlew test --tests 'com.tcms.tenancy.CompanySettingsEndpointTest'` → 404.

- [ ] **Step 3: Write the service and controller**

`CompanySettingsService` reads and writes through `CompanyRepository` with the gate applied by the
same `PermissionGate` idiom every other write service in this repo uses — **open one
(`grep -rln "PermissionGate" backend/src/main/java/com/tcms | head`) and follow it exactly.** The
read is gated on `company:read`, the write on `company:settings`, both COMPANY scope, both resolved
by `PermissionEvaluator.evaluateCompanyWide`.

The controller is `GET /api/v1/company` and `PUT /api/v1/company`. **There is no `{companyId}` path
variable and there must not be one** — the company comes from the session and from nothing else.

- [ ] **Step 4: Add both paths to the contract**, with the description carrying the two rules that are
easy to lose: no path variable (ERD §0.1 point 2), and 422 with the colour named rather than a
silent auto-adjust. Regenerate both `schema.d.ts` copies.

- [ ] **Step 5: Run the tests and the gates.**

- [ ] **Step 6: Commit.**

---

## Task 5: The logo, through the storage module

**Files:**
- Modify: `backend/src/main/java/com/tcms/tenancy/CompanySettingsService.java`, `CompanySettingsController.java`
- Modify: `docs/specs/openapi.yaml`
- Test: `backend/src/test/java/com/tcms/tenancy/CompanySettingsEndpointTest.java`

- [ ] **Step 1: Read the storage module's contract before writing anything**

`storage` is a **foundation module** and declares `tenancy` alone (§125, decision #51) — so `tenancy`
**cannot depend on `storage`**, and ArchUnit will say so. Open
`backend/src/main/java/com/tcms/storage/package-info.java` and `AttachmentStore.java`'s header and
work out which of these the logo is:

- an `Attachment` row with an `AttachmentOwner` of a new kind, stored through `AttachmentStore`; or
- a blob under the existing `company/{companyId}/` prefix (`BlobUpload.key(companyId, ownerId)`), with
  only the key on `company.logo_storage_key`; or
- a reason the endpoint belongs in a module that may depend on both.

**This is a genuine design decision and it is the one thing in Plan B I have deliberately not
decided for you** — deciding it from the module graph rather than from convenience is the point, and
the wrong answer here is a foundation module quietly gaining a fifth dependency, which the master
plan's §1 note calls "a decision, not an import". Write the decision into the service's javadoc.

- [ ] **Step 2: Write the failing test** — upload a PNG, read it back, assert the key is under
  `company/{companyId}/`, assert a 415 for a content type that is not an image, and assert a size cap.

- [ ] **Step 3–6:** implement, contract, gates, commit — following whichever module placement Step 1
  decided.

---

## Task 6: The theme becomes a function of the company

**Files:**
- Modify: `frontend/src/app/theme.ts`
- Create: `frontend/src/app/brandedTheme.ts` + `brandedTheme.test.ts`
- Modify: `frontend/src/app/Providers.tsx`

- [ ] **Step 1: Understand what makes this hard, before editing**

`theme.ts` is a module-level constant with literal hexes, and its own header records that those
hexes are **mirrored into `index.css`** as CSS custom properties — *"this is the one accepted
duplication"*. Branding at runtime breaks that arrangement: a theme built from the company's colours
diverges from the static `index.css` values the moment it differs from the default.

**So the factory must set the CSS custom properties too**, on `:root`, at the same moment it builds
the palette — or the app renders MUI components in the client's colours and everything styled through
a token in its own. Write that constraint into `brandedTheme.ts`'s header; it is the non-obvious part
of this task and the part a later reader will otherwise undo.

- [ ] **Step 2: Extract the base palette**

Split `theme.ts` so the light/dark palettes are values the factory can spread over, and
`export const theme` becomes `createBrandedTheme(null)`. **`statusColors` and `lifecycleColors` stay
exactly where they are and are not parameters of the factory** — that is what makes §6u's "semantic
status colours are not brandable" structural rather than documentary.

- [ ] **Step 3: Write the failing test**

```ts
it('derives a contrast-safe foreground rather than accepting one', () => {
  const t = createBrandedTheme({ primary: '#1b2a41', accent: '#2e6e8e' });
  expect(t.colorSchemes.light.palette.primary.contrastText).toBe('#ffffff');
});

it('leaves the semantic status colours alone whatever the brand is', () => {
  const t = createBrandedTheme({ primary: '#c0392b', accent: '#c0392b' });
  // A client whose brand is red must not end up with red "Passed" chips, and a tester
  // moving between two clients must not have to relearn what green means (§6u).
  expect(statusColors.PASSED.light).toBe('#2e7d5b');
  expect(t.colorSchemes.light.palette.success.main).toBe('#2e7d5b');
});

it('falls back to the product palette when the company has no branding', () => {
  expect(createBrandedTheme(null)).toEqual(theme);
});
```

- [ ] **Step 4: Implement `createBrandedTheme`** — MUI's `createTheme` computes `contrastText` from
  `main` when it is not given, which is precisely the "derive, don't accept" §6u asks for; let it,
  and do not pass `contrastText`.

- [ ] **Step 5: Wire it in `Providers.tsx`**

**`Providers.tsx`'s nesting order is load-bearing and documented in the file** — the query client is
constructed innermost because its global error handlers consume the notification and
session-recovery providers. The theme provider's position must not change; only the *value* it is
given becomes a function of `useAuth().company`. Read the file's header before touching it.

- [ ] **Step 6: Gates and commit.**

---

## Task 7: Per-company metric tags

§6s's *"ship immediately (near-free)"* item: *"Micrometer tags by company on request, ingestion and
report timers, so the operator can answer 'which company is making the instance slow' the first time
it's asked. Company count is small and bounded, so this is a safe tag — unlike project or user."*

**Files:**
- Create: `backend/src/main/java/com/tcms/tenancy/CompanyMetrics.java`
- Test: `backend/src/test/java/com/tcms/tenancy/CompanyMetricsTest.java`

- [ ] **Step 1: Write the failing test** — drive a request as company A and one as company B, then
  assert `meterRegistry.find("http.server.requests").tag("company", …)` has both, with separate counts.
  Use the `SimpleMeterRegistry` the Spring Boot test context provides.

- [ ] **Step 2: Implement it as a `MeterFilter` or an `Observation` customizer**, tagging from
  `CompanyContext.current()`.

**The one thing to get right:** tag with the company **slug**, not the id. A UUID in a dashboard
legend is unreadable and the slug is stable and immutable (`Company.java:35`, `updatable = false`).
And **tag `unknown` rather than omitting the tag** for a company-context-less request — a meter that
sometimes has a tag and sometimes does not produces two time series that will not aggregate.

- [ ] **Step 3: Assert the bound explicitly**

```java
@Test
@DisplayName("tags on the company and on nothing finer, because cardinality is the trap")
void doesNotTagOnProjectOrUser() { … }
```

§6s permits this tag *because company count is small and bounded* — and the same sentence rules out
project and user. Write the reason into the test, not just the code.

- [ ] **Step 4: Gates and commit.**

---

## Task 8: Write the slice down

Same shape as Plan A's Task 7 — a `docs/handoff/NNN-<slug>.md`, one index row, both orientation
caches, both verification queues, all in the slice's own commit.

The findings worth recording:

- The status column had a writer since V1 and no reader, **and login already refused a suspended
  company** — so the gap was never "suspension does nothing", it was "suspension takes effect at next
  sign-in", which is a different and more believable-looking failure.
- The console plan's Task 1 named `CompanyContext.currentOrNull()`, which does not exist.
- `theme.ts`'s literal hexes are mirrored into `index.css` by hand, and runtime branding is what makes
  that accepted duplication stop working.
- Whatever Task 5 Step 1 decided about the logo and the `storage` module boundary.

Verification-queue rows (**write them, run nothing** — §65):

- Browser: suspend a company from another session and confirm the running SPA shows the refusal
  rather than an empty page; set a brand colour and confirm the app bar, buttons and focus rings
  change while Pass/Fail chips do not; try a pale accent and confirm the rejection names the colour.
- Backend mutation: `PUT /api/v1/company` with a stale `lockVersion`; `PUT` as a Test Engineer;
  branding with one colour set and one null.

---

## Self-review

**Spec coverage against the master plan's Plan B row:** `SuspendedCompanyFilter` → Task 1 · branding
columns mapped with a writer → Task 3 · `GET/PUT /company/branding` → Task 4 (folded into
`GET/PUT /company`, because a separate `/branding` sub-resource for four fields is a second endpoint
with the same gate) · contrast validation at save time → Tasks 2–3 · semantic colours not brandable →
Task 6 Step 2, structurally · logo + favicon through `storage` → Task 5 · per-company Micrometer tags
→ Task 7.

**Placeholder scan.** Tasks 4, 5 and 6 are written at a coarser granularity than Tasks 1–3, and that
is deliberate rather than unfinished in exactly one case — **Task 5 Step 1**, which asks the
implementer to decide a module-boundary question from the module graph, and says why deciding it
from convenience is the failure. Tasks 4 and 6 name every file, every gate and every assertion but
leave the local idiom (`PermissionGate`, the 422 convention, MUI's `createTheme` spread) to be copied
from a named neighbour rather than restated wrongly here. If the executing agent finds a step that
does not tell it enough to act, that is a plan defect — report it rather than guessing.

**Type consistency.** `BrandColor` (Task 2) is consumed by `CompanyBranding` (Task 3), which is the
argument type of `Company.rebrand` (Task 3) and the payload behind `PUT /api/v1/company` (Task 4).
`CompanyBranding.none()` / `isSet()` are declared in Task 3's test and used in Task 3's
implementation. `createBrandedTheme(branding | null)` is declared and called under that name in every
Task 6 step.

**One thing I could not check from the plan and the executor must:** whether `tenancy` may depend on
`storage` (Task 5). The module graph says `storage` declares `tenancy` alone, which makes the reverse
edge a cycle — so the answer is almost certainly no, and the endpoint or the port placement has to
move. It is written as a step rather than a decision because the right resolution depends on
`AttachmentOwner`'s shape, which is the file to open first.
