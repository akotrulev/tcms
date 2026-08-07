package com.tcms.architecture;

import static com.tcms.architecture.ArchitectureRules.FOUNDATION_MODULES;
import static com.tcms.architecture.ArchitectureRules.ROOT_PACKAGE;
import static com.tcms.architecture.ArchitectureRules.moduleOf;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.tcms.tenancy.CompanyScopedEvent;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.event.EventListener;

/**
 * The architecture rules Spring Modulith cannot express, in ArchUnit.
 *
 * <p>{@code ModuleStructureTest} covers what Modulith owns — declared dependencies and
 * cycles. Four properties fall outside it, and each one is a rule this project already
 * wrote down in prose and has so far had no mechanism for:
 *
 * <ol>
 *   <li><strong>{@code tenancy} depends on nothing</strong> — stated from the other
 *       side. Modulith checks the declaration; this checks the code, including the edge
 *       Modulith would not see at all: a dependency on the composition root.</li>
 *   <li><strong>{@code storage} depends on {@code tenancy} alone</strong> — §125's
 *       fourth foundation module, checked from the code rather than from its declaration,
 *       because widening that declaration is one legal word and every other rule here
 *       would still pass.</li>
 *   <li><strong>{@code provisioning} is a leaf.</strong>
 *       {@code allowedDependencies} constrains what a module reaches <em>out</em> to and
 *       has no vocabulary for who reaches <em>in</em>. "Nothing depends on provisioning,
 *       and nothing should" is a claim in its package comment; here it becomes a
 *       build failure.</li>
 *   <li><strong>Every event handler's event carries its company.</strong> The whole
 *       async-isolation design rests on it (HANDOFF §14): {@code CompanyContext} is not
 *       inheritable, the task decorator clears it, so a handler has exactly one
 *       trustworthy source for the company — the envelope. An event that is not a
 *       {@link CompanyScopedEvent} leaves the handler with nothing to open, and the
 *       tempting repair is to make the context inheritable, which is the leak.</li>
 * </ol>
 *
 * <p>Like {@code ModuleStructureTest}, this runs on bytecode alone — no container, no
 * Spring context.
 *
 * <h2>Two class sets, deliberately</h2>
 *
 * <p>The dependency rules import production classes only: a test may import anything,
 * and running them over {@code src/test} would report the isolation harness as an
 * architecture violation. The event-contract rule imports <em>both</em>, because today
 * the only event handler in the codebase is the one in
 * {@code AsyncCompanyContextTest} — over production classes alone the rule would match
 * nothing, and a rule that matches nothing is a rule that has never discriminated
 * anything. Including tests makes it live now rather than on the day the first real
 * event lands, and holds the harness to the same contract as the code it stands in for.
 */
@DisplayName("Architecture rules — the ones Modulith cannot express (plan §3)")
class ArchitectureRulesTest {

    /** Production classes only. Structural rules are about the shipped graph. */
    private static final JavaClasses PRODUCTION = new ClassFileImporter()
            .withImportOption(new ImportOption.DoNotIncludeTests())
            .importPackages(ROOT_PACKAGE);

    /** Production and test classes. See the class comment for why the event rule needs both. */
    private static final JavaClasses PRODUCTION_AND_TESTS = new ClassFileImporter()
            .importPackages(ROOT_PACKAGE);

    @Test
    @DisplayName("fixture precondition: the two class sets are actually different")
    void theImportOptionSeparatesProductionFromTests() {
        // Every rule below rests on the split working. If DoNotIncludeTests ever stopped
        // matching this build's output layout, PRODUCTION would quietly grow the test
        // classes and the dependency rules would start failing for the wrong reason —
        // or, worse, the reverse: an empty import makes every noClasses() rule pass.
        assertThat(PRODUCTION.size())
                .as("the production import must not be empty, or every rule below passes vacuously")
                .isGreaterThan(30);
        assertThat(PRODUCTION.stream().map(JavaClass::getName))
                .as("DoNotIncludeTests is not excluding this build's test classes")
                .noneMatch(name -> name.endsWith("Test"));
        assertThat(PRODUCTION_AND_TESTS.stream().map(JavaClass::getName))
                .as("the combined import must contain the test classes the event rule needs")
                .anyMatch(name -> name.endsWith("Test"));
    }

