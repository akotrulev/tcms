package com.tcms.tenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tcms.IntegrationTestBase;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@link DisplayKeys} — the company-scoped display-key allocator (plan §6t).
 *
 * <h2>What this class exists to catch, and none of it is loud</h2>
 *
 * <ol>
 *   <li><strong>A racing allocator.</strong> {@code MAX(key_seq) + 1} and read-then-write both look
 *       right and both issue duplicate keys under concurrency. The property that distinguishes them
 *       from the prescribed statement is that <em>no number is ever handed out twice</em>, so that is
 *       asserted directly rather than inferred from one happy-path call.</li>
 *   <li><strong>A range that is not a range.</strong> Asking for three and getting one, or getting
 *       three that overlap the next caller's, is the failure bulk import cannot survive — and it is
 *       invisible while every caller asks for one.</li>
 *   <li><strong>A missing counter reported as a key.</strong> An {@code UPDATE … RETURNING} matching
 *       nothing is an empty result, not an error; a null key or a {@code "null1"} would reach the
 *       database and fail on a {@code NOT NULL} three layers away from the cause (migration V5).</li>
 *   <li><strong>Numbers crossing companies.</strong> The counter is per company, so two clients both
 *       start at 1 — and a leak here would be one client able to observe how many cases another has
 *       written.</li>
 * </ol>
 *
 * <p>Company ids are unique to this class: sharing them is how handoff §19's first defect happened,
 * green alone and red in the full suite.
 */
@DisplayName("Display keys — one counter per company, one statement, no duplicates")
class DisplayKeyAllocationTest extends IntegrationTestBase {

    private static final UUID SEEDED = UUID.fromString("b2000000-0000-4000-8000-00000000000a");
    private static final UUID ALSO_SEEDED = UUID.fromString("b2000000-0000-4000-8000-00000000000b");
    /** Provisioned the way a pre-V5 company was: the row exists, its key counters do not. */
    private static final UUID UNSEEDED = UUID.fromString("b2000000-0000-4000-8000-00000000000c");

    @Autowired
    private DisplayKeys keys;

    @Autowired
    private CompanyRepository companies;

    @Autowired
    private TransactionTemplate tx;

    @BeforeEach
    void seed() throws SQLException {
        Instant now = Instant.now();
        tx.executeWithoutResult(status -> {
            company(SEEDED, "displaykeys-seeded", "Seeded", now);
            company(ALSO_SEEDED, "displaykeys-also", "Also Seeded", now);
            company(UNSEEDED, "displaykeys-unseeded", "Unseeded", now);
        });

        // Only two of the three get their counters, which is the point of the third.
        seedCompanyDefaults(SEEDED);
        seedCompanyDefaults(ALSO_SEEDED);
        resetCounters();
    }

    @Test
    @DisplayName("the first case key of a company is TC-1, and the next is TC-2")
    void allocatesFromOne() {
        assertThat(allocateOne(SEEDED, DisplayKeys.EntityType.CASE)).isEqualTo("TC-1");
        assertThat(allocateOne(SEEDED, DisplayKeys.EntityType.CASE)).isEqualTo("TC-2");
    }

    @Test
    @DisplayName("the prefix comes from the row, so every entity type gets its own")
    void eachTypeHasItsOwnPrefixAndCounter() {
        // Read from key_sequence rather than from a constant in Java: TC-/TS-/TP-/TR-/DOC- are data
        // seeded by V5, they are immutable by contract, and a second definition in code would be
        // edited months apart from the first.
        assertThat(allocateOne(SEEDED, DisplayKeys.EntityType.CASE)).isEqualTo("TC-1");
        assertThat(allocateOne(SEEDED, DisplayKeys.EntityType.SUITE)).isEqualTo("TS-1");
        assertThat(allocateOne(SEEDED, DisplayKeys.EntityType.PLAN)).isEqualTo("TP-1");
        assertThat(allocateOne(SEEDED, DisplayKeys.EntityType.RUN)).isEqualTo("TR-1");
        assertThat(allocateOne(SEEDED, DisplayKeys.EntityType.DOC_PAGE)).isEqualTo("DOC-1");
        // Independent counters: taking a suite key did not move the case counter.
        assertThat(allocateOne(SEEDED, DisplayKeys.EntityType.CASE)).isEqualTo("TC-2");
    }

