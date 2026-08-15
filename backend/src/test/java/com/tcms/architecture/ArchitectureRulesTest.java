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
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.domain.JavaFieldAccess;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Controller;

/**
 * The architecture rules Spring Modulith cannot express, in ArchUnit.
 *
 * <p>{@code ModuleStructureTest} covers what Modulith owns — declared dependencies and
 * cycles. Everything else this project wrote down in prose and had no mechanism for lands
 * here. It started as the four structural properties below and is now fourteen rules — the
 * later ones are not about dependency edges at all but about <em>tallies this repository was
 * keeping by hand</em> (§147's duplicate record shapes, §152's callerless repository methods,
 * §153's unexercised bean methods, §154's readerless columns), each one a question somebody
 * had been answering with a grep once per slice. The count in this sentence has been wrong
 * before; the rules below are the list.
 *
 * <p>The four it began with:
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
 * <p>Like {@code ModuleStructureTest}, this needs no container and no Spring context.
 * ~~It runs on bytecode alone.~~ <strong>Thirteen of the fourteen do</strong>;
 * {@link #everyMappedColumnHasAReader} also reads the production <em>source text</em>, and
 * the reason is at the rule: a column's reader is usually a query, and a query is a string.
 * Do not "tidy" it back onto bytecode — over bytecode alone that rule reports 212 fields of
 * 635 and is unshippable, which was measured before it was written.
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

    /**
     * The production classes by name — the set the caller rules ask "was this a test?" against.
     * Derived from {@link #PRODUCTION} rather than from a name pattern, so a test class that does
     * not end in {@code Test} is still a test class here.
     */
    private static final Set<String> PRODUCTION_NAMES = PRODUCTION.stream()
            .map(JavaClass::getName)
            .collect(Collectors.toCollection(TreeSet::new));

    /**
     * The product's sources — the corpus both column rules read, and the only thing in this file
     * that is not bytecode. Relative, because Gradle runs a test with the project directory as its
     * working directory; the rules assert the corpus is non-empty rather than trusting that. It is
     * also where an entity's own file is, so a field's declaration does not count as its reader.
     *
     * <p><strong>It was {@code src/main} + {@code src/test} until §188.</strong> The narrowing is
     * argued at each rule and the argument is one sentence in both: <em>the product is the
     * population</em>. A fixture that INSERTs a row proves a column can hold a value; a fixture that
     * SELECTs one proves a write happened. Neither is the question either rule asks, which is
     * whether anything the product does produces or consumes the value.
     *
     * <p><strong>Narrowing it deleted a trap rather than merely changing a number, and that is the
     * part worth keeping.</strong> {@code OWN_FILE} used to exclude this file from its own corpus,
     * and it was not housekeeping — without it the reader rule could not fail. Every key in
     * {@link #READERLESS_BY_DECISION} is the string {@code "Entity.field"}, so each argument for
     * keeping a column was itself a string literal naming that column, and the corpus read the
     * argument as the reader; five of the seven members then extant made themselves disappear. The
     * arguments live in {@code src/test} and the corpus is {@code src/main}, so the constant is gone
     * because the shape it guarded is now unreachable — not because anyone decided it was safe. It
     * was the same shape §152 found in a javadoc and §153 in a prefix match: the prose about a seam
     * standing in for the seam, that time inside the check written to stop that happening.
     */
    private static final Path MAIN_SOURCE = Path.of("src", "main", "java");

    /** A mapping declares a column, it does not read one — stripped before the corpus is searched. */
    private static final Pattern MAPPING_ANNOTATION = Pattern.compile(
            "@(?:Column|JoinColumn|Table|SecondaryTable|OrderColumn|MapKeyColumn)\\s*\\([^)]*\\)");

    /** Prose is not a reader. Three separate slices' comments named a column nothing ever selects. */
    private static final Pattern COMMENT = Pattern.compile("/\\*.*?\\*/|//[^\\n]*", Pattern.DOTALL);

    /** An INSERT names the columns it WRITES — a fixture's column list is not a read. */
    private static final Pattern INSERT = Pattern.compile("insert\\s+into", Pattern.CASE_INSENSITIVE);

    /** Text blocks first: a native query in this codebase is a text block more often than not. */
    private static final Pattern STRING_LITERAL = Pattern.compile(
            "\"\"\"(.*?)\"\"\"|\"((?:[^\"\\\\\\n]|\\\\.)*)\"", Pattern.DOTALL);

    /** Any declared name followed by a parameter list — Spring Data derives the query from it. */
    private static final Pattern METHOD_NAME = Pattern.compile("\\b(\\w+)\\s*\\(");

    /**
     * The migrations — SQL that writes without any Java in between, and <strong>{@code src/main}
     * only</strong>.
     *
     * <p>It was {@code src/main/resources} <em>and</em> {@code src/test/resources} until §188, for
     * the same reason the Java corpus reached into {@code src/test}, and it came out for the same
     * reason: the harness's init script and its fixtures are not the product. See
     * {@link #everyMappedColumnHasAWriter}'s corpus note.
     */
    private static final Path MAIN_RESOURCES = Path.of("src", "main", "resources");

    /** {@link #COMMENT}'s SQL spelling. V1's comments carry the why, and prose is not a write. */
    private static final Pattern SQL_COMMENT = Pattern.compile("--[^\\n]*");

    /**
     * A statement that WRITES, up to its terminator — {@link #everyMappedColumnHasAWriter}'s whole
     * SQL corpus, and the exact complement of what {@link #withoutWrites} strips for the reader
     * rule. Deliberately not narrowed to the column list or the {@code SET} clause: a {@code WHERE}
     * inside an {@code UPDATE} would then be the only place a column is spelled and the rule would
     * call it written, which over-counts in the direction this file has already chosen twice.
     */
    private static final Pattern WRITE_STATEMENT = Pattern.compile(
            "\\b(?:insert\\s+into|update)\\b[^;]*", Pattern.CASE_INSENSITIVE);

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

    /**
     * The public bean methods no production caller reaches — the population, pinned rather than
     * emptied.
     *
     * <p>Each entry is a decision recorded in the rule below and argued at the method itself. A
     * new entry, or the disappearance of one of these, fails the build: the first says a seam has
     * been built and left unexercised without anybody deciding to, the second says an argument
     * somebody wrote is now describing code that is gone.
     *
     * <p><strong>EMPTY, and it emptied by the exit the rule describes rather than by deletion.</strong>
     * Its one member was {@code Outbox.record} — the outbox's occurrence arm, built in §131 and
     * called by nothing but {@code OutboxDispatchTest} for eighty-odd sections, because every event
     * type the product had was coalesced and consumed by an argument-less recompute. The entry's
     * own text said the trade was "one recorded defect shape for the other".
     *
     * <p>The notifications slice is the caller. A message whose entire content is the payload
     * cannot be sent from an event that has none, so {@code execution.RunStatusService} and
     * {@code work.BoardService} both record through it — and the exemption therefore expired and is
     * removed rather than being left as an argument about code that is now exercised, which is what
     * {@code everyExemptionStillExemptsSomething} exists to prevent one file over. The map stays
     * because the next unexercised seam should still cost a sentence somebody has to defend.
     */
    private static final Map<String, String> UNEXERCISED_BY_DECISION = Map.of();

    @Test
    @DisplayName("every public bean method is exercised by the product, not only by a test")
    void everyPublicBeanMethodIsExercisedInProduction() {
        // §152 asked which repository methods have no caller at all, and counted a test as a
        // caller on the stated grounds that "a seam whose consumer is a test is a seam with a
        // consumer" — AuditEventRepository.findByActionOrderByOccurredAtDesc is read only by a
        // test, because the audit trail has no screen yet, and the row it reads is one the product
        // really wrote.
        //
        // This is the complementary question, and the widening §152 granted does not survive it.
        // That argument holds for a READ seam: the write is the product's, the test only observes
        // it. It does not hold for a WRITE seam. A public method on a bean is something the
        // application offers to do; if every caller is a test, the product has never once done it,
        // and what the test proves is that it WOULD work rather than that it does.
        //
        // Pinned to an exact population rather than asserted empty. §152 refused an ignore list
        // and was right to — its broad form would have shipped with one longer than the rule — but
        // an ignore list and this are not the same object. An ignore list says "these do not
        // count"; this says "these are all of them, and here is the argument for each", and it
        // fails in BOTH directions. Deleting the argued method reddens the build too, so the
        // argument cannot outlive the code it is about, which is the failure this whole line of
        // slices keeps finding (§152's countLiveCasesBySuite, and ProvisionCompanyCommand's own
        // isRequestedBy, whose javadoc argued for keeping it in the same slice that stopped
        // calling it).
        //
        // THREE narrowings, each measured rather than assumed. Broad, the population is 98 and
        // unshippable — record accessors, entity getters, and every implementation of an interface
        // whose production callers go through the interface (ReportParser.parse has four
        // implementations and no direct caller of any of them).
        //
        //   1. Spring beans only, and not controllers: a handler method's caller is the dispatcher,
        //      reflectively, and bytecode cannot see it.
        //   2. Public only. It is what makes this rule work here and it is a property of THIS
        //      codebase rather than of Java: every @Scheduled method in it is package-private
        //      (four of four, checked), so the container-invoked methods are already out — and a
        //      package-private method that only a test calls is a seam somebody deliberately opened
        //      for the test, like TokenRateLimiter.trackedTokens, which exists so §141's eviction
        //      can be observed at all.
        //   3. Not an override. The caller of an implementation is whoever holds the interface.
        //
        // A self-call counts as a production caller: ProvisionCompanyCommand.parse is reached from
        // run() in the same class, and a method its own class uses is exercised whenever that class
        // is. Getting this backwards is how the first draft of this rule reported parse and hid
        // Outbox.record at the same time — the filter meant to drop self-calls was a prefix match,
        // so OutboxDispatchTest read as a self-call of Outbox. §152's lesson about a hand-grep,
        // reproduced inside the check written to replace hand-greps.
        Map<String, String> unexercised = new TreeMap<>();
        int examined = 0;

        for (JavaClass type : PRODUCTION_AND_TESTS) {
            if (!PRODUCTION_NAMES.contains(type.getName())
                    || type.isInterface()
                    || !type.isMetaAnnotatedWith(Component.class)
                    || type.isMetaAnnotatedWith(Controller.class)) {
                continue;
            }
            for (JavaMethod method : type.getMethods()) {
                if (!method.getModifiers().contains(JavaModifier.PUBLIC) || overridesASupertype(method)) {
                    continue;
                }
                examined++;
                Set<String> callers = new TreeSet<>();
                method.getAccessesToSelf().forEach(a -> callers.add(a.getOriginOwner().getName()));
                method.getReferencesToSelf().forEach(r -> callers.add(r.getOriginOwner().getName()));
                if (callers.isEmpty() || callers.stream().anyMatch(PRODUCTION_NAMES::contains)) {
                    continue;
                }
                unexercised.put(type.getSimpleName() + "." + method.getName(), String.join(", ", callers));
            }
        }

        assertThat(examined)
                .as("no public bean method was examined, so this rule passes vacuously — the "
                        + "stereotype predicate or the import has moved")
                .isGreaterThan(50);

        assertThat(unexercised.keySet())
                .as("""
                        a public method on a bean is something the application offers to do, and \
                        every caller of this one is a test — so the product has never done it, and \
                        the test proves only that it would. Give it a production caller, delete it \
                        (§121's findQuarantined and §152's ten are the precedent), or add it to \
                        UNEXERCISED_BY_DECISION with the argument written at the method itself. \
                        The third is a decision and costs a sentence somebody has to defend; the \
                        point of this rule is that it cannot be taken silently""")
                .containsExactlyInAnyOrderElementsOf(UNEXERCISED_BY_DECISION.keySet());
    }

    /**
     * Whether a supertype declares this method — an implementation whose callers hold the
     * interface, not the class.
     */
    private static boolean overridesASupertype(JavaMethod method) {
        JavaClass owner = method.getOwner();
        List<JavaClass> supertypes = new ArrayList<>(owner.getAllRawInterfaces());
        supertypes.addAll(owner.getAllRawSuperclasses());
        for (JavaClass supertype : supertypes) {
            for (JavaMethod candidate : supertype.getMethods()) {
                if (candidate.getName().equals(method.getName())
                        && candidate.getRawParameterTypes().equals(method.getRawParameterTypes())) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * The mapped columns nothing reads — the population, pinned rather than emptied.
     *
     * <p>{@link #UNEXERCISED_BY_DECISION}'s object, at the column rather than at the method, and
     * with the same both-directions property: a new readerless column fails the build, and so does
     * giving one of these a reader without deleting its entry. The argument for each is written at
     * the field itself; what is here is the one-line reason and the shape it is an instance of.
     *
     * <p><strong><del>Twelve since §160</del> <del>Eleven since §181</del> EIGHTEEN since §188</strong>,
     * which is the first time this count has gone up and the reason is the corpus rather than the
     * code: {@code src/test} came out of it, so seven columns whose only reader was a fixture are
     * findings now. The seven are argued at their fields and in the entries below, and one of them
     * is worth reading before the rest — {@code TestRun.releaseSortKey} is §151's, and §151 recorded
     * the absence of a reader as finding 3 and deliberately did not act on it. Two more,
     * {@code AuditEvent.actorTokenId} and {@code ReportArtifact.generatedAt}, arrive by an argument
     * that used to be the argument against them: each was cited, at the field and in this rule's own
     * comment, as the example of a column whose test SELECT counted.
     *
     * <p>{@code rawStorageKey} left by getting both halves at
     * once, which is the first exit from this rule in either direction and the second time a member
     * has been written out rather than argued for (§157 did it in the writerless twin). Its entry
     * had said closing it was "a storage read path and an operation, not a wiring fix", and that was
     * accurate; what neither §154 nor §156 checked was whether the port it was waiting for had
     * already arrived, which it had, in §125.
     *
     * <p><strong>§181 is the first exit in the direction this rule was least expecting, and the
     * member that took it was the exemplar.</strong> {@code CaseHealth.incomplete} acquired a
     * reader — {@code CaseHealthRecomputeTest.signalsOf} selects the column so
     * {@code Boundaries.otherSignalsAreNotClobbered} can prove the recompute leaves it alone, the
     * previous three witnesses for that assertion having each been given a writer in turn. So the
     * rule reported it, correctly by its own definition (a test that SELECTs a column is that
     * column's reader, §132's {@code actorTokenId} being the precedent), and the entry is gone.
     *
     * <p><strong>It is a weaker reader than this rule was written for, and the rule cannot tell —
     * which is worth stating rather than working around.</strong> The defect here is an
     * <em>unverified value</em>: something written on every row that nothing checks. This new reader
     * verifies no value; it asserts the column's <em>absence</em> of one. The two are different, the
     * corpus is text and sees only the word — and the honest response is still to delete the entry,
     * because an exemption list that keeps a member after the rule stops reporting it is §162's
     * one-directional ignore list, which is the thing this map's both-ways shape exists to prevent.
     * The column is not left undocumented by the deletion: every word of its argument is at the
     * field and in {@link #WRITERLESS_BY_DECISION}, where it remains, and where being unwritten is
     * the half that was ever load-bearing.
     *
     * <p>They are two different defects — <strong>and which member is which was itself wrong twice
     * until {@link #everyMappedColumnHasAWriter} computed the other half</strong> (§156).
     * <strong>Written and readerless</strong> — {@code locale}, {@code timezone}, {@code spaceId},
     * {@code addedAt}, {@code addedBy}, {@code invitedAt}, {@code invitedBy} — is §151's shape
     * and the expensive one: a write per row recording something no screen, report or query has
     * ever asked for, free to be wrong for as long as nobody looks, which is exactly how
     * {@code release_sort_key} sorted a release below its own release candidate from M0 to §151
     * with 2389 tests green. <strong>Writerless as well</strong> — {@code anchorSlug},
     * {@code scheduleCron}, {@code sharedWithTeamId}, {@code bulkOperationId}, <del>and since §156's
     * correction {@code incomplete}</del> — is §116's cheaper
     * one: a column nobody fills is a column nobody is misled by, and its whole cost is that six
     * javadocs describe a state the database has never been in.
     *
     * <p><del>{@code CaseHealth.incomplete} is the entry to copy.</del> <del><strong>It left in
     * §181, and it is still the entry to copy</strong> — one map over, in
     * {@link #WRITERLESS_BY_DECISION}.</del> <strong>It left THAT map too, in §182, by acquiring a
     * derivation</strong> ({@code CaseCompleteness}); it is in neither list now, having spent one
     * slice in each and one slice in both. Its argument was already at the field
     * before this rule existed, in the form the rule asks for: what it is, why it is mapped, and
     * why it stops there — and it is worth reading there for a second reason now: <strong>the same
     * column moved between these two maps three times without the column changing at all.</strong>
     * What moved was which end was dead, which is a fact neither rule can report alone and the
     * plainest argument there is for running the pair.
     */
    private static final Map<String, String> READERLESS_BY_DECISION = Map.ofEntries(
            Map.entry("UserIdentity.emailAtLink",
                    "what the IdP said about the address at link time, kept deliberately as a "
                            + "RECORD and never read back — the entity's own header says so, and "
                            + "reading it would be the defect: the identity key is (provider, "
                            + "subject) precisely so that a corporate rename does not fork "
                            + "somebody into a second account. A reader here is the thing §6r "
                            + "exists to keep out"),
            Map.entry("UserIdentity.linkedAt",
                    "when the binding was made. Written on every link, and there is no screen in "
                            + "the product that lists a person's external identities — the "
                            + "federation surface built in §217 configures PROVIDERS, not the "
                            + "identities under them. Exits when that screen exists"),
            Map.entry("AppUser.locale",
                    "V1 NOT NULL, set to the constant \"en\" by AppUser.invite and never read. A "
                            + "stored preference with no writer but the default and no reader at "
                            + "all is not a preference; giving it either half alone makes it "
                            + "worse, because a value a person can set and nothing honours is a "
                            + "broken promise rather than a gap"),
            Map.entry("AppUser.timezone",
                    "locale's shape, constant \"UTC\", and the more pointed of the two: AppUser's "
                            + "own @Version javadoc reasons about what happens \"when someone "
                            + "edited their own timezone\", which is an act this product cannot "
                            + "perform. Timestamps render in the browser's zone and this column "
                            + "has never been consulted"),
            Map.entry("DocPage.spaceId",
                    "V1 NOT NULL, written by DocPage.create on every page, read by nothing. "
                            + "DocSpace's own header states the other half — the space \"is not on "
                            + "the wire and has no endpoint\" — so the foreign key is maintained "
                            + "for a grouping no caller can ask for"),
            Map.entry("SuiteCase.addedAt",
                    "§98 made a suite flat — filed, added and tagged — and this is the added "
                            + "arm's own provenance, stamped by SuiteCase.of on every membership "
                            + "and never read back. \"When was this case put in this suite\" is "
                            + "answerable from the row and has never been asked"),
            Map.entry("SuiteCase.addedBy",
                    "addedAt's pair and the worse half, for CompanyMembership.invitedBy's reason: "
                            + "this one is not a clock stamp but a real actor id, carried from the "
                            + "endpoint to the database on every add so that nothing can read who "
                            + "did it. §154 wrote \"caller-supplied\" here and at the field, which "
                            + "was wrong (corrected 2026-08-10): the value is jwt.getSubject() in "
                            + "SuiteController.create, passed down through SuiteWriteService and "
                            + "SuiteMembershipWriter. An actor id a caller supplies is one a "
                            + "caller chooses, so the two are not the same claim"),
            Map.entry("CompanyMembership.invitedAt",
                    "stamped by invite() and again by reinvite(), read by nothing. UserView is "
                            + "the contract's User and carries neither this nor invitedBy, so the "
                            + "reader it wants is an administrative column on the members screen "
                            + "and therefore a contract change rather than a wiring fix"),
            Map.entry("CompanyMembership.invitedBy",
                    "invitedAt's pair and the worse half, SuiteCase.addedBy's shape exactly: "
                            + "THREE writers carry an actor id to the database for a fact nothing "
                            + "has ever read back — MembershipInviteService's invite() and "
                            + "reinvite(), plus CompanyProvisioningService, whose value may be "
                            + "null because an unattended provisioning run has no actor. §154 "
                            + "counted two, having counted the two factory METHODS rather than "
                            + "their call sites (corrected 2026-08-10)"),
            Map.entry("DocBlock.anchorSlug",
                    "writerless AND readerless. V1's human-readable URL convenience, and the "
                            + "class header already names it as the trap the schema names beside "
                            + "the id: a link targets (page_id, block_id), never an anchor "
                            + "derived from content. Give it a writer and a reader in one commit "
                            + "or drop it in a migration — not one of the two"),
            Map.entry("ReportDefinition.scheduleCron",
                    "writerless AND readerless. Scheduled reports are on the high-level plan's §8 "
                            + "blocked list — they need a notification channel that does not "
                            + "exist — and ReportDefinition.create takes no parameter for it on "
                            + "purpose, with the reason written at the factory"),
            Map.entry("ReportDefinition.sharedWithTeamId",
                    "writerless AND readerless, scheduleCron's shape and the same factory's "
                            + "decision: sharing a saved report with a team is not built"),
            Map.entry("AuditEvent.bulkOperationId",
                    "writerless AND readerless — the field, its mapping and its getter are the "
                            + "only three mentions of it in main and test alike, so its javadoc's "
                            + "\"set when this event is one of many\" describes a state that has "
                            + "never occurred. The audit trail has no screen, which §152 recorded "
                            + "from the other side when AuditEventRepository's only reader turned "
                            + "out to be a test"),
            Map.entry("AuditEvent.actorTokenId",
                    "bulkOperationId's neighbour and the opposite half: §132 spent a slice giving "
                            + "it a writer, and the audit trail still has no screen. It was this "
                            + "rule's own worked example for counting a test as a reader — "
                            + "IngestionEndpointTest selects the column — until §188 narrowed the "
                            + "corpus to src/main, so it arrives here by the argument it used to "
                            + "be the argument against. The reader it wants is the audit screen "
                            + "§152 named, which is a contract change"),
            Map.entry("ClassificationDimension.controlled",
                    "mapped and not on the wire, argued at the field since it was written: the "
                            + "contract's ClassificationDimension does not carry it and inventing "
                            + "the field would commit the SPA to a distinction nothing renders. "
                            + "Every seeded dimension is controlled, so the column has one value "
                            + "in every deployment — cheaper than the usual shape, because a "
                            + "reader added later would find the data already correct"),
            Map.entry("Permission.area",
                    "the catalogue's grouping for humans, seeded by V3 and served by nothing. The "
                            + "SPA groups from its own copy in frontend/src/lib/permissions.ts, "
                            + "which is exhaustive against the contract by construction and "
                            + "therefore the stronger of the two — so serving this column would "
                            + "not retire the copy, and neither side reads the other. What both "
                            + "headers claim instead is that the two groupings AGREE, which is a "
                            + "checkable sentence nothing was checking"),
            Map.entry("ReportArtifact.generatedAt",
                    "stamped by ReportArtifact.of on every generate, and the entry its own field "
                            + "javadoc has been predicting: it said the reason it was NOT here was "
                            + "that a test selects the column, which stopped being a reason in "
                            + "§188. ReportArtifactRepository declares no method at all. The "
                            + "reader it wants is an artifact list, which is a contract change "
                            + "rather than a wiring fix"),
            Map.entry("SuiteVersion.contentHash",
                    "the digest that exists so a LATER version can be compared to an earlier one, "
                            + "on the one version table that has never written a second row: "
                            + "SuiteVersions writes v1 at create and nothing since, and its header "
                            + "says why a decide() sibling would be \"a comparison with exactly "
                            + "one caller that always answers yes\". PageVersions.recordEdit is "
                            + "what the reader looks like when it arrives; the exit is the "
                            + "composition writes SuiteVersions already names as owed"),
            Map.entry("TestRun.releaseSortKey",
                    "§151's column, and the sharpest member of this list: written on three tables, "
                            + "indexed on three tables, and there is no WHERE and no ORDER BY over "
                            + "it anywhere in src/main. That is not an oversight §151 missed — it "
                            + "is §151's own finding 3, recorded and deliberately not acted on, "
                            + "because giving it a reader is §6q's latest-release view and a "
                            + "feature. What §151 bought was that the eventual reader is not "
                            + "written against three tables of keys that order releases backwards"),
            Map.entry("TestExecution.releaseSortKey",
                    "TestRun's, one table along, and the one whose absence of a reader was hardest "
                            + "to see: ExecutionSource DOES select it — into execution_fact's own "
                            + "release_sort_key, which nothing orders by either. A value "
                            + "propagated through a third table and consumed nowhere is still "
                            + "consumed nowhere, and this rule could not report it before §188 "
                            + "because the SELECT lives inside an INSERT and withoutWrites strips "
                            + "the statement whole"));

    @Test
    @DisplayName("every mapped column has a reader — the readerless tally, computed")
    void everyMappedColumnHasAReader() {
        // §152 asked which repository methods have no caller and §153 which bean methods only a
        // test calls. This is the third question in that family and the one the repository has
        // answered by hand the longest: which COLUMN does nothing read. §116, §117 and §132 each
        // found the writerless form of it one instance at a time, and §151 found the readerless
        // form — release_sort_key, on three tables, with three indexes and no ORDER BY anywhere,
        // wrong since M0 and undetectable because nothing looked. Every one of those was a grep
        // somebody happened to run because the slice was already about that table.
        //
        // THE READER IS OFTEN NOT JAVA, WHICH IS WHY THIS RULE READS SOURCE TEXT. It is the only
        // rule here that does, and the reason is that bytecode is the wrong observer for the
        // question: a column is read by JPQL in a @Query string, by native SQL in a text block
        // inside a service, and by a Spring Data method NAME from which the query is derived — and
        // none of the three is a field access. Measured, not assumed: over bytecode alone the
        // population is 212 of 635 fields, which is not a rule, it is a shrug. With the three SQL
        // spellings counted it is seven. §108's ConfigConventionTest is the precedent for a check
        // that reads the text rather than a resolved value, and it is the same lesson — every other
        // check here observes what the compiler produced, and the defect was in what somebody wrote.
        //
        // FOUR THINGS COUNT AS A READER, and each of the four was added because leaving it out
        // reported a column something really reads. A whole-word occurrence of the COLUMN name in
        // any file but the entity's own; the FIELD name inside a string literal; the capitalised
        // field name in any name a repository declares; or a Java read.
        //
        // THE CORPUS IS src/main, AND IT WAS src/main + src/test UNTIL §188 — where the reasoning
        // above used to run "a test that SELECTs a column to assert what a write left behind is
        // that column's reader (§152), and it is the only one three of these columns have."
        //
        // THAT SENTENCE WAS ANSWERING A DIFFERENT QUESTION, and §187 is where the distinction got
        // its name. §153's rule is about SEAMS: a method exists to be called, so a test calling it
        // is a caller and everyPublicBeanMethodIsExercised counts it. A COLUMN is not a seam, it is
        // a VALUE, and this rule's failure message has always said so — "that is not waste, it is
        // an unverified value". A fixture that selects a column proves the write happened. It does
        // not make anything downstream consult the value, which is the only property that can make
        // a wrong one matter. release_sort_key is the proof and it is not hypothetical: the column
        // is written on three tables, indexed on three tables, copied from test_execution into
        // execution_fact by ExecutionSource — and there is no WHERE and no ORDER BY over it
        // anywhere, which is exactly why it could sort a release below its own release candidate
        // from M0 to §151 with the whole suite green. Under the old corpus this rule was silent
        // about it, because §151's own tests name the column while asserting the fix.
        //
        // THE COST IS PAID IN ONE PLACE AND IT IS NAMED THERE. AuditEvent.actorTokenId was the
        // instance §132 cited for the old corpus — a slice spent giving it a writer, no product
        // code reading it, and IngestionEndpointTest's "SELECT actor_token_id FROM audit_event"
        // standing in as the reader. It is in READERLESS_BY_DECISION now, with the same argument
        // bulkOperationId has carried since §152: the audit trail has no screen.
        //
        // TWO THINGS ARE STRIPPED FIRST, and both were measured. A mapping annotation is not a
        // read — without stripping it, every entity declaring its own created_at marks every other
        // entity's created_at as read, and the population collapses from 26 to 7 for no reason. A
        // COMMENT is not a read either, and this one is uncomfortable: nineteen columns read as
        // live only because some javadoc somewhere mentioned them, which in a repository whose
        // comments carry the reasoning is exactly the trap §152 named — the prose about a seam
        // outliving the seam. An INSERT's column list is a write and is dropped with the same
        // argument; a fixture that inserts a row is not a reader of what it inserted.
        //
        // THIS FILE USED TO BE EXCLUDED FROM ITS OWN CORPUS and no longer needs to be — see
        // MAIN_SOURCE, where the trap and its disappearance are both recorded. It was not
        // housekeeping: every key in READERLESS_BY_DECISION is the string "Entity.field", so each
        // argument for keeping a column was a string literal naming that column, the corpus read
        // the argument as the reader, and five of the thirteen members then extant deleted
        // themselves. §152 found a javadoc standing in for the seam it described and §153 found a
        // prefix match hiding the method it was meant to report; that was the third, inside the
        // check written to end the first two, and it is the reason the population went 7 → 13 after
        // the last fix. The arguments are in src/test and the corpus is src/main, so it cannot
        // recur — which is a better outcome than a filter, and is the one thing §188's narrowing
        // did for free.
        //
        // The remainder still over-counts and it does so ON PURPOSE, one way. A native query
        // naming ANOTHER table's lock_version marks this one read, because the corpus is text and
        // has no idea which table a statement is about. A rule that reports a field something
        // really reads earns an ignore-list entry and is then ignored; a rule that misses one
        // stays trustworthy and finds it the next time the corpus tightens. What that costs is
        // named where it is paid, and §155 paid it: everyLockVersionColumnIsAVersion below is the
        // rule this comment used to say did not exist, written because test_run.lock_version and
        // work_item.lock_version are two columns THIS rule cannot see — a native query naming
        // another table's lock_version marks them read. §154 verified both by hand. Both are
        // unmapped now, and the run's actual race is closed by a predicate rather than a counter.
        //
        // THE FIRST DRAFT'S CORPUS WAS WRONG IN EXACTLY §152'S WAY. It matched repository method
        // names against findBy*/countBy*/existsBy*, so ReportJob.requestedAt was reported as
        // readerless while findFirstByStatusOrderByRequestedAtAsc reads it — the whole scheduler
        // reads it. A hand-written pattern standing in for a population, inside the check written
        // to replace hand-written patterns. It matches every declared method name now.
        //
        // No exemption for @Id, @Version or company_id, and that is a result rather than a choice:
        // all three were carved out of an earlier draft and all three turned out to be matched by
        // the corpus anyway — an id by findById's derived name, lock_version by the queries that
        // name it, company_id by the RLS predicate and the Hibernate filter condition. A special
        // case that the general rule already covers is a special case that will one day be wrong.
        Map<Path, String> columnText = new LinkedHashMap<>();
        Map<Path, String> literalText = new LinkedHashMap<>();
        StringBuilder repositoryMethodNames = new StringBuilder();
        try (Stream<Path> sources = Files.walk(MAIN_SOURCE)) {
            for (Path file : sources.filter(p -> p.toString().endsWith(".java")).toList()) {
                String reading = withoutWrites(
                        COMMENT.matcher(Files.readString(file)).replaceAll(" "));
                columnText.put(file, MAPPING_ANNOTATION.matcher(reading).replaceAll(" "));
                literalText.put(file, literalsOf(reading));
                if (file.getFileName().toString().endsWith("Repository.java")) {
                    METHOD_NAME.matcher(reading).results()
                            .forEach(m -> repositoryMethodNames.append(' ').append(m.group(1)));
                }
            }
        } catch (IOException unreadable) {
            throw new AssertionError("src/main is this rule's corpus", unreadable);
        }
        String methodNames = repositoryMethodNames.toString();

        assertThat(columnText)
                .as("the source corpus is empty or tiny, so every column below would read as "
                        + "unread — the working directory or the source layout has moved")
                .hasSizeGreaterThan(600);

        Map<String, String> readerless = new TreeMap<>();
        int examined = 0;

        for (JavaClass type : PRODUCTION_AND_TESTS) {
            if (!PRODUCTION_NAMES.contains(type.getName()) || !type.isAnnotatedWith(Entity.class)) {
                continue;
            }
            Path own = MAIN_SOURCE.resolve(type.getName().replace('.', '/') + ".java");
            for (JavaField field : type.getFields()) {
                if (field.getModifiers().contains(JavaModifier.STATIC)) {
                    continue;
                }
                examined++;
                String column = columnOf(field);
                String name = field.getName();
                String capitalised = Character.toUpperCase(name.charAt(0)) + name.substring(1);
                boolean read = isReadInJava(field)
                        || methodNames.contains(capitalised)
                        || namedIn(columnText, own, column)
                        || namedIn(literalText, own, name);
                if (!read) {
                    readerless.put(type.getSimpleName() + "." + name, column);
                }
            }
        }

        assertThat(examined)
                .as("no entity field was examined, so this rule passes vacuously — the @Entity "
                        + "predicate or the import has moved")
                .isGreaterThan(500);

        assertThat(readerless.keySet())
                .as("""
                        a column is written on every row and no code, no query and no derived \
                        method name ever reads it back. That is not waste, it is an unverified \
                        value: §151's release_sort_key sorted a release below its own release \
                        candidate from M0 to §151 because nothing looked at it, and nothing \
                        looking is exactly what makes a wrong value survive. Give it a reader, \
                        delete the column in a migration, or add it to READERLESS_BY_DECISION \
                        with the argument written at the field — and if a query DOES read it, the \
                        corpus above is what is wrong, not this list""")
                .containsExactlyInAnyOrderElementsOf(READERLESS_BY_DECISION.keySet());
    }

    /**
     * The columns nothing writes, with the one-line reason each stays mapped. The argument itself
     * lives at the field, like {@link #READERLESS_BY_DECISION}'s.
     *
     * <p><del>Fourteen</del> <del>thirteen since §157</del> <del>twelve since §158</del> <del>eleven since §159</del>
     * <del>ten since §160</del> <del>eight since §178</del> <del>seven since §181</del>
     * <del>six since §182</del> <strong>NINE since §188</strong> — and the first time this count has
     * gone <em>up</em>, which is what a corpus narrowing looks like from inside a map that had only
     * ever emptied. The three arrivals are one column on three tables ({@code change_comment} on
     * {@code doc_page_version}, {@code suite_version} and {@code test_plan_version}), and only one
     * of them arrived because the corpus moved: the other two were being hidden by an explicit
     * {@code = null} in their factories, which is an assignment and therefore a writer to a rule
     * that reads bytecode. <strong>A no-op write is the one spelling that defeats this rule</strong>
     * — the mirror of the fixture problem below, arrived at from inside {@code src/main}, where no
     * corpus change can reach it. Both no-ops are deleted.
     *
     * <p><strong>The corpus is {@code src/main} since §188</strong> and {@code @Version} fields are
     * skipped; both are argued in the rule. What the narrowing cost is one entry
     * ({@code DocPageVersion.changeComment}) and what it bought is that a column whose only writer
     * is a test fixture is now a finding here, which is the question §187 could only ask one
     * granularity up.
     * <strong>Eight</strong> members have now left by being <em>written</em>
     * rather than deleted — {@code CaseHealth.staleApproval} (§157), {@code CaseHealth.conditionDrift}
     * (§158), {@code Project.archivedAt} (§159), {@code IngestionJob.rawStorageKey} (§160),
     * {@code CaseHealth.outdatedSpecChanged} + {@code CaseHealth.unlinked} (§178, the first slice to
     * take <em>two</em> out at once, because both are read off one statement over one table),
     * {@code CaseHealth.orphaned} (§181, the last of the five) and {@code CaseHealth.incomplete}
     * (§182, which was never one of the five — it was not a signal, and the derivation made it one)
     * — <strong>so no {@code case_health} column is in this map at all</strong>, and
     * the rule itself has not moved for any of them, which is what makes "an entry here is a
     * decision to leave a column empty, not a permit to keep it that way" a description rather than
     * a hope. <strong>This javadoc's own count was stale by one from §158 to §159</strong>, while
     * the comment inside the test method below said twelve: two tallies, four lines apart, in the
     * file whose subject is tallies kept by hand.
     *
     * <p><strong>§178 is also where the exit stopped being cheap for the remainder.</strong> Four of
     * the five {@code case_health} members left in five slices, and the fifth
     * ({@code CaseHealth.orphaned}) is the one every one of those slices named as needing a decision
     * first. An entry list that empties by a run of easy members ends on the hard one, so a flat
     * count going down is not evidence the next one is like the last.
     *
     * <p><strong>§181 took that fifth one, and the prediction above was half right.</strong> It was
     * the hard one and it did end the run — four slices deferred it — but what made it hard was
     * entirely upstream of this map: the derivation is one class and one statement, shorter than
     * the two that came before it. The entry's own last sentence is the accurate part and is worth
     * generalising, since it is the only kind of blocker an entry here can record but a count
     * cannot: <em>needs a decision</em> is not <em>needs a lot of code</em>, and a list that reports
     * only a number cannot tell a reader which one it is looking at.
     *
     * <p>§160 is the first member to leave <em>both</em> this rule and
     * {@link #READERLESS_BY_DECISION} in one slice, and that is not a coincidence: its entry in each
     * argued for the other half's absence, so writing one alone would have been the two-states-at-once
     * shape {@code IngestionJob}'s own field javadoc warned about.
     *
     * <p><del>Five of the eight</del> <del>Four of the seven</del> <del>Four of the six</del> Four
     * of the nine are already in
     * {@link #READERLESS_BY_DECISION} — the
     * four §154 called
     * "writerless as well". <del>Plus {@code CaseHealth.incomplete},
     * whose classification §154 got wrong in this exact direction.</del> That one left the other map
     * in §181 by acquiring a reader, so the overlap shrank without the column changing at all —
     * <strong>and left this one in §182 by acquiring a writer</strong>, so the entry that was the
     * reference for both maps is now in neither, and the four-of-six overlap is four of a map with
     * two entries outside it.
     * The overlap is the point of
     * running both rules rather than one: a column can be dead at either end, and only the pair says
     * <em>which</em> — and §181 is the worked example, since a member moving from "dead at both
     * ends" to "dead at one" is a fact neither rule could report alone. The <del>five</del>
     * <del>three</del> three this rule sees alone are the ones
     * §154 structurally could not,
     * because a column with a reader is invisible to a rule about readers — and read-but-never-written
     * is the worse half, since something downstream is drawing a conclusion from a value nothing
     * produces.
     */
    private static final Map<String, String> WRITERLESS_BY_DECISION = Map.ofEntries(
            Map.entry("AuditEvent.bulkOperationId",
                    "writerless AND readerless — argued at the field and in "
                            + "READERLESS_BY_DECISION; the field, its mapping and its getter are "
                            + "the only three mentions of it anywhere"),
            Map.entry("DocBlock.anchorSlug",
                    "writerless AND readerless — argued at the field: a link targets "
                            + "(page_id, block_id), never an anchor derived from content"),
            Map.entry("ReportDefinition.scheduleCron",
                    "writerless AND readerless — scheduled reports are on the high-level plan's "
                            + "§8 blocked list and ReportDefinition.create takes no parameter for "
                            + "it, with the reason at the factory"),
            Map.entry("ReportDefinition.sharedWithTeamId",
                    "writerless AND readerless, scheduleCron's shape and the same factory's "
                            + "decision: sharing a saved report with a team is not built"),
            Map.entry("CompanyMembership.avatarUrl",
                    "V1's column for a picture nothing uploads. Already stated on the contract "
                            + "side — api.UserRef's javadoc calls it \"null for everyone today\" — "
                            + "and now stated at the column that would hold it"),
            Map.entry("IngestionJob.error",
                    "the detail of a FAILED job, and this lane never writes FAILED — argued at "
                            + "IngestionJob's header since §117: the idempotency key is consumed "
                            + "by whichever attempt writes a row, so a failed row would make the "
                            + "corrected retry replay the failure. A report that cannot be parsed "
                            + "leaves no row at all"),
            Map.entry("DocPageVersion.changeComment",
                    "V1 gives all four version tables a change_comment and only test_case_version "
                            + "has an endpoint that takes one. The exit is a contract field on the "
                            + "page save, not a wiring fix — argued at the field"),
            Map.entry("SuiteVersion.changeComment",
                    "DocPageVersion.changeComment's argument exactly, and the one that was HIDDEN: "
                            + "SuiteVersion.of assigned it null, which this rule reads as a writer, "
                            + "so two of the three identical columns were invisible while the third "
                            + "— whose factory merely omitted the field — was reported alone. The "
                            + "no-op assignment is gone (§188); a suite version is written at "
                            + "creation and nowhere else, so there is no save to comment on"),
            Map.entry("TestPlanVersion.changeComment",
                    "the third of the three, and the second that was hidden by an explicit null. "
                            + "Argued at the field; same exit as the other two"));


    @Test
    @DisplayName("every mapped column has a writer — the writerless tally, computed")
    void everyMappedColumnHasAWriter() {
        // §154 asked which mapped column nothing READS. This is the same question at the other
        // end, and it is the older of the two: §116, §117, §132 and §151 each found one instance of
        // it by hand, one slice at a time, and each wrote the instance down rather than the
        // question. §116's phrasing — "three V1 tables had never had a writer" — is this rule at
        // table granularity, answered by a person who happened to be looking.
        //
        // THE TWO RULES ARE NOT REDUNDANT AND THE OVERLAP PROVES IT. Four of these nine are in
        // READERLESS_BY_DECISION too, which is the harmless half: a column nobody fills and nobody
        // consults costs a javadoc describing a state the database has never been in. The FIVE
        // this rule sees alone are the expensive half, and they are invisible to §154 by
        // construction — a column with a reader passes that rule no matter what fills it. Something
        // downstream is drawing a conclusion from a value nothing produces: five HealthSignal
        // members were rendered as chips and counted in facets while no recompute wrote them, and
        // ProjectView.archived was required by the contract and false in every deployment there had
        // ever been. Both examples are past tense now — FOUR health signals since §157, THREE since
        // §158, ONE since §178, NONE since §181, and Project.archivedAt left this map in §159 when
        // the archive operation was built. §182 then took the LAST case_health entry — incomplete,
        // which was never a signal and became one, so it left by the same exit as the five signals
        // rather than by being deleted.
        // That is the intended exit from this list, it has now been taken SEVEN times, and each
        // time the rule's population fell without the rule changing: an entry here is a
        // decision to leave a column empty, not a permit to keep it that way. §178 took two in one
        // slice, which is the same fact one level up — both were read off one statement, so
        // deriving one alone would have left a chip and a panel on one screen disagreeing.
        //
        // IT ALSO CORRECTED §154 RATHER THAN ONLY EXTENDING IT. That rule's javadoc sorted its
        // thirteen into "written and readerless" and "writerless as well" BY HAND, one slice after
        // arguing that hand-kept tallies are what this family exists to replace, and it put
        // rawStorageKey in the expensive half — asserting a write that does not happen, fifty lines
        // below a class header that says so. The classification was a claim nothing checked, inside
        // the check written to end claims nothing checks.
        //
        // IT READS BYTECODE FIRST, WHICH IS THE MIRROR OF WHY §154 READS TEXT. A reader is usually
        // a query and a query is a string; a WRITER is an assignment, and an assignment is exactly
        // what bytecode records. The SQL arm is the remainder rather than the substance: this
        // codebase writes case_health through native INSERT ... ON CONFLICT and starts a run
        // through JPQL UPDATE, and neither is a field access. Both spellings are matched — the
        // column name for native SQL, the field name for JPQL — because startIfNotStarted names
        // r.startedAt and V1 names started_at.
        //
        // ONLY INSERT AND UPDATE STATEMENTS ARE IN THE CORPUS, which is the exact complement of
        // withoutWrites: there, an insert's column list is stripped because it is not a read; here
        // it is the only thing kept. The .sql files are in as well and the reader rule has no need
        // of them — a migration that backfills a column is a writer of it (V44 is the worked
        // example), and one that only creates it is not, because CREATE TABLE is neither verb.
        //
        // THE CORPUS IS src/main, AND IT WAS src/main + src/test UNTIL §188. §153's rule, which
        // this file has applied at three granularities since: a test caller is a consumer for a
        // READ seam and never for a WRITE one. A fixture that INSERTs a row proves the column can
        // hold a value, which nobody doubted; it says nothing about the product ever producing one.
        // §187 measured the cost of the old corpus at table granularity — case_condition_optout had
        // an entity, a repository, a filter and four files of prose, two fixtures that INSERTed
        // into it, no way for any request to create a row, and thirty-one green sections of this
        // rule — and answered it with everyEntityIsWritableByTheProduct rather than here, because
        // narrowing the corpus took the population from 6 to 22 and FIFTEEN of the sixteen new
        // members were lockVersion. That is the next paragraph, and it is why both could be done at
        // once in the end: the noise had one cause and the cause is not a finding.
        //
        // @Version IS SKIPPED, and it is the one exemption in either rule that is about the
        // OBSERVER rather than about a column. Hibernate assigns the version field itself, by
        // reflection, on every flush — so the write exists, is essential, and is in no source file
        // and no bytecode this rule can reach. Reporting fifteen of them would not be a finding
        // list with noise in it; it would be a rule asserting something untrue. The mirror rule
        // needs no such carve-out and deliberately has none: lock_version really is READ, by the
        // queries that name it, and everyLockVersionColumnIsAVersion is the rule that asks the
        // question @Version actually raises.
        //
        // THE FIRST DRAFT OVER-MATCHED, WHICH IN THIS DIRECTION HIDES FINDINGS. §154's corpus joins
        // a file's literals and searches the join, which is right for a word-boundary read; here a
        // statement runs to the next semicolon, and a fixture's "UPDATE key_sequence SET
        // next_value = 1" — no terminator — then swallowed every later literal in its file. Two of
        // the fourteen (as the population then stood) were invisible, one of them
        // case_health.unlinked, marked live by an
        // @DisplayName. writesInLiterals is the bound. Note which way each rule's over-match cuts:
        // §154's costs a missed finding it will catch next time the corpus tightens, and this one's
        // costs a finding that never appears at all.
        //
        // WHAT IT DOES NOT SEE, stated because the count would otherwise look like a claim it is
        // not making: Hibernate populates every field of every row it loads, by reflection, and
        // that is not a writer in any sense this rule cares about — the question is whether the
        // product ever PRODUCES a value, not whether one round-trips. Nor does a self-write in a
        // method with no caller count, which is isWrittenInJava's whole content and was the reason
        // Project.archivedAt was on this list until §159: it had a mutator, a good one, and the
        // only caller it had was a test fixture seeding a state no request could produce.
        Map<Path, String> writeText = new LinkedHashMap<>();
        try (Stream<Path> sources = Files.walk(MAIN_SOURCE)) {
            for (Path file : sources.filter(p -> p.toString().endsWith(".java")).toList()) {
                writeText.put(file, writesInLiterals(
                        COMMENT.matcher(Files.readString(file)).replaceAll(" ")));
            }
        } catch (IOException unreadable) {
            throw new AssertionError("src/main is this rule's corpus", unreadable);
        }
        try (Stream<Path> sources = Files.walk(MAIN_RESOURCES)) {
            for (Path file : sources.filter(p -> p.toString().endsWith(".sql")).toList()) {
                writeText.put(file, writesIn(
                        SQL_COMMENT.matcher(Files.readString(file)).replaceAll(" ")));
            }
        } catch (IOException unreadable) {
            throw new AssertionError("the migrations are this rule's corpus too", unreadable);
        }

        assertThat(writeText)
                .as("the source corpus is empty or tiny, so every column below would read as "
                        + "unwritten — the working directory or the source layout has moved")
                .hasSizeGreaterThan(600);

        Map<String, String> writerless = new TreeMap<>();
        int examined = 0;

        for (JavaClass type : PRODUCTION) {
            if (!type.isAnnotatedWith(Entity.class)) {
                continue;
            }
            for (JavaField field : type.getFields()) {
                // Hibernate writes it and no source file does — see the corpus note above.
                if (field.getModifiers().contains(JavaModifier.STATIC)
                        || field.isAnnotatedWith(Version.class)) {
                    continue;
                }
                examined++;
                boolean written = isWrittenInJava(field)
                        || namedIn(writeText, null, columnOf(field))
                        || namedIn(writeText, null, field.getName());
                if (!written) {
                    writerless.put(type.getSimpleName() + "." + field.getName(), columnOf(field));
                }
            }
        }

        assertThat(examined)
                .as("no entity field was examined, so this rule passes vacuously — the @Entity "
                        + "predicate or the import has moved")
                .isGreaterThan(500);

        assertThat(writerless.keySet())
                .as("""
                        no assignment, no INSERT, no UPDATE and no migration ever puts a value in \
                        this column, so every row holds its default for ever. That is harmless \
                        only while nothing reads it — and when something does, the read is a \
                        conclusion drawn from a value the product never produced: \
                        ProjectView.archived is required by the contract and false in every \
                        deployment, and a HealthSignal nothing computes renders as "clean" rather \
                        than as "not looked at". Give it a writer, drop the column in a migration, \
                        or add it to WRITERLESS_BY_DECISION with the argument written at the field \
                        — and if something DOES write it, this rule's corpus is what is wrong, not \
                        the list. Its mirror is everyMappedColumnHasAReader; a member of both is \
                        the cheap case, a member of this one alone is not""")
                .containsExactlyInAnyOrderElementsOf(WRITERLESS_BY_DECISION.keySet());
    }

    /**
     * Entities whose table the product cannot put a row in, though a test fixture can.
     *
     * <p><strong>One, and it is the one that was already argued.</strong> §187 asked the question
     * for the first time and the population was five; four of those were the rule's own corpus
     * being wrong rather than the product being incomplete — {@code permission} and {@code role}
     * are seeded by {@code V3}, {@code configuration} and {@code doc_space} by {@code V1}'s seed
     * functions — which is why the migrations are in it now. Measured, not predicted.
     *
     * <p>{@code case_condition_optout} was the member this rule was written for and it left the
     * list before the list existed: §187 phase 1 gave it {@code CaseConditionOptout.of} and a
     * reconcile, so the rule is green <em>because</em> that landed rather than in spite of it.
     */
    private static final Map<String, String> UNWRITABLE_BY_DECISION = Map.of(
            "CaseAutomationTag",
            "the CI SELECTION tags (§6c), and the one field on TestCaseInput still REFUSED with a "
                    + "400 rather than ignored — CaseUpdateRequest's \"the two fields this "
                    + "endpoint refuses\", of which this is now the second-to-last. Unwritable is "
                    + "the honest state while the refusal stands: the reconcile is a different "
                    + "table and a different job from the organisational `tags` §96 built, and "
                    + "building it silently here would make the 400 a lie. The exit is the same "
                    + "one classificationValueIds, the conditions and tags each took in turn");

    @Test
    @DisplayName("every entity's table has a writer IN THE PRODUCT, not only in a fixture")
    void everyEntityIsWritableByTheProduct() {
        // §116'S QUESTION, AT THE GRANULARITY §116 ASKED IT AT. ~~And the one its successor cannot
        // answer~~ — that was true for exactly one section. everyMappedColumnHasAWriter read
        // `src/test` as well as `src/main` when this rule was written, so A TABLE WHOSE ONLY WRITER
        // IS A FIXTURE READ AS WRITTEN there, and this rule existed to ask the question the column
        // rule structurally could not. §188 narrowed that corpus to `src/main` and skipped @Version
        // — the two changes the paragraph below said were needed — so the column rule now answers
        // it too, per column rather than per table.
        //
        // THIS RULE STAYS, AND NOT OUT OF SENTIMENT. The two disagree in one direction and it is
        // the direction that matters: an entity whose columns are ALL individually excused would
        // pass the column rule entry by entry while remaining a table the product cannot put a row
        // in, and case_condition_optout is precisely that shape — every one of its three columns
        // has an obvious argument, and the finding was never about any of them. One bit per table
        // is a different question from one bit per column, and §116 asked the table one.
        //
        // `case_condition_optout` is the measured instance and the reason this rule is here. It
        // shipped in V1 with an entity, an id class, a repository, a filter in CaseConditions and
        // prose in four files describing what its rows do — and no way for any request to create
        // one. Two test fixtures INSERT into it, which is the whole of why §156's rule ran green
        // over it for thirty-one sections. §153's finding at the level below: a test caller is a
        // consumer for a READ seam and never for a WRITE one, and a table is the largest write
        // seam there is.
        //
        // THE NARROW SCOPE IS THE POINT. Restricting the column rules' own corpus to src/main was
        // measured rather than proposed (§187): the writerless population went 6 → 22, of which
        // FIFTEEN were lockVersion — @Version, written by Hibernate, definitionally not a finding —
        // and exactly one was real (DocPageVersion.changeComment). The reader rule's went 11 → 18,
        // and all seven of those WERE real, TestRun.releaseSortKey and TestExecution.releaseSortKey
        // among them: §151 built that sort key and nothing in the product has ever read it back.
        // Eight genuine findings needing eight decisions was a slice and not a tail, so §187
        // recorded the numbers instead of laundering them through a by-decision map here, and §188
        // took it. Both corpora are src/main now and all eight are answered at their fields.
        //
        // At table granularity none of that arises. An entity is written if ANY of its fields is
        // assigned in src/main or its table is named by an INSERT there, so a @Version column
        // carries no weight and the answer is one bit per table.
        Map<Path, String> mainWrites = new LinkedHashMap<>();
        try (Stream<Path> sources = Files.walk(MAIN_SOURCE)) {
            for (Path file : sources.filter(p -> p.toString().endsWith(".java")).toList()) {
                mainWrites.put(file, writesInLiterals(
                        COMMENT.matcher(Files.readString(file)).replaceAll(" ")));
            }
        } catch (IOException unreadable) {
            throw new AssertionError("src/main is this rule's corpus", unreadable);
        }
        // The MIGRATIONS are main sources too, and leaving them out cost four false members on the
        // first run: `permission` and `role` are seeded by V3, `configuration` and `doc_space` by
        // the seed functions V1 declares. A migration that fills a table is a writer of it — V44 is
        // the worked example the column rule already cites — and at table granularity that is even
        // more clearly true, because "the product can put a row here" is exactly what a seed does.
        try (Stream<Path> sources = Files.walk(Path.of("src", "main", "resources"))) {
            for (Path file : sources.filter(p -> p.toString().endsWith(".sql")).toList()) {
                mainWrites.put(file, writesIn(
                        SQL_COMMENT.matcher(Files.readString(file)).replaceAll(" ")));
            }
        } catch (IOException unreadable) {
            throw new AssertionError("the migrations are this rule's corpus too", unreadable);
        }

        assertThat(mainWrites)
                .as("the main source corpus is empty or tiny, so every table below would read as "
                        + "unwritable — the working directory or the source layout has moved")
                .hasSizeGreaterThan(300);

        Map<String, String> unwritable = new TreeMap<>();
        int examined = 0;

        for (JavaClass type : PRODUCTION) {
            if (!type.isAnnotatedWith(Entity.class)) {
                continue;
            }
            examined++;
            String table = tableOf(type);
            boolean writable = namedIn(mainWrites, null, table);
            if (!writable) {
                for (JavaField field : type.getFields()) {
                    if (field.getModifiers().contains(JavaModifier.STATIC)) {
                        continue;
                    }
                    // A field assigned from outside the entity, or by a mutator something calls —
                    // isWrittenInJava's rule, which is what makes a factory count and a dead
                    // setter not. ArchUnit's class corpus is PRODUCTION, so a fixture's assignment
                    // is not in it and cannot make a table look writable.
                    if (isWrittenInJava(field)) {
                        writable = true;
                        break;
                    }
                }
            }
            if (!writable) {
                unwritable.put(type.getSimpleName(), table);
            }
        }

        assertThat(examined)
                .as("no entity was examined, so this rule passes vacuously — the @Entity predicate "
                        + "or the import has moved")
                .isGreaterThan(50);

        assertThat(unwritable.keySet())
                .as("""
                        nothing in src/main can put a row in this table, so it is empty in every \
                        deployment and full in the test database — which is the one arrangement in \
                        which a green suite is evidence FOR the behaviour nobody can perform. \
                        case_condition_optout was that for thirty-one sections, with a reader, a \
                        filter and four files of prose describing rows no request could create. \
                        Give it a writer, drop the table in a migration, or add it to \
                        UNWRITABLE_BY_DECISION with the argument written at the entity""")
                .containsExactlyInAnyOrderElementsOf(UNWRITABLE_BY_DECISION.keySet());
    }

    /** An entity's table name — the {@code @Table} annotation, or the class's own simple name. */
    private static String tableOf(JavaClass type) {
        return type.tryGetAnnotationOfType(Table.class)
                .map(Table::name)
                .filter(name -> !name.isEmpty())
                .orElseGet(type::getSimpleName);
    }

    @Test
    @DisplayName("no entity maps lock_version without @Version — §74's sweep, computed")
    void everyLockVersionColumnIsAVersion() {
        // The rule the comment in everyMappedColumnHasAReader said did not exist yet, and the
        // reason it is a separate rule rather than a tighter corpus there: the corpus is TEXT and
        // has no idea which table a statement is about, so a native query naming another table's
        // lock_version marks every entity's live. This question is not about readers at all. It is
        // whether a column that exists to be a guard is one.
        //
        // §74 asked it BY HAND. It found three entities mapping lock_version without @Version,
        // annotated three, and left two — and the note about the two lived in a javadoc on a THIRD
        // entity, TestPlan, from §74 to §155. That is eighty slices of a written finding nothing
        // could act on, which is §152's shape exactly: the prose about a defect outliving every
        // opportunity to fix it, because prose is not a check.
        //
        // WHAT IT COSTS IS THE POINT. A mapped lock_version Hibernate does not maintain reads 0 on
        // every row for ever, and it is worse than an absent column rather than merely useless: it
        // is the value an If-Match is checked against. TestPlan is the recorded instance — the
        // column had been mapped since M2 slice 2 and REQUIRED by the contract just as long, so the
        // endpoint would have shipped a concurrency guard structurally incapable of firing, and no
        // test asserting "a stale header is refused" would have gone red, because there is no such
        // thing as a stale 0.
        //
        // THE RULE DOES NOT SAY "ANNOTATE IT". It says the two states must not be confused, and
        // unmapping is the other way out — which is the one §155 took for both survivors, because
        // ApiToken's rubric (is a lost update here a discarded edit, or a duplicated intent?)
        // answers "neither" for a row whose only writes name an absolute target. The arguments are
        // at TestRun and WorkItem. What must not happen again is a third entity sitting in between.
        //
        // No by-decision map, unlike §153's and §154's, and that is measured rather than hoped: the
        // population is empty on this tree, so pinning one would be pinning nothing. The moment it
        // is not, the entry belongs at the field like every other one here.
        List<String> mappedWithoutVersion = new ArrayList<>();
        int versioned = 0;

        for (JavaClass type : PRODUCTION) {
            if (!type.isAnnotatedWith(Entity.class)) {
                continue;
            }
            for (JavaField field : type.getFields()) {
                if (field.getModifiers().contains(JavaModifier.STATIC)
                        || !columnOf(field).equals("lock_version")) {
                    continue;
                }
                if (field.isAnnotatedWith(Version.class)) {
                    versioned++;
                } else {
                    mappedWithoutVersion.add(type.getSimpleName() + "." + field.getName());
                }
            }
        }

        // Fifteen on this tree, and the number is worth stating because §154 recorded it by hand as
        // "nineteen other entities carry @Version on the identically-named column". It was 17
        // mappings, 15 of them annotated. A hand-kept tally being wrong in the slice that argued
        // for computing hand-kept tallies is the joke this family of rules keeps telling.
        assertThat(versioned)
                .as("no entity maps lock_version at all, so this rule is asserting nothing — the "
                        + "@Entity predicate, the import or the column convention has moved")
                .isGreaterThan(10);

        assertThat(mappedWithoutVersion)
                .as("""
                        an entity maps lock_version and Hibernate does not maintain it, so the \
                        column reads 0 on every row for ever while looking like a guard. That is \
                        worse than not mapping it: it is the value an If-Match is compared \
                        against, and a precondition against a constant cannot fail — TestPlan \
                        shipped within a slice of exactly that. Two ways out, and the annotation \
                        is only one of them: add @Version, or unmap the field and write at the \
                        class why this row does not need one (ApiToken, TestRun and WorkItem are \
                        the worked examples). What is not allowed is the third state, which §74 \
                        left two entities in for eighty slices""")
                .isEmpty();
    }

    /**
     * Whether Java writes this field's value.
     *
     * <p>{@link #isReadInJava}'s mirror, with the same one-level caller check and one asymmetry
     * that matters: a JPA field is private, so almost every write is <em>inside</em> the entity —
     * a factory, a constructor or a mutator. The qualifying question is therefore whether the
     * method doing the assigning is itself called by anything. A setter nobody calls is not a
     * writer, which is the whole shape §116, §117 and §132 each found by hand.
     */
    private static boolean isWrittenInJava(JavaField field) {
        for (JavaFieldAccess access : field.getAccessesToSelf()) {
            if (access.getAccessType() != JavaFieldAccess.AccessType.SET) {
                continue;
            }
            JavaCodeUnit origin = access.getOrigin();
            if (!origin.getOwner().equals(field.getOwner()) || !origin.getAccessesToSelf().isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /** Every {@code INSERT} and {@code UPDATE} statement in a piece of SQL, and nothing else. */
    private static String writesIn(String text) {
        return WRITE_STATEMENT.matcher(text).results()
                .map(MatchResult::group)
                .collect(Collectors.joining(" "));
    }

    /**
     * The same, over a Java compilation unit — <strong>one literal at a time, which is the whole
     * point of the method existing.</strong>
     *
     * <p>A SQL statement lives inside one string literal, and {@link #WRITE_STATEMENT} runs to the
     * next {@code ;} because that is where a statement ends. Joining the literals first and
     * matching once — the first version — made those two facts contradict each other: a literal
     * with no terminator (<code>"UPDATE key_sequence SET next_value = 1"</code> in a test fixture)
     * swallowed every later literal in the file, and {@code case_health.unlinked} read as written
     * by an {@code @DisplayName}. Over-matching in the writer direction hides findings, unlike the
     * reader rule where it only costs a missed one, so the bound is the literal.
     */
    private static String writesInLiterals(String reading) {
        return STRING_LITERAL.matcher(reading).results()
                .map(m -> m.group(1) != null ? m.group(1) : m.group(2))
                .map(ArchitectureRulesTest::writesIn)
                .filter(writes -> !writes.isEmpty())
                .collect(Collectors.joining(" ; "));
    }

    /** Whether this field's column is spelled anywhere in {@code corpus} but its own file. */
    private static boolean namedIn(Map<Path, String> corpus, Path own, String token) {
        if (token.isEmpty()) {
            return false;
        }
        Pattern word = Pattern.compile("\\b" + Pattern.quote(token) + "\\b");
        return corpus.entrySet().stream()
                .anyMatch(entry -> !entry.getKey().equals(own) && word.matcher(entry.getValue()).find());
    }

    /**
     * Whether Java reads this field's value.
     *
     * <p>A read from outside the declaring entity counts outright. A read from inside it counts
     * only when the reading method itself has a caller — otherwise {@code getFoo()} reading
     * {@code foo} would make every field on every entity look live, which is the answer this whole
     * rule exists not to give.
     */
    private static boolean isReadInJava(JavaField field) {
        for (JavaFieldAccess access : field.getAccessesToSelf()) {
            if (access.getAccessType() != JavaFieldAccess.AccessType.GET) {
                continue;
            }
            JavaCodeUnit origin = access.getOrigin();
            if (!origin.getOwner().equals(field.getOwner()) || !origin.getAccessesToSelf().isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /** The column a field maps to, or its name when the mapping leaves it to the naming strategy. */
    private static String columnOf(JavaField field) {
        if (field.isAnnotatedWith(Column.class)) {
            return field.getAnnotationOfType(Column.class).name();
        }
        if (field.isAnnotatedWith(JoinColumn.class)) {
            return field.getAnnotationOfType(JoinColumn.class).name();
        }
        return field.getName();
    }

    /**
     * The same source with every {@code INSERT} statement blanked out.
     *
     * <p>Applied before <em>both</em> corpora rather than only to the literal one, which is the
     * bug the first version had: an insert's column list is a write whichever corpus reads it, and
     * leaving it in the column corpus marked seven columns live off the fixture rows that create
     * them. A row inserted naming {@code added_by} is not a reader of {@code added_by}.
     */
    private static String withoutWrites(String code) {
        return STRING_LITERAL.matcher(code).replaceAll(match -> INSERT.matcher(match.group()).find()
                ? " "
                : Matcher.quoteReplacement(match.group()));
    }

    /** Every string literal left in a compilation unit — where JPQL and native SQL live. */
    private static String literalsOf(String reading) {
        return STRING_LITERAL.matcher(reading).results()
                .map(m -> m.group(1) != null ? m.group(1) : m.group(2))
                .collect(Collectors.joining(" "));
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
    @DisplayName("integration is a leaf — a notification channel is nobody else's business")
    void nothingDependsOnIntegration() {
        // The claim in integration/package-info.java, made mechanical — the same shape as the two
        // rules above and for a third reason.
        //
        // Modulith already forbids the edge implicitly: `integration` is not on
        // ArchitectureRules.FOUNDATION_MODULES, so declarationsNameOnlyTheFoundationModules would
        // catch a module DECLARING it. What that rule cannot catch is the composition root, or a
        // module quietly widening its own declaration in the same commit as the import — and the
        // temptation is specific and plausible: `execution` wants to tell a channel that a run
        // finished, and calling NotificationDispatchService directly is one line shorter than
        // recording an outbox event.
        //
        // It is also the line that would undo the feature's one durability property. A direct call
        // puts a POST to somebody else's server inside the transaction that closed the run, which
        // is the failure V1's comment on the outbox table names in so many words: "a failing Slack
        // webhook must never roll back a test result".
        ArchRule rule = noClasses()
                .that().resideInAPackage(ROOT_PACKAGE + "..")
                .and().resideOutsideOfPackage("com.tcms.integration..")
                .and().resideOutsideOfPackage(ROOT_PACKAGE)
                .should().dependOnClassesThat().resideInAPackage("com.tcms.integration..")
                .because("""
                        integration is a leaf: it holds a domain concept with a lifecycle, an owner \
                        and a permission, so it is not a foundation module and nothing may depend \
                        on it. A module that wants a notification records an outbox event — which \
                        is also what keeps a third party's webhook out of the transaction that did \
                        the work""");

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
