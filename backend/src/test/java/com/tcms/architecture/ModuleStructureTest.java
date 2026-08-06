package com.tcms.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.tcms.TcmsApplication;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.ApplicationModule;
import org.springframework.modulith.core.ApplicationModules;

/**
 * Spring Modulith verification — the modules are packages, and this is what makes that
 * claim mean something.
 *
 * <p>The module list in the plan (§3) is {@code tenancy · identity · project ·
 * repository · execution · documentation · reporting · work · shared}, of which four
 * exist. Nothing about "modules are Java packages" is enforced by the compiler: every
 * class under {@code com.tcms} can see every other one. Without this test the module
 * boundaries are a naming convention, and a naming convention is what the first
 * deadline removes.
 *
 * <h2>No database, no context</h2>
 *
 * <p>This class deliberately does <strong>not</strong> extend {@code IntegrationTestBase}.
 * Modulith's analysis is static bytecode inspection, so it needs neither Postgres nor a
 * Spring context — and a structural rule that only runs when Docker is up is a rule that
 * gets skipped exactly when someone is in a hurry.
 *
 * @see ArchitectureRulesTest for the rules Modulith cannot express
 */
@DisplayName("Module structure — Spring Modulith (plan §3)")
class ModuleStructureTest {

    /**
     * Built once; the analysis is the expensive part and it is immutable. Modulith
     * excludes test classes by default, so this is the production module graph.
     */
    private static final ApplicationModules MODULES = ApplicationModules.of(TcmsApplication.class);

    @Test
    @DisplayName("the module graph verifies — declared dependencies hold, and there are no cycles")
    void modulesVerify() {
        // verify() throws with the offending edge named. It checks two things at once:
        //  - every module stays inside its declared allowedDependencies, and
        //  - no module participates in a cycle.
        // The second is why `tenancy` declaring `{}` is load-bearing rather than tidy:
        // everything depends on tenancy, so a single edge back out of it would put the
        // isolation layer into a cycle with whatever it reached for.
        MODULES.verify();
    }

    @Test
    @DisplayName("the modules detected are exactly the ones with a package under com.tcms")
    void detectedModulesMatchTheSourceTree() {
        // A guard on the analysis itself. If the module-detection strategy or the build
        // layout ever changes so that MODULES comes back empty or short, verify() above
        // would pass vacuously — it cannot violate a rule about modules it never saw.
        List<String> detected = MODULES.stream().map(module -> module.getIdentifier().toString()).sorted().toList();

        assertThat(detected)
                .as("""
                        The ten modules that exist today — `execution` joined them in backend M2 \
                        slice 1, `work` in §53, `documentation` in §57 and `reporting` in §86. \
                        Adding a module here is expected as the plan's remaining modules land \
                        (`shared` is the last one, and the slice plan's decision E deliberately \
                        declines to create it for two storage ports and an enum) — but it must be a \
                        deliberate edit, so that whoever adds one also reads the \
                        allowedDependencies rule they are now subject to. In `execution`'s case that \
                        rule is the whole design: it may not depend on `repository`, and its package \
                        comment records what it does instead. `work` is the sharper case — it may \
                        depend on NEITHER `execution` nor `repository`, while the derived half of the \
                        board is made entirely of their rows, so both edges are concentrated in \
                        `work.BoardFacts`. `documentation` was the narrowest — tenancy and project \
                        only, "because nothing in it renders a person" — until the artifact register \
                        arrived with `updatedBy` in the contract's required array and made that false; \
                        it now takes `identity` for display names only, and reaches `test_plan` \
                        through `documentation.PlanFacts` rather than depending on `execution`. \
                        `reporting` was the narrowest of the lot for exactly one slice: `tenancy` \
                        ALONE in §86, because nothing in it was reached through an endpoint, so \
                        there was no project gate to ask for and no person to name. §87's report \
                        pipeline is the widening its package comment named in advance — `project` \
                        for the gate on all three report endpoints, `identity` for the person a job \
                        records as having requested it — and the reason the narrow declaration was \
                        right in the meantime is the rule this list runs on: a declaration is a \
                        fact about code that exists, never a forecast of what the module will want, \
                        because a forecast cannot fail the build when it stops being true. \
                        §101 adds the first module with TWO seams: `execution.RollupFacts` names \
                        `reporting`'s three read models beside `execution.CaseFacts` naming \
                        `repository`'s tables. The rule was never "one seam per module" but one \
                        seam per FOREIGN module, and splitting them keeps each file's header an \
                        argument about one boundary. It is also the first edge that runs both ways \
                        at the table level — `reporting` builds those three tables from \
                        `execution`'s — and in neither direction at the Java level, which is the \
                        only level this test can see; `RollupAgreementTest` is what holds the two \
                        sides to each other instead.""")
                .containsExactly(
                        "auth", "documentation", "execution", "identity", "project", "provisioning",
                        "reporting", "repository", "tenancy", "work");
    }

