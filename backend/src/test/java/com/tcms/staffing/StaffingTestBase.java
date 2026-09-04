package com.tcms.staffing;

import static org.assertj.core.api.Assertions.assertThat;

import com.tcms.project.GateResult;
import com.tcms.tenancy.CompanyContext;
import com.tcms.tracker.TrackerEndpointTestBase;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * The fixture plumbing the four {@code staffing} test classes share, and nothing else — no ids, no
 * company slugs, no project keys.
 *
 * <p>That division is {@code TrackerEndpointTestBase}'s and is kept for its reason: a slug shared
 * with another class is green alone and red in the suite, and the failure names neither class. Every
 * id stays in the subclass that owns it, so one prefix greps to one namespace.
 *
 * <h2>The COMPANY-scope grant, which {@code TrackerEndpointTestBase} has no shape for</h2>
 *
 * <p>Three of this module's five keys are COMPANY scope, and a company-wide grant is a
 * {@code role_assignment} row with a <strong>null</strong> {@code project_id}. Its project-scope
 * sibling is not a substitute and the difference is the whole of 017's scope split: a key held on
 * one project must not read as held company-wide, which is the "held anywhere in this company"
 * shortcut the roles spec records rejecting.
 */
public abstract class StaffingTestBase extends TrackerEndpointTestBase {

    /** Project Manager — the PROJECT-scope pair, and no company-wide staffing key at all. */
    protected static final UUID PROJECT_MANAGER_ROLE =
            UUID.fromString("10000000-0000-4000-8000-000000000009");

    /** Account Manager — the three COMPANY-scope keys, and no project visibility. */
    protected static final UUID ACCOUNT_MANAGER_ROLE =
            UUID.fromString("10000000-0000-4000-8000-000000000010");

    /**
     * A COMPANY-scope grant, written through the app connection with {@code ON CONFLICT DO NOTHING}
     * and read back.
     *
     * <p>Both halves are {@code TrackerEndpointTestBase.grantOnProject}'s and for its reasons: the
     * insert is idempotent in the database rather than behind a read (this runs from
     * {@code @BeforeEach}), and the read-back exists because RLS <em>hides</em> a row written with
     * the wrong company context rather than erroring — which would surface as a 403 from every test
     * in the class and read as a gate defect.
     */
    protected void grantInCompany(UUID id, UUID companyId, UUID roleId, UUID userId)
            throws SQLException {

        try (Connection connection = appConnection(companyId);
                PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO role_assignment
                            (id, company_id, role_id, user_id, project_id, granted_at)
                        VALUES (CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid),
                                CAST(? AS uuid), NULL, now())
                        ON CONFLICT (id) DO NOTHING
                        """)) {
            statement.setObject(1, id);
            statement.setObject(2, companyId);
            statement.setObject(3, roleId);
            statement.setObject(4, userId);
            statement.executeUpdate();
        }

        try (Connection connection = appConnection(companyId);
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT count(*) FROM role_assignment "
                                + "WHERE id = CAST(? AS uuid) AND project_id IS NULL")) {
            statement.setObject(1, id);
            try (var rows = statement.executeQuery()) {
                rows.next();
                if (rows.getLong(1) != 1) {
                    throw new AssertionError("the company-wide grant " + id + " was not written "
                            + "for user " + userId + " in company " + companyId);
                }
            }
        }
    }

    /**
     * Runs a service call with the company context open, as the request filter does.
     *
     * <p>Every read and write here starts with {@code CompanyContext.require()}, and a call without
     * it throws rather than reading another company's rows — which is the fail-closed behaviour the
     * tenancy layer is for, and is why this helper exists instead of each test remembering.
     */
    protected <T> T scoped(UUID companyId, Supplier<T> call) {
        try (var ignored = CompanyContext.open(companyId)) {
            return call.get();
        }
    }

    /** The gated value, with the gate asserted rather than cast past. */
    protected static <T> T allowed(GateResult<T> gate) {
        assertThat(gate).isInstanceOf(GateResult.Allowed.class);
        return ((GateResult.Allowed<T>) gate).value();
    }

    /**
     * Empties this module's five tables, in FK order.
     *
     * <p>Per test rather than per class: every method here creates allocations or absences, and a
     * suite that let them accumulate would make one method's capacity figure depend on which methods
     * ran before it — which is the one property a capacity assertion cannot survive.
     */
    protected void wipeStaffing(UUID companyId) throws SQLException {
        try (Connection connection = appConnection(companyId)) {
            for (String table :
                    List.of("absence", "person_skill", "allocation", "person_profile", "skill")) {
                try (PreparedStatement statement = connection.prepareStatement(
                        "DELETE FROM " + table + " WHERE company_id = CAST(? AS uuid)")) {
                    statement.setObject(1, companyId);
                    statement.executeUpdate();
                }
            }
        }
    }
}
