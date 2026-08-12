package com.tcms.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * {@link CaseHealth#signals()} — the severity beside each signal, and the ORDER.
 *
 * <h2>This class exists because §179 deleted the only check on both facts</h2>
 *
 * <p>Until §179 the severity of a signal was not on the wire for a chip, so the SPA carried a
 * hand-written {@code SEVERE_SIGNALS} list and {@code HealthChip.test.tsx} was — in its own words —
 * "nothing but this test holds the two sides together". It held one of the two sides: the list had
 * drifted from {@link HealthSignal} about two of the ten members by the time §178 read it. Putting
 * severity on the wire deletes the list, and deleting the list deletes the frontend test that pinned
 * the {@code STALE_AUTOMATION} / {@code UNDECLARED_AUTOMATION} asymmetry. <strong>A slice that moves
 * a decision to the side that owns it and leaves its only test behind is §153's finding</strong>
 * ({@code ProvisionCompanyCommand.isRequestedBy}: six assertions aimed at the dead copy while the
 * live guard had none), so the assertion arrives here in the same commit that removes it there.
 *
 * <h2>No database and no context</h2>
 *
 * <p>{@link CaseHealth} is an entity, but the two properties under test are a {@code switch}, a
 * comparator and a table of constants — {@code CaseHealthAttributesTest}'s call, for its reason. The
 * row is built by reflection over the very attribute names {@link HealthSignal} publishes, so a
 * constant whose attribute stopped resolving fails here too rather than silently raising nothing.
 */
@DisplayName("Raised signals — worst first, each carrying the severity the server decided")
class RaisedSignalTest {

    /**
     * Every signal's severity, written out rather than read off the enum.
     *
     * <p>A test that asked {@link HealthSignal#severity()} what the severity is would assert that
     * the enum equals itself. The point of the table is that a change to any member has to be typed
     * twice — once as a product decision and once here — because <em>each of these is an argued
     * decision with a paragraph behind it</em>: the automation pair is deliberately asymmetric
     * (ERROR for the results that stopped arriving, WARNING for the ones arriving uncounted, because
     * only one of them can hide a failure), {@code CONDITION_DRIFT} is ERROR where its neighbour
     * {@code STALE_APPROVAL} is WARNING, and {@code UNLINKED} is INFO because it fires on most of a
     * young project and a WARNING on 156 cases takes the two ERRORs down with it.
     */
    private static final Map<HealthSignal, HealthSignal.Severity> DECIDED =
            new EnumMap<>(Map.of(
                    HealthSignal.OUTDATED_SPEC_CHANGED, HealthSignal.Severity.ERROR,
                    HealthSignal.CONDITION_DRIFT, HealthSignal.Severity.ERROR,
                    HealthSignal.STALE_AUTOMATION, HealthSignal.Severity.ERROR,
                    HealthSignal.STALE_APPROVAL, HealthSignal.Severity.WARNING,
                    HealthSignal.NEVER_EXECUTED, HealthSignal.Severity.WARNING,
                    HealthSignal.LONG_UNEXECUTED, HealthSignal.Severity.WARNING,
                    HealthSignal.FAILING_REPEATEDLY, HealthSignal.Severity.WARNING,
                    HealthSignal.UNDECLARED_AUTOMATION, HealthSignal.Severity.WARNING,
                    HealthSignal.UNLINKED, HealthSignal.Severity.INFO,
                    HealthSignal.ORPHANED, HealthSignal.Severity.INFO));

    @Nested
    @DisplayName("Severity — the decision, and where it is allowed to live")
    class Severities {

        @Test
        @DisplayName("every signal carries the severity that was decided for it")
        void everySignalCarriesItsDecidedSeverity() {
            assertThat(DECIDED)
                    .as("a signal with no entry here is a severity nobody decided; the map and the "
                            + "enum are the same set both ways")
                    .containsOnlyKeys(HealthSignal.values());

            DECIDED.forEach((signal, severity) -> assertThat(signal.severity())
                    .as("%s reads as %s on every chip, tile and facet — changing it is a product "
                            + "decision, and this line is where it is recorded", signal, severity)
                    .isEqualTo(severity));
        }

        @Test
        @DisplayName("the automation pair is asymmetric, which is the whole point of the pair")
        void theAutomationPairStaysAsymmetric() {
            // Moved from HealthChip.test.tsx in §179, which held it while SEVERE_SIGNALS was the
            // chip's source. The two signals are mirrors and read as a matched set, so the tidy
            // change is to give them a matched severity — and that erases the only distinction
            // between them a reader acts on. Stale automation is a result the team believes it is
            // getting and is not: a red test can stay invisible for as long as it lasts. Undeclared
            // automation is a result arriving that no coverage number counts, which understates a
            // percentage and costs nobody a missed defect.
            assertThat(HealthSignal.STALE_AUTOMATION.severity())
                    .isEqualTo(HealthSignal.Severity.ERROR);
            assertThat(HealthSignal.UNDECLARED_AUTOMATION.severity())
                    .isEqualTo(HealthSignal.Severity.WARNING);
        }

        @Test
        @DisplayName("Severity is declared worst first, which is what the sort relies on")
        void severityIsDeclaredWorstFirst() {
            // CaseHealth.signals() and DashboardService.health both sort by this enum, and a
            // Comparator over an enum orders by ORDINAL. Alphabetising these four constants — the
            // tidiest edit there is — silently inverts both orders and nothing else fails: the
            // grid's chip becomes the least important signal again and the dashboard's panel puts
            // "Unlinked 156" above two broken cases.
            assertThat(HealthSignal.Severity.values())
                    .containsExactly(
                            HealthSignal.Severity.ERROR,
                            HealthSignal.Severity.WARNING,
                            HealthSignal.Severity.INFO,
                            HealthSignal.Severity.NEUTRAL);
        }
    }

    @Nested
    @DisplayName("Order — worst first, because the grid renders the first one")
    class Order {

        @Test
        @DisplayName("a broken case is not rendered below a missing link")
        void theWorstSignalComesFirst() {
            // THE REGRESSION, in the two signals that produce it. CaseGrid renders
            // healthSignals[0] as the row's chip and the rest as "+n", and in the enum's
            // declaration order UNLINKED comes first — so from §178, when UNLINKED gained a writer
            // and started firing on most cases, a case whose automation had stopped reporting
            // showed "Not linked to any spec" and hid the failure behind a +1.
            List<HealthSignal> raised =
                    signalsOf(HealthSignal.UNLINKED, HealthSignal.STALE_AUTOMATION);

            assertThat(raised)
                    .containsExactly(HealthSignal.STALE_AUTOMATION, HealthSignal.UNLINKED);
        }

        @Test
        @DisplayName("all three severities sort worst first")
        void everySeveritySorts() {
            List<HealthSignal> raised = signalsOf(
                    HealthSignal.UNLINKED,             // INFO
                    HealthSignal.NEVER_EXECUTED,       // WARNING
                    HealthSignal.CONDITION_DRIFT);     // ERROR

            assertThat(raised).containsExactly(
                    HealthSignal.CONDITION_DRIFT,
                    HealthSignal.NEVER_EXECUTED,
                    HealthSignal.UNLINKED);
        }

        @Test
        @DisplayName("signals of equal severity keep the enum's declaration order")
        void tiesKeepDeclarationOrder() {
            // List.sort is stable and that is load-bearing rather than incidental: with eight of
            // the ten sharing two severities, an unstable sort would make the row's one visible
            // chip vary between two requests over the same unchanged case.
            assertThat(signalsOf(HealthSignal.STALE_AUTOMATION, HealthSignal.OUTDATED_SPEC_CHANGED))
                    .containsExactly(
                            HealthSignal.OUTDATED_SPEC_CHANGED, HealthSignal.STALE_AUTOMATION);
            assertThat(signalsOf(HealthSignal.FAILING_REPEATEDLY, HealthSignal.STALE_APPROVAL))
                    .containsExactly(
                            HealthSignal.STALE_APPROVAL, HealthSignal.FAILING_REPEATEDLY);
        }

        @Test
        @DisplayName("each entry carries the severity of the signal beside it")
        void severityTravelsWithItsOwnSignal() {
            // The failure this pairing exists to make impossible is an off-by-one: two lists
            // assembled separately and zipped, so every chip renders the previous signal's colour.
            List<RaisedSignalView> raised = raise(HealthSignal.values()).signals();

            assertThat(raised).hasSize(HealthSignal.values().length);
            assertThat(raised).allSatisfy(entry ->
                    assertThat(entry.severity()).isEqualTo(DECIDED.get(entry.signal())));
        }

        @Test
        @DisplayName("a row with nothing raised has no entries")
        void nothingRaisedIsAnEmptyList() {
            assertThat(raise().signals()).isEmpty();
        }
    }

    /** The signals a row raising exactly these asserts, in the order it asserts them. */
    private static List<HealthSignal> signalsOf(HealthSignal... raised) {
        return raise(raised).signals().stream().map(RaisedSignalView::signal).toList();
    }

    /**
     * A {@link CaseHealth} raising exactly the given signals.
     *
     * <p>Written through {@link HealthSignal#attribute()} rather than through field names spelled
     * here, so this fixture cannot drift from the enum: a constant whose attribute no longer
     * resolves fails this class as well as {@code CaseHealthAttributesTest}.
     */
    private static CaseHealth raise(HealthSignal... raised) {
        CaseHealth row = new CaseHealth();
        List<String> attributes =
                Arrays.stream(raised).map(HealthSignal::attribute).toList();
        List<String> unset = new ArrayList<>(attributes);
        for (Field field : CaseHealth.class.getDeclaredFields()) {
            if (attributes.contains(field.getName())) {
                field.setAccessible(true);
                try {
                    field.setBoolean(row, true);
                } catch (IllegalAccessException unreachable) {
                    throw new IllegalStateException(unreachable);
                }
                unset.remove(field.getName());
            }
        }
        assertThat(unset)
                .as("a signal's attribute names no field on CaseHealth, so this fixture raised "
                        + "nothing and every assertion below would pass vacuously")
                .isEmpty();
        return row;
    }
}