    @Test
    @DisplayName("every module declares its allowed dependencies — none is left unrestricted")
    void everyModuleDeclaresItsAllowedDependencies() {
        // The default for @ApplicationModule#allowedDependencies is *unrestricted*, and a
        // module with no package-info annotation at all is unrestricted too. Both states
        // are indistinguishable from "verified" in the run above: verify() reports no
        // violation because there is no constraint to violate. So a new module that
        // simply forgets to declare anything would silently opt out of the whole rule
        // set — the same failure shape as a table quietly added without company_id.
        //
        // Derived from the live module list rather than a hand-maintained copy, for the
        // same reason SchemaConventionTest reads pg_class.
        Map<String, String> undeclared = new LinkedHashMap<>();

        for (var module : MODULES) {
            String basePackage = module.getBasePackage().getName();
            ApplicationModule declaration = packageAnnotation(basePackage);
            if (declaration == null) {
                undeclared.put(module.getIdentifier().toString(),
                        "no @ApplicationModule on " + basePackage + "/package-info.java");
            } else if (isUnrestricted(declaration)) {
                undeclared.put(module.getIdentifier().toString(),
                        "@ApplicationModule present but allowedDependencies left at its unrestricted default");
            }
        }

        assertThat(undeclared)
                .as("""
                        Modules whose dependencies are unconstrained. Add \
                        @ApplicationModule(allowedDependencies = {...}) to the package-info — an empty \
                        array means "no module dependencies at all", which is what tenancy declares. \
                        Per plan §3 the only permitted targets are tenancy, identity and project; \
                        everything else crosses via a CompanyScopedEvent.""")
                .isEmpty();
    }

    @Test
    @DisplayName("no module declares a dependency on anything but tenancy, identity or project")
    void declarationsNameOnlyTheFoundationModules() {
        // The declarations themselves are checked, not just honoured. verify() confirms
        // a module stays inside what it declared; it has no opinion on whether the
        // declaration was allowed in the first place, so `execution` could legally
        // declare `allowedDependencies = "repository"` and pass. Plan §3 says that edge
        // must be an event instead.
        Map<String, List<String>> illegal = new LinkedHashMap<>();

        for (var module : MODULES) {
            ApplicationModule declaration = packageAnnotation(module.getBasePackage().getName());
            if (declaration == null || isUnrestricted(declaration)) {
                continue;   // reported by the test above; not double-counted here
            }
            List<String> offending = new ArrayList<>();
            for (String allowed : declaration.allowedDependencies()) {
                // A named-interface reference is `module::INTERFACE`; only the module part matters.
                String target = allowed.split("::", 2)[0].trim();
                if (!target.isEmpty() && !ArchitectureRules.FOUNDATION_MODULES.contains(target)) {
                    offending.add(allowed);
                }
            }
            if (!offending.isEmpty()) {
                illegal.put(module.getIdentifier().toString(), offending);
            }
        }

        assertThat(illegal)
                .as("""
                        Declared dependencies on a module outside tenancy/identity/project. Cross-module \
                        FKs and direct calls are permitted only toward those three (plan §3); every other \
                        edge is a domain event implementing CompanyScopedEvent. Adding a fourth \
                        foundation module is a decision, not an import.""")
                .isEmpty();
    }

    private static ApplicationModule packageAnnotation(String basePackage) {
        try {
            // package-info compiles to a class only when it carries an annotation, so a
            // ClassNotFoundException here IS the "nothing declared" case, not a setup fault.
            return Class.forName(basePackage + ".package-info").getAnnotation(ApplicationModule.class);
        } catch (ClassNotFoundException absent) {
            return null;
        }
    }

    private static boolean isUnrestricted(ApplicationModule declaration) {
        String[] allowed = declaration.allowedDependencies();
        return allowed.length == 1 && ApplicationModule.OPEN_TOKEN.equals(allowed[0]);
    }
}