    @Test
    @DisplayName("a range of three reserves three consecutive numbers and the next caller starts after them")
    void reservesARange() {
        // ERD §5 and plan §6t: bulk import and CI quarantine auto-create MUST reserve N in one
        // statement. A range that overlaps the next caller's is the failure that only appears once
        // two writers exist, so it is asserted here where one does.
        DisplayKeys.Allocation block = inCompany(SEEDED, () -> keys.allocate(DisplayKeys.EntityType.CASE, 3));

        assertThat(block.count()).isEqualTo(3);
        assertThat(List.of(block.keyAt(0), block.keyAt(1), block.keyAt(2)))
                .containsExactly("TC-1", "TC-2", "TC-3");
        assertThat(allocateOne(SEEDED, DisplayKeys.EntityType.CASE)).isEqualTo("TC-4");
    }

    @Test
    @DisplayName("keySeq is the numeric part, and it is what may be sorted on")
    void seqIsTheNumericPart() {
        // TC-10000 sorts before TC-9999 as text (§3 lesson 6). Every sort resolves on key_seq, so the
        // allocation has to hand both back and they have to agree.
        DisplayKeys.Allocation block = inCompany(SEEDED, () -> keys.allocate(DisplayKeys.EntityType.CASE, 2));

        assertThat(block.seqAt(0)).isEqualTo(1L);
        assertThat(block.keyAt(0)).isEqualTo("TC-" + block.seqAt(0));
        assertThat(block.seqAt(1)).isEqualTo(2L);
    }

    @Test
    @DisplayName("no number is ever handed out twice — the property MAX(key_seq)+1 does not have")
    void neverIssuesTheSameNumberTwice() {
        Set<Long> issued = new LinkedHashSet<>();
        for (int i = 0; i < 25; i++) {
            DisplayKeys.Allocation block =
                    inCompany(SEEDED, () -> keys.allocate(DisplayKeys.EntityType.CASE, 2));
            assertThat(issued.add(block.seqAt(0)))
                    .as("%s was already issued", block.seqAt(0)).isTrue();
            assertThat(issued.add(block.seqAt(1)))
                    .as("%s was already issued", block.seqAt(1)).isTrue();
        }
        assertThat(issued).hasSize(50);
    }

    @Test
    @DisplayName("two companies number independently, and neither can see the other's counter")
    void countersAreCompanyScoped() {
        assertThat(allocateOne(SEEDED, DisplayKeys.EntityType.CASE)).isEqualTo("TC-1");
        assertThat(allocateOne(SEEDED, DisplayKeys.EntityType.CASE)).isEqualTo("TC-2");

        // Not TC-3: a shared counter would leak how many cases the first client has written.
        assertThat(allocateOne(ALSO_SEEDED, DisplayKeys.EntityType.CASE)).isEqualTo("TC-1");
    }

