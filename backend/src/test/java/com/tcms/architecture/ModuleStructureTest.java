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
 * repository · execution · documentation · reporting · work · shared}, all but the last
 * of which exist — plus {@code auth}, {@code provisioning} and, since §125,
 * {@code storage}, which the plan's list never named and which are recorded in the
 * assertion below rather than back-fitted into it.
 * Nothing about "modules are Java packages" is enforced by the compiler: every
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
                        The thirteen modules that exist today — `execution` joined them in backend M2 \
                        slice 1, `work` in §53, `documentation` in §57, `reporting` in §86 and \
                        `storage` in §125. \
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
                        sides to each other instead. \
                        §125 adds `storage` and, with it, the only entry on this list that is also \
                        an entry on `ArchitectureRules.FOUNDATION_MODULES` without being a domain \
                        concept: `Attachment`'s javadoc had named "a second caller to design \
                        against" as the condition for extracting it, decision #49 created that \
                        caller twice over, and #51 took the move as its own slice BEFORE them — in \
                        one diff a module extraction and a feature are indistinguishable. The \
                        `shared` module the paragraph above still calls "the last one" is \
                        unaffected: `storage` is not it, and decision E's refusal to create a \
                        module for two interfaces and a record is what this one was measured \
                        against rather than an argument it overturns. \
                        §146 adds `api`, and it IS the module the paragraph above kept calling \
                        `shared` — under a name that states a charter instead of an access level, \
                        because §125 refused `shared` on the grounds that a module every other \
                        module may depend on must not be a grab bag, and that argument did not \
                        expire. It holds the wire shapes the CONTRACT owns and more than one \
                        module serves: `Problem`, which was package-private in `repository` while \
                        six modules referred to it in prose, plus the same record written again in \
                        `auth` and a third time as a JSON string literal. The forcing function was \
                        a caller with no module at all — the container-error rendering, which runs \
                        when no controller did. \
                        The notifications slice adds `integration`, which plan §5 has named in the \
                        module list since M0 (`Jira, notification channels, Git, outbound \
                        webhooks`) — so this is a declared module arriving rather than one being \
                        invented. It is NOT a foundation module and its package comment says so \
                        against §125's three properties: a notification channel is a domain \
                        concept with a lifecycle, an owner and a permission, which is exactly what \
                        `ArchitectureRules.FOUNDATION_MODULES` says a feature module proposing \
                        itself will not clear. What makes it interesting instead is the constraint \
                        it works under: it may see NEITHER `execution` nor `work`, and every fact \
                        it renders belongs to one of them — so the transactional outbox is not one \
                        mechanism among several here, it is the only one, and this is the slice \
                        that gave `Outbox.record` its first product caller since §131 built it. \
                        011 M1 adds `tracker`, and it is the largest domain concept in the \
                        product — epics, stories, tasks and the workflow they move through. Its \
                        package comment states the constraint that shapes everything downstream of \
                        it: it will NEVER be a foundation module, because \
                        `ArchitectureRules.FOUNDATION_MODULES` asks for a module that holds no \
                        domain concept and this one holds the biggest, so `time`, `billing` and \
                        `reporting` reference an issue POLYMORPHICALLY and join \
                        `tracker.IssueFactProjection`'s table rather than importing it. The price \
                        is stated where it is paid: referential integrity between a time entry and \
                        its issue lives in application code, which is why `issue` is soft-deleted. \
                        `documentation` is deliberately absent from its allowedDependencies while \
                        `issue_doc_link` mirrors that module's table — `work.BoardFacts`' \
                        resolution, one class naming the foreign tables and nothing else in the \
                        package doing so. \
                        016 M6 adds `time`, and it is the first module built ON TOP of that \
                        constraint rather than one that had to discover it: hours are logged \
                        against an issue through `(subject_type, subject_id)` with no foreign key, \
                        the whole read crossing is `time.IssueFacts`, and the module's own \
                        `issue_time_rollup` is what it writes instead. `TimeWritesNoIssueFactTest` \
                        asserts both halves over the source text, because a second writer of \
                        `issue_fact` compiles, passes every functional test, and produces two \
                        silently disagreeing numbers. \
                        017 M7 adds `staffing`, and it inherits `time`'s constraint one module \
                        further out: it may not import `time` either, so the hours behind \
                        utilisation are read through `staffing.TimeFacts` — the FIFTH instance of \
                        the one-file crossing, after `work.BoardFacts`, `execution.CaseFacts`, \
                        `tracker.release.ReleaseFacts` and `time.IssueFacts`. What is new here is \
                        the direction the module leans: it takes `identity` for names AND for the \
                        company's membership list (017 D-k added `PeopleDirectory.membersOf`), and \
                        `project` for BOTH gate shapes — the project one for `staffing:read` / \
                        `staffing:allocate` and the company one for the three COMPANY keys, which \
                        is why `ProjectService` grew `readInCompany` and `holdsInCompany` in the \
                        same task. `StaffingReadsNoTimeEntryTest` asserts the crossing over source \
                        text, because SQL is not a Java dependency and this rule cannot see it. \
                        018 Mc adds `crm`, and it is the first module here whose crossing runs the \
                        OTHER way. Every one above it reads a module it may not import, through one \
                        `*Facts` class of native SQL — `staffing` -> `time`, `time` -> `tracker`. \
                        This one is READ BY a module that may not import it: `project` stores \
                        `client_id` and must serve the client's NAME on `ProjectView`, and \
                        `project` is a foundation module. A `project.ClientFacts` would have been \
                        the sixth instance of the pattern and would have been wrong — all five \
                        point downstream to upstream, and this edge is upstream to downstream, \
                        which ArchUnit cannot see precisely because SQL is not a Java dependency. \
                        So it is the OTHER pattern in that package instead: `project` declares \
                        `ClientDirectory` and `crm` implements it, as `identity` does for \
                        `PeopleDirectory` and `integration` for `TrackerBindingDirectory`. It also \
                        takes four foundation modules rather than five — `storage` is absent \
                        because nothing here has an attachment, and `client.html`'s Contracts \
                        panel is built by no module at all. \
                        019 M8 adds `billing`, and it is the first module that makes the crossing \
                        TWICE and the first that had to rename the classes doing it. It may not \
                        import `time` or `tracker`, so approved hours arrive through \
                        `billing.ApprovedTimeFacts` (sixth instance) and an issue's key and title \
                        through `billing.IssueSubjectFacts` (seventh, landing with the invoice \
                        entries that snapshot them). Neither is called `TimeFacts` or `IssueFacts` \
                        because `staffing` and `time` already own those SIMPLE names and Spring's \
                        default bean name is the simple class name — two `@Component`s with one \
                        name is a ConflictingBeanDefinitionException and a context that does not \
                        start, which is what \
                        `ArchitectureRulesTest.springStereotypesHaveDistinctSimpleNames` exists to \
                        catch (019 D-r). It also runs INTO 018's inversion from the other side: it \
                        may not import `crm` either, so a client's name comes through \
                        `project.ClientDirectory` — which grew `activeClients()` for it rather \
                        than this module growing a `ClientFacts` that would have pointed the \
                        `*Facts` pattern backwards. Four foundation modules, `storage` absent for \
                        `crm`'s reason: nothing here has an attachment, `invoice.html`'s Preview \
                        PDF is not built, and the day either changes this list grows by one entry \
                        and the growth is the record that it did.""")
                .containsExactly(
                        "api", "auth", "billing", "crm", "documentation", "execution", "identity",
                        "integration", "project", "provisioning", "reporting", "repository",
                        "staffing", "storage", "tenancy", "time", "tracker", "work");
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
                        Per plan §3 the only permitted targets are tenancy, identity, project, \
                        (since §125) storage and (since §146) api; everything else crosses via a \
                        CompanyScopedEvent.""")
                .isEmpty();
    }

    @Test
    @DisplayName("no module declares a dependency outside the foundation — api, tenancy, identity, project, storage")
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
                        Declared dependencies on a module outside api/tenancy/identity/project/storage. \
                        Cross-module FKs and direct calls are permitted only toward those (plan §3); \
                        every other edge is a domain event implementing CompanyScopedEvent. Adding a \
                        SIXTH foundation module is a decision, not an import — §125 took the fourth \
                        and §146 the fifth, and ArchitectureRules.FOUNDATION_MODULES states the three \
                        properties each of them had to clear rather than being a precedent for the \
                        next one.""")
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
