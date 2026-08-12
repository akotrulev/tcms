package com.tcms.contract;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The contract's named enums against the server's — the third contract-versus-server rule, after
 * {@code ContractParameterBindingTest}'s two.
 *
 * <h2>The check already existed for one enum of twenty, and had since M0</h2>
 *
 * <p>{@code SeedDataTest.catalogueMatchesTheContract} compares {@code PermissionKey}'s 55
 * members against the rows {@code V3} seeds, both ways, and its reason is written out there: a
 * generated union is <em>closed</em>, so a value the server can produce and the contract does not
 * declare is a value no client can name, and a value the contract declares and nothing produces is
 * a screen nobody can reach. That argument is not about permissions. It is about every enum on the
 * wire, and for nineteen of the twenty nothing checked it — the one that had a check is the one
 * whose members live in a table, which is to say the one where somebody had already been bitten.
 *
 * <p>§161 found what that costs. {@code ArchitectureRulesTest.READERLESS_BY_DECISION}'s
 * <em>reference</em> entry — the one its javadoc holds the other eleven to — argued that
 * {@code case_health.incomplete} stays unmapped-to-a-signal because "the contract's enum has no
 * such member, and inventing one would put a value on the wire the SPA cannot label". The
 * contract's enum has had {@code INCOMPLETE} since 2026-07-30, one day <em>before</em> the sentence
 * was written, and {@code lib/labels.ts} has labelled it just as long. The claim was never true,
 * and §154 promoted it to the shape twelve other arguments are copied from.
 *
 * <h2>The two directions are not equally dangerous, and the rule reports both</h2>
 *
 * <p><strong>Server-only is the defect.</strong> A constant Jackson can serialize that the contract
 * does not declare reaches a client as a string outside its union: TypeScript does not check it at
 * runtime, so it lands in a {@code satisfies Record<Union, string>} lookup as {@code undefined} and
 * renders as a blank cell or a crash, depending on the consumer. Nothing on either side throws.
 *
 * <p><strong>Contract-only is a promise, and usually a deliberate one.</strong> A client can carry
 * a label for a value it never receives; the cost is a dead branch, not a wrong screen. Both are
 * worth naming, because which one an enum is in tells the next author what closing it costs — and
 * getting that backwards is the whole of §161's finding. {@code incomplete}'s entry said the
 * repair was a contract change. The contract was already paid for; what is missing is a server
 * constant and a decision about what "incomplete" means.
 *
 * <p>No Spring context and no database, like {@link ContractConventionTest}: one YAML parse and one
 * bytecode import.
 */
@DisplayName("Contract enums — the wire's closed sets, checked against the server's")
class ContractEnumTest {

    /**
     * The application root package. Spelled here rather than imported from
     * {@code architecture.ArchitectureRules}, which is package-private on purpose — its two facts
     * are the architecture tests', and this is not one.
     */
    private static final String ROOT_PACKAGE = "com.tcms";