    @Test
    @DisplayName("tenancy depends on nothing else in com.tcms")
    void tenancyDependsOnNothing() {
        // Expressed as "everything under com.tcms that is not tenancy", derived rather
        // than listing the sibling modules — a module added next year is covered without
        // anyone remembering this file exists.
        //
        // It also covers the edge allowedDependencies has no term for: the composition
        // root com.tcms is not a module. Measured, because the first version of this
        // comment claimed Modulith would miss it outright and that was too strong —
        // Modulith DOES fail a tenancy→root dependency today, but as a *cycle*
        // (tenancy → root:com.tcms → provisioning → identity → tenancy), which exists
        // only because TcmsApplication wires the CLI. Drop that one import and Modulith
        // goes quiet while the edge remains. This rule names the edge itself.
        ArchRule rule = noClasses()
                .that().resideInAPackage("com.tcms.tenancy..")
                .should().dependOnClassesThat(
                        JavaClass.Predicates.resideInAPackage(ROOT_PACKAGE + "..")
                                .and(JavaClass.Predicates.resideOutsideOfPackage("com.tcms.tenancy..")))
                .because("""
                        tenancy is depended on by every module and must depend on none (plan §3). \
                        An edge out of it puts the isolation layer into a cycle with whatever it \
                        reached for, and "the company boundary is established before anything reads a \
                        company-owned row" stops being a property anyone can guarantee. If tenancy \
                        needs something from another module, that something belongs in tenancy""");

        rule.check(PRODUCTION);
    }

    @Test
    @DisplayName("a cross-module dependency points only at a foundation module")
    void crossModuleDependenciesPointOnlyAtTheFoundation() {
        // Plan §3's actual rule, and the reason it is here as well as in each
        // package-info: a module added without an @ApplicationModule declaration is
        // unrestricted as far as Modulith is concerned. ModuleStructureTest fails such a
        // module for not declaring — but this rule fails it for the specific edge, which
        // is the more useful message.
        ArchRule rule = ArchRuleDefinition.classes()
                .that(new DescribedPredicate<JavaClass>("reside in a module") {
                    @Override
                    public boolean test(JavaClass type) {
                        return moduleOf(type) != null;
                    }
                })
                .should(new ArchCondition<>("depend across module boundaries only on "
                        + FOUNDATION_MODULES.stream().sorted().toList()) {
                    @Override
                    public void check(JavaClass source, ConditionEvents events) {
                        String from = moduleOf(source);
                        for (Dependency dependency : source.getDirectDependenciesFromSelf()) {
                            String to = moduleOf(dependency.getTargetClass());
                            if (to == null || to.equals(from) || FOUNDATION_MODULES.contains(to)) {
                                continue;
                            }
                            events.add(SimpleConditionEvent.violated(source, dependency.getDescription()
                                    + " — module '" + from + "' may not depend on module '" + to
                                    + "'; publish a CompanyScopedEvent instead"));
                        }
                    }
                })
                .because("""
                        Cross-module FKs and direct calls are allowed only toward the foundation \
                        modules — tenancy, identity, project, and storage since §125 (plan §3); every \
                        other edge crosses as a domain event. Two feature modules wired directly \
                        become deployable only together, which is the one property a modulith exists \
                        to keep""");

        rule.check(PRODUCTION);
    }

