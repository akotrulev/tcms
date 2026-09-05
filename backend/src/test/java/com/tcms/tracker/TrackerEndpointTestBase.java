package com.tcms.tracker;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tcms.IntegrationTestBase;
import com.tcms.auth.SessionTokens;
import com.tcms.identity.AppUser;
import com.tcms.identity.AppUserRepository;
import com.tcms.identity.CompanyMembership;
import com.tcms.identity.CompanyMembershipRepository;
import com.tcms.project.Project;
import com.tcms.project.ProjectRepository;
import com.tcms.tenancy.Company;
import com.tcms.tenancy.CompanyContext;
import com.tcms.tenancy.CompanyRepository;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The fixture plumbing the three {@code tracker} endpoint classes share, and <strong>nothing
 * else</strong> — no ids, no company slugs, no project keys.
 *
 * <p>That division is deliberate rather than tidy. Those three things are exactly what
 * {@code BoardEndpointTest} records losing eleven tests to: a slug shared with another class is
 * green alone and red in the suite, and the failure names neither class. Ids stay in the subclass
 * that owns them so a reader can grep one prefix and see the whole namespace it is taking.
 *
 * <p>What is here is the part that genuinely is identical: creating a company, an active user, a
 * membership, a project grant, a project and its seeded defaults — the last through
 * {@code tcms_seed_project_defaults}, which is what gives a project its six statuses, its six issue
 * types and the moves between them. Seeding those by hand in a test would be a second definition of
 * a project's workflow, and the one that drifts is whichever the migration does not.
 */
public abstract class TrackerEndpointTestBase extends IntegrationTestBase {

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected ObjectMapper json;

    @Autowired
    protected TransactionTemplate tx;

    @Autowired
    private SessionTokens tokens;

    @Autowired
    private CompanyRepository companies;

    @Autowired
    private ProjectRepository projects;

    @Autowired
    private AppUserRepository users;

    @Autowired
    private CompanyMembershipRepository memberships;

    protected String tokenFor(UUID userId, UUID companyId) {
        return tx.execute(status -> tokens.issue(userId, companyId, 1, false).token());
    }

    protected void company(UUID id, String slug, String name, Instant now) {
        if (companies.findById(id).isEmpty()) {
            companies.save(Company.provision(id, slug, name, now));
        }
    }

    protected void activeUser(UUID id, String email, String canonicalName, Instant now) {
        if (users.findById(id).isEmpty()) {
            AppUser user = AppUser.invite(id, email, canonicalName, now);
            user.activate("hash", now);
            users.save(user);
        }
    }

    protected void member(UUID companyId, UUID userId, String displayName, Instant now) {
        if (memberships.findByCompanyIdAndUserId(companyId, userId).isEmpty()) {
            CompanyMembership membership =
                    CompanyMembership.invite(companyId, userId, displayName, null, now);
            membership.accept(now);
            memberships.save(membership);
        }
    }