    /**
     * Which server enum each named contract enum corresponds to.
     *
     * <p><strong>This map is the correspondence itself, and before §161 it was written down
     * nowhere.</strong> It is not an ignore list: the rule below asserts it covers the contract's
     * enum schemas <em>exactly</em>, so a new named enum fails the build until somebody says which
     * type serves it — and deleting a server enum that is still on the wire fails too.
     *
     * <p>Four names have two owners, and that is a property of a modulith rather than a mistake:
     * {@code Priority}, {@code StepFormat} and {@code AggregationPolicy} are each copied across a
     * module boundary because a feature module may not import another's types, so the contract's
     * one schema is served by two enums that have to stay identical to each other <em>and</em> to
     * it. That is exactly the drift a hand-read cannot catch, and listing both copies is what makes
     * the rule catch it.
     */
    private static final Map<String, List<String>> OWNERS = Map.ofEntries(
            Map.entry("ReviewSubjectType", List.of("com.tcms.project.ReviewSubjectType")),
            Map.entry("ReviewState", List.of("com.tcms.project.ReviewState")),
            // The one name that does not match: the server's is nested in the entity that holds it,
            // and the request side deliberately accepts a narrower set (LGTM, CHANGES_REQUESTED)
            // which the contract states as its own inline enum on ReviewParticipationInput.
            Map.entry("ReviewVerdict", List.of("com.tcms.project.ReviewParticipation$Verdict")),
            Map.entry("EditAfterApproval", List.of("com.tcms.project.EditAfterApproval")),
            Map.entry("LifecycleStatus", List.of("com.tcms.repository.LifecycleStatus")),
            Map.entry("AutomationStatus", List.of("com.tcms.repository.AutomationStatus")),
            Map.entry("Priority",
                    List.of("com.tcms.repository.Priority", "com.tcms.work.Priority")),
            Map.entry("StepFormat",
                    List.of("com.tcms.repository.StepFormat", "com.tcms.execution.StepFormat")),
            Map.entry("StatusKind", List.of("com.tcms.project.StatusKind")),
            Map.entry("HealthSignal", List.of("com.tcms.repository.HealthSignal")),
            // Nested in the enum whose property it is, like ReviewVerdict above. It was an
            // ANONYMOUS enum on DashboardTile.severity until §179 and therefore in no population
            // this rule can see — §170's inline-shape finding, arriving at an enum. Naming the
            // schema is what put it here.
            Map.entry("HealthSignalSeverity",
                    List.of("com.tcms.repository.HealthSignal$Severity")),
            Map.entry("AggregationPolicy",
                    List.of("com.tcms.reporting.AggregationPolicy",
                            "com.tcms.execution.AggregationPolicy")),
            Map.entry("ProposalStatus", List.of("com.tcms.repository.ProposalStatus")),
            Map.entry("ProposalVerdict", List.of("com.tcms.repository.ProposalVerdict")),
            Map.entry("VerdictSource", List.of("com.tcms.repository.VerdictSource")),
            // The ingestion dialect, NOT reporting.ReportFormat — see HOMONYMS below.
            Map.entry("ReportFormat", List.of("com.tcms.execution.ReportFormat")),
            Map.entry("IngestedOutcome", List.of("com.tcms.execution.IngestedOutcome")),
            Map.entry("IngestionStatus", List.of("com.tcms.execution.IngestionStatus")),
            Map.entry("PermissionQualifier", List.of("com.tcms.identity.PermissionQualifier")),
            Map.entry("WorkItemStatus", List.of("com.tcms.work.WorkItemStatus")));

    /**
     * The contract enums with no server type at all, and what checks each instead.
     *
     * <p>One member, and it is the precedent this whole rule generalizes rather than an exception
     * to it.
     */
    private static final Map<String, String> NO_SERVER_ENUM = Map.of(
            "PermissionKey",
            "the catalogue is DATA, not a type: V3 seeds 55 rows and the code checks permissions by "
                    + "string so that a role screen can render a key nobody compiled against. "
                    + "SeedDataTest.catalogueMatchesTheContract is this rule for it, "
                    + "against the seed instead of against an enum, and it has run since M0");

    /**
     * Production enums whose simple name collides with a contract enum and which are not that
     * enum's server type.
     *
     * <p>One member, and it is worth the line: {@code reporting.ReportFormat} is what a report's
     * bytes ARE (PDF, MD, HTML, CSV, XLSX, JSON) and {@code execution.ReportFormat} is the dialect
     * a CI job POSTED (JUnit, TestNG, Allure, Cucumber). Disjoint sets, one letter of context
     * between them, and the contract's {@code ReportFormat} is the second. A rule matching on
     * simple name alone would have picked whichever the importer returned first and then reported
     * six false divergences or none, depending on the day.
     */
    private static final Map<String, String> HOMONYMS = Map.of(
            "com.tcms.reporting.ReportFormat",
            "what a report's bytes are, not the dialect CI posted. The contract's ReportFormat is "
                    + "execution's; this one reaches the wire as ReportJob.format, which the "
                    + "contract types as a bare string, and as the generate request's own inline "
                    + "enum offering the subset that has a renderer");