    @Test
    @DisplayName("storage depends on tenancy alone — the property that makes it a legal fourth")
    void storageDependsOnTenancyAlone() {
        // §125 added a fourth entry to FOUNDATION_MODULES, which every module may now point
        // at. That is safe for exactly one reason, and it is a reason about storage rather
        // than about the modules using it: storage cannot see a person, a permission or a
        // project, so nothing about WHICH bytes a caller may reach can migrate into it. The
        // decision about that is a database question answered in execution.EvidenceService
        // and reporting.ReportReadService, and both of their ports' headers say a store that
        // could be asked "list this company's files" would be one refactor from being the
        // second place it is decided.
        //
        // Modulith already enforces the declaration in storage/package-info.java. What it
        // cannot object to is the declaration being WIDENED — adding "identity" there is one
        // word, it is legal under the foundation rule (identity is itself a foundation
        // module), and it would pass every other test in this class. This rule is about the
        // code and fails on the import, which is both the earlier signal and the more useful
        // message.
        //
        // Same shape as the tenancy rule above and deliberately not the same rule: tenancy
        // may reach for nothing at all, storage may reach for tenancy, because `attachment`
        // is a company-owned table and carries CompanyFilter like every other one.
        ArchRule rule = noClasses()
                .that().resideInAPackage("com.tcms.storage..")
                .should().dependOnClassesThat(
                        JavaClass.Predicates.resideInAPackage(ROOT_PACKAGE + "..")
                                .and(JavaClass.Predicates.resideOutsideOfPackage("com.tcms.storage.."))
                                .and(JavaClass.Predicates.resideOutsideOfPackage("com.tcms.tenancy..")))
                .because("""
                        storage is a foundation module every other module may depend on (plan §3, \
                        decision round 3 #51), and it earns that by depending on tenancy alone: it \
                        owns bytes and one metadata row, holds no domain concept, and never resolves \
                        an owner_id into anything. An edge out of it toward identity or project is \
                        the start of authorization being decided in two places, and toward a feature \
                        module it is a cycle through the module everything else depends on""");

        rule.check(PRODUCTION);
    }

    @Test
    @DisplayName("provisioning is a leaf — only the composition root may reach into it")
    void nothingDependsOnProvisioning() {
        // The claim in provisioning/package-info.java, made mechanical. A second
        // provisioning path is the failure this prevents: a company that exists without
        // its vocabularies, its display-key counters or an administrator is worse than no
        // company at all, and the way that happens is an admin API calling three of the
        // four steps.
        //
        // com.tcms itself is exempt and is spelled as an exact package (no "..") so the
        // exemption covers TcmsApplication and nothing else. It decides server-vs-CLI
        // from the raw arguments, which has to happen before the context exists.
        ArchRule rule = noClasses()
                .that().resideInAPackage(ROOT_PACKAGE + "..")
                .and().resideOutsideOfPackage("com.tcms.provisioning..")
                .and().resideOutsideOfPackage(ROOT_PACKAGE)
                .should().dependOnClassesThat().resideInAPackage("com.tcms.provisioning..")
                .because("""
                        provisioning composes tenancy + identity + project and is a leaf: nothing \
                        depends on it. A module reaching in is either a second provisioning path or \
                        the start of a cycle. A future admin API calls CompanyProvisioningService \
                        from the composition root — it does not grow its own""");

        rule.check(PRODUCTION);
    }

    @Test
    @DisplayName("auth is a leaf — no module may reach for the session")
    void nothingDependsOnAuth() {
        // Same shape as the provisioning rule, and for a different reason. auth owns the
        // session: the filter chain, the token, the current caller. A module reaching in
        // would be reaching for "who is asking", and the moment a domain service can see
        // that, authorization starts being decided in two places — which is precisely the
        // failure PermissionEvaluator exists as a single entry point to prevent.
        //
        // What a module needs from the session it already has ambiently and safely:
        // CompanyContext holds the company, established by CompanyContextFilter.
        ArchRule rule = noClasses()
                .that().resideInAPackage(ROOT_PACKAGE + "..")
                .and().resideOutsideOfPackage("com.tcms.auth..")
                .and().resideOutsideOfPackage(ROOT_PACKAGE)
                .should().dependOnClassesThat().resideInAPackage("com.tcms.auth..")
                .because("""
                        auth composes tenancy + identity and is a leaf: nothing depends on it. A \
                        module reaching in is reaching for the caller's session, and a service that \
                        can see the session is one that will eventually decide authorization itself \
                        rather than asking PermissionEvaluator""");

        rule.check(PRODUCTION);
    }