    /**
     * A PROJECT-scope grant, written through the app connection with {@code ON CONFLICT DO NOTHING}.
     *
     * <p><strong>Not {@code findById().isEmpty()} first, and that is the bug this shape fixes.</strong>
     * This runs from {@code @BeforeEach}, so it re-seeds before every test method, and the read that
     * was supposed to make it idempotent came back empty for a row that was there — green in
     * isolation, and seventeen {@code role_assignment_pkey} violations the moment the class ran
     * inside the full suite beside every other class that touches that table. The database already
     * knows whether the row exists; asking it is one statement and cannot disagree with itself.
     *
     * @param id the assignment's own id, from this class's UUID prefix. An id derived from a
     *     marker string is a second namespace nothing coordinates — this way the whole namespace a
     *     class takes is greppable in one place, which is what {@code BoardEndpointTest} lost eleven
     *     tests to.
     */
    protected void grantOnProject(UUID id, UUID companyId, UUID roleId, UUID userId, UUID projectId)
            throws SQLException {

        try (Connection connection = appConnection(companyId);
                PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO role_assignment
                            (id, company_id, role_id, user_id, project_id, granted_at)
                        VALUES (CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid),
                                CAST(? AS uuid), CAST(? AS uuid), now())
                        ON CONFLICT (id) DO NOTHING
                        """)) {
            statement.setObject(1, id);
            statement.setObject(2, companyId);
            statement.setObject(3, roleId);
            statement.setObject(4, userId);
            statement.setObject(5, projectId);
            statement.executeUpdate();
        }

        // A fixture that silently seeds nothing is worse than one that fails: the symptom is a 404
        // from every endpoint in the class, which reads as a gate defect rather than as a missing
        // row. RLS refuses the row rather than erroring if the company context is wrong, so the
        // only honest check is to read it back through the same connection.
        try (Connection connection = appConnection(companyId);
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT count(*) FROM role_assignment WHERE id = CAST(? AS uuid)")) {
            statement.setObject(1, id);
            try (var rows = statement.executeQuery()) {
                rows.next();
                if (rows.getLong(1) != 1) {
                    throw new AssertionError("the grant " + id + " was not written for user "
                            + userId + " on project " + projectId + " in company " + companyId);
                }
            }
        }
    }

    /**
     * A COMPANY-scope grant, written through the app connection with {@code ON CONFLICT DO NOTHING}
     * and read back.
     *
     * <p>~~Lived on {@code StaffingTestBase}~~ → <strong>moved here 2026-09-05 (018 T3)</strong>,
     * when {@code crm} became the second module needing it. It is not staffing's shape, it is the
     * company-wide half of {@link #grantOnProject}, and a second copy is exactly what these base
     * classes exist to prevent.
     *
     * <p>Both halves are {@link #grantOnProject}'s and for its reasons: the
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

    protected void seedProject(UUID id, UUID companyId, String key, String name, Instant now) {
        if (projects.findScopedById(id).isEmpty()) {
            projects.save(Project.create(id, companyId, key, name, null, now));
        }
    }

    protected void inCompany(UUID companyId, Runnable work) {
        try (var ignored = CompanyContext.open(companyId)) {
            work.run();
        }
    }

    /**
     * The project's workflow, its types and its counter, through the one function that defines them.
     *
     * <p>Called as the app role with the company GUC set, exactly as {@code ProjectCreateService}
     * does — the function raises SQLSTATE 42501 unless {@code app.current_company} matches, which is
     * what turns a context bug in a fixture into a loud failure rather than rows the policy hides.
     */
    protected void seedProjectDefaults(UUID companyId, UUID projectId) throws SQLException {
        if (hasWorkflow(companyId, projectId)) {
            /*
              Called from @BeforeEach, so it runs once per test METHOD, and the function is
              idempotent only against its own names: a fixture that renames "Backlog" leaves the
              function unable to see that the status is there, so the next call inserts a second one
              at position 0 and the class fails on a unique constraint rather than on anything it
              is about. Asking whether the project already has a workflow is the guard the function
              cannot supply for us.
            */
            return;
        }
        try (Connection connection = appConnection(companyId);
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT tcms_seed_project_defaults(CAST(? AS uuid), CAST(? AS uuid))")) {
            statement.setObject(1, companyId);
            statement.setObject(2, projectId);
            statement.execute();
        }
    }

    private boolean hasWorkflow(UUID companyId, UUID projectId) throws SQLException {
        try (Connection connection = appConnection(companyId);
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT 1 FROM issue_status WHERE project_id = CAST(? AS uuid) LIMIT 1")) {
            statement.setObject(1, projectId);
            try (var rows = statement.executeQuery()) {
                return rows.next();
            }
        }
    }

    /**
     * Empties the issue tables this class writes to, in FK order.
     *
     * <p>Per test rather than per class: every method here creates issues, and a suite that let them
     * accumulate would make one method's list assertion depend on which methods ran before it.
     */
    protected void clearIssues(List<UUID> companyIds) throws SQLException {
        for (UUID companyId : companyIds) {
            try (Connection connection = appConnection(companyId)) {
                for (String table : List.of(
                        "issue_doc_link", "issue_watcher", "issue_link", "issue_comment",
                        "issue_fact")) {
                    try (PreparedStatement statement =
                            connection.prepareStatement("DELETE FROM " + table)) {
                        statement.executeUpdate();
                    }
                }
                /*
                  Leaves first, three times, because issue.parent_id is ON DELETE RESTRICT and the
                  constraint fires per row rather than per statement — one `DELETE FROM issue` over
                  an epic and its stories refuses itself. Three passes is the depth the rule bounds
                  the tree at, so a loop here would be a loop over something that cannot be deeper.
                */
                for (int pass = 0; pass < 3; pass++) {
                    try (PreparedStatement statement = connection.prepareStatement("""
                            DELETE FROM issue i
                             WHERE NOT EXISTS (SELECT 1 FROM issue c WHERE c.parent_id = i.id)
                            """)) {
                        statement.executeUpdate();
                    }
                }
            }
        }
    }
}