    /**
     * Members present on one side and not the other, with the argument for each.
     *
     * <p>Two, and they are in opposite directions — which is the point of recording the direction
     * rather than just the member. A key here is {@code Schema.MEMBER}.
     *
     * <p><strong>Neither value below spells a column name, and that is load-bearing rather than
     * style.</strong> {@code ArchitectureRulesTest.everyMappedColumnHasAReader} counts a whole-word
     * occurrence of a column name in any file's <em>string literals</em> as a reader, with comments
     * stripped first — so an argument for a column being dead, written as a literal here, would
     * quietly mark it alive there and make its {@code READERLESS_BY_DECISION} entry vanish. §154
     * hit exactly this and fixed it by excluding its own file from its own corpus; the shape is
     * general, and the second file to write about a dead column is the one that discovers it. The
     * identifiers belong in this javadoc, where the corpus cannot see them.
     */
    private static final Map<String, String> DIVERGENCE_BY_DECISION = Map.of(
            "HealthSignal.INCOMPLETE",
            "CONTRACT-ONLY, deliberately. The case_health column behind this member has no "
                    + "derivation (ArchitectureRulesTest.WRITERLESS_BY_DECISION) and therefore no "
                    + "server constant, and the contract carries the member so a client's labels "
                    + "stay complete — which lib/labels.ts and StatusChip both act on. §161's "
                    + "finding is that the READERLESS_BY_DECISION entry had this backwards and "
                    + "named the contract as the blocker: closing it is a derivation plus a "
                    + "product decision, and costs no contract change at all. "
                    + "(The column's own name is deliberately not spelled in this literal — see "
                    + "the note above this map.)",
            "PermissionQualifier.NONE",
            "SERVER-ONLY, which is the dangerous direction, and safe here for a reason the code "
                    + "enforces rather than states: NONE means \"not held\", and PermissionSet's "
                    + "constructor THROWS if a key is put in the held map with it. A permission "
                    + "the caller lacks is an absent entry in permissions, never a present one "
                    + "qualified to nothing, so the constant cannot reach a serializer. "
                    + "MeController.HeldPermission says the same from the wire side");

    /**
     * The production class graph — imported for this class and <strong>released when it is
     * done</strong>, which is deliberate rather than tidy.
     *
     * <p>An ArchUnit graph of this codebase is the largest single object a test in this suite
     * allocates, and {@code ArchitectureRulesTest} already holds <em>two</em> in {@code static
     * final} fields, so they live as long as the JVM. A third one held the same way is what took
     * the worker's 512m heap to 522685K of 524288K and hung the build — see the note beside
     * {@code maxHeapSize} in {@code build.gradle.kts} for why that hang reports nothing. The heap is
     * 2g now and this would fit either way; it is still wrong to keep a graph alive for two
     * thousand tests that have no use for it.
     */
    private static JavaClasses production;

    @BeforeAll
    static void importProductionClasses() {
        production = new ClassFileImporter()
                .withImportOption(new ImportOption.DoNotIncludeTests())
                .importPackages(ROOT_PACKAGE);
    }

    @AfterAll
    static void releaseProductionClasses() {
        production = null;
    }

    @Test
    @DisplayName("every named contract enum names the server type that serves it")
    void everyContractEnumHasAnOwner() {
        Set<String> declared = new TreeSet<>(OpenApiContract.load().enumSchemas().keySet());
        Set<String> claimed = new TreeSet<>(OWNERS.keySet());
        claimed.addAll(NO_SERVER_ENUM.keySet());

        assertThat(declared)
                .as("no named enum was found in the contract at all — components.schemas has moved")
                .hasSizeGreaterThan(10);

        assertThat(claimed)
                .as("""
                        The contract's named enums and this file's owner map have to be the same \
                        set, both ways.

                        A schema here and not in OWNERS is a new enum on the wire whose server \
                        type nobody has named — add the entry, or NO_SERVER_ENUM with what checks \
                        it instead.

                        An entry in OWNERS with no schema is an argument that outlived what it was \
                        about, which is the shape §152, §153, §154, §156 and §160 each found in \
                        turn. Delete it.""")
                .containsExactlyInAnyOrderElementsOf(declared);

        List<String> missing = new ArrayList<>();
        OWNERS.forEach((schema, owners) -> owners.stream()
                .filter(owner -> enumNamed(owner).isEmpty())
                .forEach(owner -> missing.add(schema + " -> " + owner)));

        assertThat(missing)
                .as("an owner named here is not a production enum — it has been renamed, moved, or "
                        + "deleted while the contract still declares the schema it served")
                .isEmpty();
    }