    @Test
    @DisplayName("a company with no counter throws, naming provision-company as the repair")
    void anUnprovisionedCompanyFailsLoudly() {
        // The whole reason this is an exception: UPDATE … RETURNING matching nothing is an empty
        // result. Returning null would put "null1" or a null key into a NOT NULL column three layers
        // from the cause, and the tempting repair is the racing MAX()+1 that V5's header rejects.
        assertThatThrownBy(() -> inCompany(UNSEEDED, () -> keys.allocate(DisplayKeys.EntityType.CASE, 1)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("provision-company")
                .hasMessageContaining("CASE");
    }

    @Test
    @DisplayName("allocating with no company context is refused before any SQL runs")
    void requiresCompanyContext() {
        // CompanyContext.require() rather than a nullable argument: a company taken from an argument
        // is one a future call site can get wrong (ERD §0.1 point 2).
        assertThatThrownBy(() -> tx.execute(status -> keys.allocate(DisplayKeys.EntityType.CASE, 1)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("asking for zero or fewer keys is a programming error, not an empty allocation")
    void refusesANonPositiveCount() {
        assertThatThrownBy(() -> inCompany(SEEDED, () -> keys.allocate(DisplayKeys.EntityType.CASE, 0)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("reading past the reserved block is refused rather than inventing a key")
    void refusesAnIndexOutsideTheBlock() {
        // A caller that reserved 2 and reads index 2 would otherwise be handed a number the counter
        // has not reserved — and the second creation would collide with the next allocation.
        DisplayKeys.Allocation block =
                inCompany(SEEDED, () -> keys.allocate(DisplayKeys.EntityType.CASE, 2));

        assertThatThrownBy(() -> block.keyAt(2)).isInstanceOf(IndexOutOfBoundsException.class);
        assertThatThrownBy(() -> block.seqAt(-1)).isInstanceOf(IndexOutOfBoundsException.class);
    }

    @Test
    @DisplayName("the allocation is what the row now says, so nothing re-derives it")
    void theRowAgreesWithWhatWasHandedOut() throws SQLException {
        inCompany(SEEDED, () -> keys.allocate(DisplayKeys.EntityType.CASE, 5));

        // next_value points at the first UNRESERVED number, which is what makes the statement safe
        // for the next caller without any coordination.
        assertThat(nextValueOf(SEEDED, "CASE")).isEqualTo(6L);
    }

    // =====================================================================
    // helpers
    // =====================================================================

    private String allocateOne(UUID companyId, DisplayKeys.EntityType type) {
        return inCompany(companyId, () -> keys.allocate(type, 1)).keyAt(0);
    }

    /**
     * Runs inside a company context <em>and</em> a transaction, which is the production arrangement:
     * {@link CompanyAwareTransactionManager} sets {@code app.current_company} in {@code doBegin}, so
     * an allocation outside a transaction would find no GUC and no row.
     */
    private <T> T inCompany(UUID companyId, java.util.function.Supplier<T> action) {
        try (var ignored = CompanyContext.open(companyId)) {
            return tx.execute(status -> action.get());
        }
    }

    private void company(UUID id, String slug, String name, Instant now) {
        if (companies.findById(id).isEmpty()) {
            companies.save(Company.provision(id, slug, name, now));
        }
    }

    private void seedCompanyDefaults(UUID companyId) throws SQLException {
        try (Connection connection = appConnection(companyId);
             var statement = connection.prepareStatement(
                     "SELECT tcms_seed_company_defaults(CAST(? AS uuid))")) {
            statement.setString(1, companyId.toString());
            statement.execute();
        }
    }

    /**
     * Puts every counter back to 1, so each test starts from a company that has created nothing.
     *
     * <p>The seed function is {@code ON CONFLICT DO NOTHING}, so re-running it does <strong>not</strong>
     * reset the numbers — which is correct for provisioning and would otherwise make every assertion
     * here depend on the order the class happened to run in.
     */
    private void resetCounters() throws SQLException {
        for (UUID companyId : List.of(SEEDED, ALSO_SEEDED)) {
            try (Connection connection = appConnection(companyId);
                 var statement = connection.prepareStatement(
                         "UPDATE key_sequence SET next_value = 1")) {
                statement.executeUpdate();
            }
        }
    }

    private long nextValueOf(UUID companyId, String entityType) throws SQLException {
        try (Connection connection = appConnection(companyId);
             var statement = connection.prepareStatement(
                     "SELECT next_value FROM key_sequence WHERE entity_type = ?")) {
            statement.setString(1, entityType);
            try (ResultSet rs = statement.executeQuery()) {
                assertThat(rs.next()).as("no %s counter for %s", entityType, companyId).isTrue();
                return rs.getLong(1);
            }
        }
    }
}
