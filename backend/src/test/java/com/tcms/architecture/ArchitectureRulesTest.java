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
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

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
    @DisplayName("api depends on NOTHING in com.tcms — the property that makes it a legal fifth")
    void apiDependsOnNothing() {
        // §146 added a fifth entry to FOUNDATION_MODULES, and it is the first that reaches for
        // nothing at all — not even tenancy, which storage above legitimately needs. That is the
        // whole of its defence: a module every other module may depend on, holding types the
        // CONTRACT owns, which cannot see a company, a person, a permission or a table.
        //
        // The declaration in api/package-info.java is `allowedDependencies = {}`, and Modulith
        // enforces it. What Modulith has nothing to say about is that declaration being WIDENED —
        // §125 made exactly this point about storage, and it is sharper here: every widening
        // available is toward a foundation module, so it is legal under the rule above and passes
        // every other test in this class. One word, and the module that everything depends on
        // starts depending on something.
        //
        // The failure it prevents is specific rather than aesthetic. `Problem` is the shape of a
        // REFUSAL. The moment this module can see identity or tenancy, the tempting next commit is
        // a factory that reads the caller — "forbidden(user, permission)" — and the permission
        // catalogue starts being published to the caller who is not entitled to it, from inside a
        // record whose javadoc says never to name the key.
        ArchRule rule = noClasses()
                .that().resideInAPackage("com.tcms.api..")
                .should().dependOnClassesThat(
                        JavaClass.Predicates.resideInAPackage(ROOT_PACKAGE + "..")
                                .and(JavaClass.Predicates.resideOutsideOfPackage("com.tcms.api..")))
                .because("""
                        api is the foundation module every other module may depend on for the wire \
                        shapes the contract defines (§146), and it earns that by depending on \
                        NOTHING: it holds no domain concept, no persistence and no notion of a \
                        caller, so a refusal cannot start being composed from facts about who was \
                        refused. An edge out of it is the beginning of authorization, tenancy or a \
                        table being visible from the one package everything else imports""");

        rule.check(PRODUCTION);
    }

    @Test
    @DisplayName("no record outside api repeats an api record's shape — the tally, computed")
    void noRecordOutsideApiRepeatsAnApiShape() {
        // §146 extracted the error envelope and §147 the person, and the finding on the way through
        // was not about either record. It was that the evidence for extracting had been kept BY HAND:
        // work.UserRefView carried a running tally — "ApiProblem ×2, UserRefView ×3" — with the rule
        // attached, and the real count when the module was created was six. A hand-maintained count
        // of copies drifts exactly the way the copies do, which is the thing it was keeping records
        // about. Same family as §136's MAX_IDEMPOTENCY_KEY, and the same repair: compute it.
        //
        // So this is the tally, as a build failure. Once a shape lives in com.tcms.api, a seventh
        // copy of it — under ANY name, in ANY module — fails here rather than being written down in
        // a comment for somebody to extract in eleven slices' time.
        //
        // MATCHED ON SHAPE, NOT ON NAME, and that is the point: every one of the six copies was
        // called UserRefView, but the failure this prevents is the next one, which will be called
        // Person or OwnerRef or CommenterView because whoever writes it will not have read this
        // file. Components are compared as a SET of name:type pairs, so a reordered copy is still a
        // copy.
        //
        // It deliberately does NOT say "no two records anywhere share a shape". Measured before
        // being written: fifty records in this codebase are `Invalid(Map<String, List<String>> errors)`,
        // one per service's sealed Outcome, and they are not copies of anything — they are the same
        // three words meaning the same thing in fifty local vocabularies. The rule is narrow because
        // api's charter is narrow: it is about the shapes the CONTRACT owns and more than one module
        // serves, and it fires only once such a shape has been given a home.
        Map<Set<String>, String> apiShapes = new LinkedHashMap<>();
        for (JavaClass type : PRODUCTION) {
            if (type.isRecord() && "api".equals(moduleOf(type))) {
                apiShapes.put(componentsOf(type), type.getName());
            }
        }
        assertThat(apiShapes)
                .as("api holds no records at all, so this rule would pass vacuously — if the module "
                        + "was emptied deliberately, delete this test with it")
                .isNotEmpty();

        Map<String, String> copies = new TreeMap<>();
        for (JavaClass type : PRODUCTION) {
            if (!type.isRecord() || "api".equals(moduleOf(type)) || moduleOf(type) == null) {
                continue;
            }
            String original = apiShapes.get(componentsOf(type));
            if (original != null) {
                copies.put(type.getName(), "repeats the shape of " + original);
            }
        }

        assertThat(copies)
                .as("a record outside com.tcms.api has the same components as one inside it, which "
                        + "is the duplication api exists to end (§146, §147). The copy is free to "
                        + "drift from the contract on its own, and the compiler cannot see the two "
                        + "as related — import com.tcms.api's record instead, or, if this really is "
                        + "a different concept that happens to have the same fields, give it the "
                        + "field names of the concept it is")
                .isEmpty();
    }

    @Test
    @DisplayName("every method a repository DECLARES has a caller — the seam tally, computed")
    void everyDeclaredRepositoryMethodHasACaller() {
        // §121 deleted CaseFlakeRepository.findQuarantined rather than using it: a seam written
        // ahead of its consumer had guessed the module boundary wrong, and the generator its
        // javadoc named could never have called it. That was found by hand, one method, because
        // the slice happened to be about the table it hung off. §116, §117, §132 and §151 each
        // found the same shape from the other end — a column with no writer, a column with no
        // reader — and each of those was found by hand too.
        //
        // This is that tally, computed, at the seam rather than at the column. A repository method
        // is a query somebody designed, indexed for, and argued for in a javadoc; one with no
        // caller is a claim about how the product reads its data that the product does not make.
        // The failure it prevents is not a wasted method. It is the NEXT reader, who finds
        // findForPages' header stating "this has exactly one caller: the delete path", believes
        // the delete path works that way, and writes the second one against a premise that was
        // never true. Every javadoc in this codebase is evidence, and an uncalled method's is
        // evidence nothing has ever checked.
        //
        // Accesses AND references, because a method reference (Repository::find) is a caller and
        // ArchUnit records the two separately — the projection accessors read via
        // Comparator.comparingLong(ProjectRoleName::getPermissionCount) are live code, and a rule
        // that saw only invocations would demand their deletion.
        //
        // Over PRODUCTION_AND_TESTS, not PRODUCTION, and that is a deliberate widening rather than
        // laxness. Three of the methods this found are read only by tests asserting what a write
        // actually left in the database — AuditEventRepository.findByActionOrderByOccurredAtDesc is
        // the clearest, since the audit trail has no screen yet and the test IS the only reader
        // there is. A seam whose consumer is a test is a seam with a consumer. A seam with no
        // consumer at all has never once been executed, which is the state this rule names.
        //
        // Scoped to types named *Repository, deliberately narrow — §147's reason, applied again.
        // The broad rule ("no uncalled public method anywhere") was measured first and it reports
        // every controller handler in the codebase, because Spring calls those reflectively and
        // bytecode cannot see it; it would ship with an ignore list longer than the rule. Spring
        // Data repositories are the one population where every legitimate caller is a compile-time
        // one, which is what makes the count trustworthy here and nowhere else.
        //
        // Methods INHERITED from JpaRepository (findById, save, count) are out of scope by
        // construction: they are not declared here, and they are the base interface's cost rather
        // than anyone's design.
        Map<String, String> uncalled = new TreeMap<>();
        int declared = 0;

        for (JavaClass type : PRODUCTION_AND_TESTS) {
            if (!type.isInterface() || !type.getSimpleName().endsWith("Repository")) {
                continue;
            }
            for (JavaMethod method : type.getMethods()) {
                declared++;
                if (method.getAccessesToSelf().isEmpty() && method.getReferencesToSelf().isEmpty()) {
                    uncalled.put(
                            type.getSimpleName() + "." + method.getName(),
                            "declared, indexed for, documented — and called from nowhere");
                }
            }
        }

        assertThat(declared)
                .as("no repository method was examined at all, so this rule passes vacuously — the "
                        + "*Repository naming convention or the import has moved")
                .isGreaterThan(100);

        assertThat(uncalled)
                .as("a repository declares a query nothing calls. It is not dead weight, it is an "
                        + "unverified claim: its javadoc describes a read path that has never "
                        + "executed, and the next author will believe it. Delete it — the query is "
                        + "three lines to write again on the day something needs it, and §121's "
                        + "findQuarantined is the precedent, deleted rather than wired because the "
                        + "consumer it was written for could not legally have called it. If it "
                        + "genuinely is needed, give it its caller in the same commit")
                .isEmpty();
    }

    /** A record's components as {@code name:type} pairs — a set, so a reordered copy still matches. */
    private static Set<String> componentsOf(JavaClass record) {
        return record.getFields().stream()
                .filter(field -> !field.getModifiers().contains(JavaModifier.STATIC))
                .map(field -> field.getName() + ":" + field.getRawType().getName())
                .collect(Collectors.toCollection(TreeSet::new));
    }

    @Test
    @DisplayName("no two Spring beans share a simple name — the collision no test can see")
    void springStereotypesHaveDistinctSimpleNames() {
        // §131 shipped `reporting.ResultsChangedHandler` and `repository.ResultsChangedHandler`,
        // one name in two packages, both @Component. Spring's default bean name is the simple
        // class name, so the two collide and the CONTEXT DOES NOT START —
        // ConflictingBeanDefinitionException, and every @SpringBootTest with it.
        //
        // THE REASON THIS IS A TEST AND NOT A REVIEW NOTE: both of those beans carry
        // @ConditionalOnProperty, and IntegrationTestBase turns BOTH properties off. Conditions
        // are evaluated during scanning, so neither bean was ever registered and the collision
        // could not occur anywhere in the suite. It would have occurred exactly once — on the
        // first boot of a real deployment, where both modules are enabled. A green suite said
        // nothing about it and structurally could not.
        //
        // Reads the classes rather than a running context, deliberately: a context-based check
        // would inherit exactly the conditional-evaluation blindness that hid the defect.
        //
        // @Service, @Repository and @Controller are meta-annotated with @Component and ArchUnit
        // resolves meta-annotations, so one predicate covers every stereotype. Spring Data
        // repository INTERFACES are named by a different generator, and are excluded by
        // isInterface() rather than by a list that would need maintaining.
        Map<String, String> byName = new LinkedHashMap<>();
        Map<String, String> collisions = new TreeMap<>();

        for (JavaClass type : PRODUCTION) {
            if (type.isInterface() || !type.isMetaAnnotatedWith(Component.class)) {
                continue;
            }
            String existing = byName.put(type.getSimpleName(), type.getName());
            if (existing != null) {
                collisions.put(type.getSimpleName(), existing + " and " + type.getName());
            }
        }

        assertThat(collisions)
                .as("two Spring stereotypes with one simple class name resolve to the same default "
                        + "bean name, and the context fails to start with "
                        + "ConflictingBeanDefinitionException. Rename one — and note that if either "
                        + "carries @ConditionalOnProperty, no integration test here will reproduce "
                        + "it, because a condition that is off during scanning never registers the "
                        + "bean at all")
                .isEmpty();
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
    @DisplayName("nothing calls Clock.millis() — on this application's clock bean it throws")
    void nothingCallsClockMillis() {
        // Not a precision rule like the one above. `clock.millis()` THROWS here, on every call,
        // with ArithmeticException: / by zero.
        //
        // TcmsApplication#clock() is Clock.tick(systemUTC(), Duration.ofNanos(1000)) — §58's
        // microsecond truncation. The JDK's Clock.TickClock.millis() computes
        // floorMod(millis, tickNanos / 1_000_000), and a sub-millisecond tick makes that divisor
        // zero. So the truncation that fixed the CI defect left a method on a bean every module
        // injects that cannot be called at all.
        //
        // It was unreachable until §141: nothing in this codebase had ever asked the injected
        // clock for milliseconds, so the first caller found it as three red integration tests and
        // would have found it in production as a 500 on every request past a rate limit. Instant
        // and toEpochMilli are unaffected and are the way to get the same number.
        ArchRule rule = noClasses()
                .that().resideInAPackage(ROOT_PACKAGE + "..")
                .should().callMethod(Clock.class, "millis")
                .because("""
                        the Clock bean is Clock.tick(..., ofNanos(1000)) and TickClock.millis() \
                        divides by tickNanos / 1_000_000, which is zero for a sub-millisecond tick \
                        — so this is not an imprecise reading, it is an ArithmeticException on the \
                        request path. Call clock.instant().toEpochMilli()""");

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