    @Test
    @DisplayName("no production enum shares a contract enum's name without being it")
    void everySameNamedProductionEnumIsClaimedOrExplained() {
        Set<String> schemas = OpenApiContract.load().enumSchemas().keySet();
        Set<String> owned = OWNERS.values().stream()
                .flatMap(List::stream)
                .collect(TreeSet::new, Set::add, Set::addAll);

        List<String> unclaimed = new ArrayList<>();
        for (JavaClass type : production) {
            if (!isEnum(type) || !schemas.contains(type.getSimpleName())) {
                continue;
            }
            if (!owned.contains(type.getName()) && !HOMONYMS.containsKey(type.getName())) {
                unclaimed.add(type.getName() + "  (contract enum " + type.getSimpleName() + ")");
            }
        }

        assertThat(unclaimed)
                .as("""
                        A production enum carries the name of a contract enum and is neither listed \
                        as one of its owners nor recorded as a homonym.

                        This is how the copied-across-a-module-boundary enums drift: Priority, \
                        StepFormat and AggregationPolicy each exist twice because a feature module \
                        may not import another's types, and a third copy that nothing lists is a \
                        third copy nothing compares. Add it to that schema's OWNERS entry — or, if \
                        it genuinely means something else, to HOMONYMS with the sentence that \
                        tells the two apart.""")
                .isEmpty();

        List<String> stale = HOMONYMS.keySet().stream()
                .filter(name -> enumNamed(name).isEmpty()
                        || !schemas.contains(enumNamed(name).orElseThrow().getSimpleName()))
                .toList();

        assertThat(stale)
                .as("a HOMONYMS entry no longer describes a name collision — the enum is gone, or "
                        + "the contract stopped declaring the schema it collided with. The "
                        + "argument cannot outlive the ambiguity it resolves")
                .isEmpty();
    }

    @Test
    @DisplayName("a contract enum and its server type hold the same members")
    void contractEnumsAndTheirServerTypesAgree() {
        Map<String, List<String>> schemas = OpenApiContract.load().enumSchemas();
        Map<String, String> divergences = new TreeMap<>();
        int compared = 0;

        for (Map.Entry<String, List<String>> schema : schemas.entrySet()) {
            List<String> owners = OWNERS.get(schema.getKey());
            if (owners == null) {
                continue; // NO_SERVER_ENUM — checked elsewhere, and the other test pins that.
            }
            Set<String> onTheWire = new TreeSet<>(schema.getValue());
            for (String owner : owners) {
                Set<String> constants = constantsOf(enumNamed(owner).orElseThrow());
                compared++;

                onlyIn(constants, onTheWire).forEach(member -> divergences.put(
                        schema.getKey() + "." + member,
                        "SERVER-ONLY — " + owner + " can serialize it and the contract does not "
                                + "declare it, so no generated client has a name for it"));
                onlyIn(onTheWire, constants).forEach(member -> divergences.put(
                        schema.getKey() + "." + member,
                        "CONTRACT-ONLY — the wire promises it and " + owner + " has no constant "
                                + "for it, so nothing can ever send it"));
            }
        }

        assertThat(compared)
                .as("no schema was compared against a server enum, so this rule asserted nothing — "
                        + "the owner map or the import has moved")
                .isGreaterThan(15);

        divergences.keySet().removeIf(DIVERGENCE_BY_DECISION::containsKey);

        assertThat(divergences)
                .as("""
                        A contract enum and the server type serving it hold different members.

                        SERVER-ONLY is the defect. Jackson writes the constant's name; the \
                        generated union does not contain it; TypeScript checks nothing at \
                        runtime, so it arrives as a string outside the union and falls out of \
                        every `satisfies Record<Union, …>` lookup as undefined. Nothing throws \
                        on either side. Declare it in openapi.yaml and regenerate BOTH copies of \
                        schema.d.ts.

                        CONTRACT-ONLY is a promise nothing keeps: a value the SPA has a label and \
                        a branch for and will never receive. Add the server constant, or delete \
                        the member from the contract.

                        DIVERGENCE_BY_DECISION is the third answer and it costs a sentence \
                        somebody has to defend — including which DIRECTION it is, because that is \
                        what tells the next author whether closing it is a contract change or a \
                        server one. §161 exists because an argument got that backwards and was \
                        then copied twelve times.

                        Divergent members:""")
                .isEmpty();

        List<String> healed = DIVERGENCE_BY_DECISION.keySet().stream()
                .filter(key -> !divergedBefore(schemas, key))
                .toList();

        assertThat(healed)
                .as("a DIVERGENCE_BY_DECISION entry describes members that now agree. The "
                        + "divergence was closed and the argument for it was left behind — delete "
                        + "the entry in the same commit that closes the gap (§157, §158, §159 and "
                        + "§160 each did exactly this in the writerless twin)")
                .isEmpty();
    }