    @Test
    @DisplayName("nothing reads the wall clock directly — the Clock bean is the only time source")
    void nothingCallsNowDirectly() {
        // The mechanical half of the fix for the defect that kept master red on CI from
        // 2026-07-31 to 2026-08-02 (HANDOFF §58). TcmsApplication#clock() is truncated to
        // microseconds because that is what a Postgres timestamptz stores; an Instant taken
        // straight from the wall clock carries nanoseconds, so a value that is returned in
        // memory and later read back out of the database is TWO DIFFERENT VALUES differing
        // in the last three digits, and nothing in the type system says so.
        //
        // Every call site that bypasses the bean silently re-opens it, which is not
        // hypothetical: ExecutionService.record and SessionTokens.issue were both doing
        // exactly this when the bean was fixed, and ExecutionService stamps an executedAt
        // that is both persisted and returned — the identical shape as the invitation
        // expiry that broke.
        //
        // Why a rule rather than a convention: this defect CANNOT FAIL on macOS, where
        // Clock.systemUTC() already yields only microseconds, and since 2026-08-02 CI runs
        // no tests at all. There is no observer left. A grep at review time is the only
        // other candidate and it is the thing that already missed twice.
        //
        // The Clock-taking overloads (Instant.now(Clock), LocalDate.now(Clock)) are
        // deliberately NOT banned — they take the injected bean and are the supported way
        // to derive a local date from it.
        ArchRule rule = noClasses()
                .that().resideInAPackage(ROOT_PACKAGE + "..")
                .should().callMethod(Instant.class, "now")
                .orShould().callMethod(LocalDate.class, "now")
                .orShould().callMethod(LocalDateTime.class, "now")
                .orShould().callMethod(LocalTime.class, "now")
                .orShould().callMethod(OffsetDateTime.class, "now")
                .orShould().callMethod(ZonedDateTime.class, "now")
                .orShould().callMethod(System.class, "currentTimeMillis")
                .because("""
                        the one Clock bean is truncated to the resolution of a Postgres timestamptz \
                        (TcmsApplication#clock), so a timestamp taken from the wall clock instead is \
                        a value the database cannot store and therefore changes when it is read back \
                        — which is what returned an invitation expiry that differed from the stored \
                        one, and what made provision-company print a time that was not the time. \
                        Inject Clock and call clock.instant(); the Clock-taking now(clock) overloads \
                        are fine. This is unobservable on macOS and CI no longer runs the suite, so \
                        the rule is the only thing watching""");

        rule.check(PRODUCTION);
    }

    @Test
    @DisplayName("every event handler takes one CompanyScopedEvent")
    void eventHandlersReceiveACompanyScopedEvent() {
        // @TransactionalEventListener is meta-annotated with @EventListener, so matching
        // the meta-annotation covers both spellings — and any future one Spring adds.
        //
        // This rule fails if it matches no methods (ArchUnit's default), which is the
        // property that keeps it honest: on the day the listeners move out of the test
        // harness and into a real module, a green run means the rule found them.
        //
        // What it adds over the compiler, since CompanyContext.openFor(CompanyScopedEvent)
        // already refuses a plain event: taking `implements CompanyScopedEvent` off an
        // existing event does not reach this rule at all — it fails compilation at the
        // openFor call. The gap is the handler that never calls openFor. That one
        // compiles, runs, reads zero rows because the decorator cleared the context, and
        // presents as an empty report rather than as a missing envelope. This rule is the
        // only thing that names it.
        ArchRule rule = methods()
                .that().areMetaAnnotatedWith(EventListener.class)
                .should(new ArchCondition<JavaMethod>(
                        "declare exactly one parameter, implementing " + CompanyScopedEvent.class.getSimpleName()) {
                    @Override
                    public void check(JavaMethod method, ConditionEvents events) {
                        var parameters = method.getRawParameterTypes();
                        if (parameters.size() != 1) {
                            events.add(SimpleConditionEvent.violated(method, method.getFullName()
                                    + " takes " + parameters.size() + " parameters; an event handler takes one"));
                            return;
                        }
                        JavaClass event = parameters.getFirst();
                        if (!event.isAssignableTo(CompanyScopedEvent.class)) {
                            events.add(SimpleConditionEvent.violated(method, method.getFullName()
                                    + " handles " + event.getName() + ", which does not implement "
                                    + CompanyScopedEvent.class.getName()));
                        }
                    }
                })
                .because("""
                        A handler runs on a worker thread with no company: CompanyContext is \
                        deliberately not inheritable and CompanyContextTaskDecorator clears it, so \
                        forgetting fails closed rather than leaking the submitter's company \
                        (HANDOFF §14). The envelope is the only trustworthy source, so the company id \
                        is part of the event contract — an event that omits it leaves the handler \
                        nothing to open, and the tempting repair is to make the context inheritable, \
                        which is the leak itself""");

        rule.check(PRODUCTION_AND_TESTS);
    }
}