    /** Whether {@code Schema.MEMBER} is still a real divergence, in either direction. */
    private static boolean divergedBefore(Map<String, List<String>> schemas, String key) {
        int dot = key.lastIndexOf('.');
        String schema = key.substring(0, dot);
        String member = key.substring(dot + 1);
        boolean onTheWire = schemas.getOrDefault(schema, List.of()).contains(member);
        boolean onTheServer = OWNERS.getOrDefault(schema, List.of()).stream()
                .anyMatch(owner -> enumNamed(owner).map(ContractEnumTest::constantsOf)
                        .orElse(Set.of())
                        .contains(member));
        return onTheWire != onTheServer;
    }

    private static Set<String> onlyIn(Set<String> from, Set<String> other) {
        Set<String> only = new TreeSet<>(from);
        only.removeAll(other);
        return only;
    }

    private static Optional<JavaClass> enumNamed(String fullyQualifiedName) {
        return production.stream()
                .filter(type -> type.getName().equals(fullyQualifiedName))
                .filter(ContractEnumTest::isEnum)
                .findFirst();
    }

    /**
     * An enum, and not one of its constants' anonymous bodies — a constant declared with a body
     * compiles to a synthetic subclass whose superclass is the enum rather than {@link Enum}.
     */
    private static boolean isEnum(JavaClass type) {
        return type.getRawSuperclass()
                .map(superclass -> superclass.getName().equals(Enum.class.getName()))
                .orElse(false);
    }

    /**
     * Its constants — the fields carrying {@code ACC_ENUM}, not merely the static ones of its own
     * type.
     *
     * <p>The distinction is the first thing this rule got wrong, and it got it wrong on its first
     * run: {@code execution.AggregationPolicy} declares
     * {@code public static final AggregationPolicy DEFAULT = LATEST}, an alias for the contract's
     * {@code default: LATEST}, and a "static field whose type is the enum" test reported it as a
     * constant the contract fails to declare. It is not on the wire at all — Jackson writes the
     * name of the constant it points at — so the rule's very first finding would have been a
     * demand to add a member no server can send. Over-counting in the direction that manufactures
     * work, which is the same trap {@code everyMappedColumnHasAWriter} names about {@code WHERE}
     * clauses.
     */
    private static Set<String> constantsOf(JavaClass type) {
        Set<String> constants = new TreeSet<>();
        for (JavaField field : type.getFields()) {
            if (field.getModifiers().contains(JavaModifier.ENUM)
                    && field.getRawType().getName().equals(type.getName())) {
                constants.add(field.getName());
            }
        }
        return constants;
    }
}
