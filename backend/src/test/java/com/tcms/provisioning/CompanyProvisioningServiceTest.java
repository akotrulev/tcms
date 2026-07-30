package com.tcms.provisioning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tcms.IntegrationTestBase;
import com.tcms.identity.AppUserRepository;
import com.tcms.identity.CompanyMembership;
import com.tcms.identity.CompanyMembershipRepository;
import com.tcms.identity.MembershipStatus;
import com.tcms.identity.RoleAssignment;
import com.tcms.identity.RoleAssignmentRepository;
import com.tcms.identity.RoleRepository;
import com.tcms.tenancy.Company;
import com.tcms.tenancy.CompanyContext;
import com.tcms.tenancy.CompanyRepository;
import com.tcms.tenancy.CompanyStatus;
import com.tcms.tenancy.InstanceAuditEvent;
import com.tcms.tenancy.InstanceAuditEventRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * {@code provision-company} — the last M0 deliverable, and the only path by which a
 * client company comes into existence.
 *
 * <p>What is actually at stake here is not "a row was inserted". It is that a provisioned
 * company is <strong>complete</strong>: it can classify a case, allocate a display key,
 * record an execution status, and it has exactly one administrator who holds company-wide
 * capability and has not yet been activated on their behalf. Each of those is a separate
 * subsystem that fails much later, and in its own vocabulary, if provisioning leaves it
 * out — which is why they are asserted here rather than trusted.
 *
 * <p>Per the async-harness lesson (handoff §14): every test class shares one container
 * and therefore one database, so this class owns the {@code prov-} slug namespace and
 * creates its companies through the service rather than seeding fixed ids. It must not
 * assume it is alone.
 */
@DisplayName("provision-company — a complete, idempotent company")
class CompanyProvisioningServiceTest extends IntegrationTestBase {

    @Autowired
    private CompanyProvisioningService provisioning;

    @Autowired
    private CompanyRepository companies;

    @Autowired
    private AppUserRepository users;

    @Autowired
    private CompanyMembershipRepository memberships;

    @Autowired
    private RoleRepository roles;

    @Autowired
    private RoleAssignmentRepository roleAssignments;

    @Autowired
    private InstanceAuditEventRepository auditEvents;

    @Nested
    @DisplayName("A fresh company")
    class Fresh {

        @Test
        @DisplayName("exists, is ACTIVE, and keeps the slug it was given")
        void createsTheCompanyRow() {
            var result = provisioning.provision(request("prov-fresh"));

            assertThat(result.companyCreated()).isTrue();
            Company company = companies.findById(result.companyId()).orElseThrow();
            assertThat(company.getSlug()).isEqualTo("prov-fresh");
            assertThat(company.getDisplayName()).isEqualTo("Prov Fresh Ltd");
            assertThat(company.getStatus()).isEqualTo(CompanyStatus.ACTIVE);
        }

        @Test
        @DisplayName("gets its OWN COPY of the three company-level vocabularies")
        void seedsClassificationVocabularies() throws SQLException {
            var result = provisioning.provision(request("prov-vocab"));

            assertThat(dimensionKeys(result.companyId()))
                    .containsExactlyInAnyOrder("test_type", "test_level", "execution_phase");

            // Type and Level are separate axes (plan §6d) — 8 + 7 + 5 values.
            assertThat(classificationValueCount(result.companyId())).isEqualTo(20);
        }

        @Test
        @DisplayName("can allocate its first display key — the counters exist and start at 1")
        void seedsKeySequences() throws SQLException {
            var result = provisioning.provision(request("prov-keys"));

            assertThat(keyPrefixes(result.companyId()))
                    .containsOnly(
                            Map.entry("CASE", "TC-"),
                            Map.entry("SUITE", "TS-"),
                            Map.entry("PLAN", "TP-"),
                            Map.entry("RUN", "TR-"),
                            Map.entry("DOC_PAGE", "DOC-"));

            // The property V5 exists for, exercised through the ERD's own allocator
            // rather than by reading next_value: an UPDATE ... RETURNING against a
            // missing counter is not an error, it is an empty result — which is exactly
            // why the gap was invisible until something tried to allocate.
            assertThat(allocateKeys(result.companyId(), "CASE", 1)).isEqualTo(1L);
            assertThat(allocateKeys(result.companyId(), "CASE", 500)).isEqualTo(2L);
            assertThat(allocateKeys(result.companyId(), "CASE", 1)).isEqualTo(502L);
        }

        @Test
        @DisplayName("has one administrator, INVITED — provisioning cannot activate them")
        void invitesTheFirstCompanyAdmin() {
            var result = provisioning.provision(request("prov-admin"));

            assertThat(result.adminUserCreated()).isTrue();
            assertThat(result.membershipCreated()).isTrue();

            try (var ignored = CompanyContext.open(result.companyId())) {
                CompanyMembership membership = memberships
                        .findByCompanyIdAndUserId(result.companyId(), result.adminUserId())
                        .orElseThrow();
                // INVITED, not ACTIVE. An operator who could activate a client's
                // administrator on their behalf is an audit finding, not a convenience.
                assertThat(membership.getStatus()).isEqualTo(MembershipStatus.INVITED);
                assertThat(membership.getAcceptedAt()).isNull();
                // The per-membership display name (§6u), not the canonical one.
                assertThat(membership.getDisplayName()).isEqualTo("Ops Person");
            }
        }

        @Test
        @DisplayName("grants Company Admin at COMPANY scope, so every future project is covered")
        void grantsCompanyAdminAtCompanyScope() {
            var result = provisioning.provision(request("prov-grant"));

            assertThat(result.adminGrantCreated()).isTrue();
            UUID companyAdminRole =
                    roles.findByName(CompanyProvisioningService.COMPANY_ADMIN_ROLE).orElseThrow().getId();

            try (var ignored = CompanyContext.open(result.companyId())) {
                List<RoleAssignment> grants = roleAssignments.findByUserId(result.adminUserId());
                assertThat(grants).singleElement().satisfies(grant -> {
                    assertThat(grant.getRoleId()).isEqualTo(companyAdminRole);
                    // projectId == null IS the COMPANY scope (see RoleAssignment). A
                    // per-project grant here would silently exclude every project the
                    // client creates afterwards.
                    assertThat(grant.getProjectId()).isNull();
                    assertThat(grant.isCompanyScoped()).isTrue();
                    assertThat(grant.getCompanyId()).isEqualTo(result.companyId());
                });
            }
        }

        @Test
        @DisplayName("is recorded in the instance operator trail")
        void writesAnInstanceAuditEvent() {
            var result = provisioning.provision(request("prov-audit"));

            List<InstanceAuditEvent> events =
                    auditEvents.findByCompanyIdOrderByOccurredAtDesc(result.companyId());
            assertThat(events).singleElement().satisfies(event -> {
                assertThat(event.getAction()).isEqualTo(CompanyProvisioningService.AUDIT_ACTION);
                assertThat(event.getTargetType()).isEqualTo("company");
                // Parsed, not substring-matched: the column is jsonb, and Postgres
                // normalises whitespace and reorders keys on the way in — an assertion on
                // the serialized text would be asserting Postgres's storage format.
                var changes = readChanges(event);
                assertThat(changes.get("slug").asText()).isEqualTo("prov-audit");
                assertThat(changes.get("companyCreated").asBoolean()).isTrue();
                assertThat(changes.get("adminEmail").asText()).isEqualTo("ops@prov-audit.test");
                // Unattended CLI run: no actor is invented. A synthetic "system" user
                // would be indistinguishable from a real operator in the trail.
                assertThat(event.getActorUserId()).isNull();
            });
        }

        @Test
        @DisplayName("leaves no company context behind on the calling thread")
        void doesNotLeakCompanyContext() {
            assertThat(CompanyContext.current()).isEmpty();
            provisioning.provision(request("prov-noleak"));
            assertThat(CompanyContext.current()).isEmpty();
        }
    }

    @Nested
    @DisplayName("The first project, when one is asked for")
    class FirstProject {

        @Test
        @DisplayName("is created and seeded with statuses, the Feature dimension and a Default configuration")
        void seedsProjectDefaults() throws SQLException {
            var result = provisioning.provision(ProvisionCompanyRequest.withFirstProject(
                    "prov-project", "Prov Project Ltd", "first-project@prov.test", "Ops Person",
                    "OLB", "Prov Online Banking"));

            assertThat(result.projectCreated()).isTrue();
            UUID projectId = result.projectId().orElseThrow();

            assertThat(caseStatusNames(result.companyId(), projectId))
                    .containsExactlyInAnyOrder(
                            "Passed", "Failed", "Blocked", "Skipped", "Retest", "Untested");

            // Feature is the one PROJECT-scoped dimension, and it is seeded EMPTY —
            // inventing a client's feature taxonomy is how a controlled vocabulary gets
            // worked around.
            assertThat(projectDimensionKeys(result.companyId(), projectId)).containsExactly("feature");

            // test_run.configuration_id is NOT NULL all the way through the read models,
            // so a project without a default configuration cannot record a run at all.
            assertThat(defaultConfigurationCount(result.companyId(), projectId)).isEqualTo(1);
        }

        @Test
        @DisplayName("is optional — a company without one is complete, not half-provisioned")
        void isOptional() {
            var result = provisioning.provision(request("prov-noproject"));

            assertThat(result.projectId()).isEmpty();
            assertThat(result.projectCreated()).isFalse();
            assertThat(result.companyCreated()).isTrue();
        }
    }

    @Nested
    @DisplayName("Re-running")
    class Idempotence {

        @Test
        @DisplayName("creates nothing a second time and says so")
        void isIdempotent() throws SQLException {
            var first = provisioning.provision(request("prov-rerun"));
            var second = provisioning.provision(request("prov-rerun"));

            assertThat(second.companyId()).isEqualTo(first.companyId());
            assertThat(second.adminUserId()).isEqualTo(first.adminUserId());
            assertThat(second.wasNoOp()).isTrue();
            assertThat(second.companyCreated()).isFalse();
            assertThat(second.adminUserCreated()).isFalse();
            assertThat(second.membershipCreated()).isFalse();
            assertThat(second.adminGrantCreated()).isFalse();

            // The parts that a "create if absent" bug would duplicate rather than skip.
            assertThat(classificationValueCount(first.companyId())).isEqualTo(20);
            assertThat(keyPrefixes(first.companyId())).hasSize(5);
            try (var ignored = CompanyContext.open(first.companyId())) {
                // A second identical grant reads in an admin screen as though the
                // operator had granted twice.
                assertThat(roleAssignments.findByUserId(first.adminUserId())).hasSize(1);
            }
        }

        @Test
        @DisplayName("records every run in the trail, because an operator did run it")
        void auditsEveryRun() {
            provisioning.provision(request("prov-audit-twice"));
            var second = provisioning.provision(request("prov-audit-twice"));

            List<InstanceAuditEvent> events =
                    auditEvents.findByCompanyIdOrderByOccurredAtDesc(second.companyId());
            assertThat(events).hasSize(2);
            assertThat(readChanges(events.getFirst()).get("noOp").asBoolean())
                    .as("the second run changed nothing, and the trail must say so")
                    .isTrue();
        }

        @Test
        @DisplayName("never renames: a differing display name does not overwrite the client's own")
        void doesNotOverwrite() {
            var first = provisioning.provision(request("prov-rename"));

            provisioning.provision(ProvisionCompanyRequest.of(
                    "prov-rename", "A Name Somebody Mistyped", "ops@prov-rename.test", "Ops Person"));

            assertThat(companies.findById(first.companyId()).orElseThrow().getDisplayName())
                    .isEqualTo("Prov Rename Ltd");
        }
    }

    @Nested
    @DisplayName("Across companies")
    class MultiCompany {

        @Test
        @DisplayName("one person, one account, a membership in each — the instance-wide email rule")
        void reusesAnExistingAccount() {
            String shared = "consultant@prov.test";
            var acme = provisioning.provision(ProvisionCompanyRequest.of(
                    "prov-multi-a", "Prov Multi A", shared, "A. Consultant"));
            var globex = provisioning.provision(ProvisionCompanyRequest.of(
                    "prov-multi-b", "Prov Multi B", shared, "Consultant (Vendor)"));

            assertThat(globex.adminUserCreated()).isFalse();
            assertThat(globex.adminUserId()).isEqualTo(acme.adminUserId());
            assertThat(users.findByEmail(shared)).isPresent();

            // Per-membership display names (§6u): the same person, named differently in
            // each client, and neither client can see or overwrite the other's.
            try (var ignored = CompanyContext.open(acme.companyId())) {
                assertThat(memberships.findByCompanyIdAndUserId(acme.companyId(), acme.adminUserId())
                        .orElseThrow().getDisplayName()).isEqualTo("A. Consultant");
            }
            try (var ignored = CompanyContext.open(globex.companyId())) {
                assertThat(memberships.findByCompanyIdAndUserId(globex.companyId(), globex.adminUserId())
                        .orElseThrow().getDisplayName()).isEqualTo("Consultant (Vendor)");
            }
        }

        @Test
        @DisplayName("vocabularies are COPIED, not shared — and one client cannot see the other's")
        void seedsAreCopiedPerCompany() throws SQLException {
            var a = provisioning.provision(ProvisionCompanyRequest.of(
                    "prov-copy-a", "Prov Copy A", "copy-a@prov.test", "Ops Person"));
            var b = provisioning.provision(ProvisionCompanyRequest.of(
                    "prov-copy-b", "Prov Copy B", "copy-b@prov.test", "Ops Person"));

            // Distinct rows, so B renaming "Compatibility" cannot rename it for A.
            assertThat(classificationValueIds(a.companyId()))
                    .hasSize(20)
                    .doesNotContainAnyElementsOf(classificationValueIds(b.companyId()));

            // And RLS agrees: A's context sees exactly A's rows.
            try (var connection = appConnection(a.companyId());
                    var statement = connection.prepareStatement(
                            "SELECT count(*) FROM classification_value WHERE company_id = ?")) {
                statement.setObject(1, b.companyId());
                try (ResultSet rows = statement.executeQuery()) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getLong(1)).isZero();
                }
            }
        }
    }

    @Nested
    @DisplayName("Refusing bad input before a transaction opens")
    class Validation {

        @Test
        @DisplayName("a slug that would not survive a URL")
        void rejectsMalformedSlug() {
            assertThatThrownBy(() -> ProvisionCompanyRequest.of(
                    "Prov Acme!", "Prov Acme", "ops@prov.test", "Ops Person"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("slug");
        }

        @Test
        @DisplayName("a project key outside the contract's own pattern")
        void rejectsMalformedProjectKey() {
            assertThatThrownBy(() -> ProvisionCompanyRequest.withFirstProject(
                    "prov-bad-key", "Prov", "ops@prov.test", "Ops Person", "olb", "Online Banking"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("project key");
        }

        @Test
        @DisplayName("a blank required argument")
        void rejectsBlankName() {
            assertThatThrownBy(() -> ProvisionCompanyRequest.of("prov-blank", "  ", "ops@prov.test", "Ops"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("display name");
        }
    }

    private static com.fasterxml.jackson.databind.JsonNode readChanges(InstanceAuditEvent event) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readTree(event.getChanges());
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new AssertionError("audit changes is not valid JSON: " + event.getChanges(), e);
        }
    }

    private static ProvisionCompanyRequest request(String slug) {
        String displayName = java.util.Arrays.stream(slug.split("-"))
                .map(part -> Character.toUpperCase(part.charAt(0)) + part.substring(1))
                .reduce((a, b) -> a + " " + b)
                .orElseThrow() + " Ltd";
        // A distinct address per company: the instance-wide email rule means a shared one
        // would make every company after the first report adminUserCreated=false, which is
        // the multi-company case and has its own test rather than being every test's default.
        return ProvisionCompanyRequest.of(slug, displayName, "ops@" + slug + ".test", "Ops Person");
    }

    // --- Raw reads, as the application role with the company active. Raw rather than
    // through entities because most of these tables have no entity yet, and because
    // reading them under RLS is itself part of what is being asserted. ---

    private static List<String> dimensionKeys(UUID companyId) throws SQLException {
        return strings(companyId,
                "SELECT key FROM classification_dimension WHERE project_id IS NULL ORDER BY position");
    }

    private static List<String> projectDimensionKeys(UUID companyId, UUID projectId) throws SQLException {
        return strings(companyId,
                "SELECT key FROM classification_dimension WHERE project_id = '" + projectId + "'");
    }

    private static List<String> caseStatusNames(UUID companyId, UUID projectId) throws SQLException {
        return strings(companyId,
                "SELECT name FROM case_status WHERE project_id = '" + projectId + "'");
    }

    private static long classificationValueCount(UUID companyId) throws SQLException {
        return count(companyId, "SELECT count(*) FROM classification_value");
    }

    private static long defaultConfigurationCount(UUID companyId, UUID projectId) throws SQLException {
        return count(companyId, "SELECT count(*) FROM configuration WHERE project_id = '"
                + projectId + "' AND is_default");
    }

    private static List<String> classificationValueIds(UUID companyId) throws SQLException {
        return strings(companyId, "SELECT id::text FROM classification_value");
    }

    private static List<Map.Entry<String, String>> keyPrefixes(UUID companyId) throws SQLException {
        List<Map.Entry<String, String>> prefixes = new ArrayList<>();
        try (var connection = appConnection(companyId);
                var statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(
                        "SELECT entity_type, prefix, next_value FROM key_sequence")) {
            while (rows.next()) {
                assertThat(rows.getLong("next_value"))
                        .as("a fresh counter must start at 1 — display keys are 1-based (§6t)")
                        .isEqualTo(1L);
                prefixes.add(Map.entry(rows.getString("entity_type"), rows.getString("prefix")));
            }
        }
        return prefixes;
    }

    /** The ERD's allocator, verbatim: reserve {@code count} keys in one statement. */
    private static long allocateKeys(UUID companyId, String entityType, int count) throws SQLException {
        try (var connection = appConnection(companyId);
                var statement = connection.prepareStatement("""
                        UPDATE key_sequence SET next_value = next_value + ?
                         WHERE company_id = ? AND entity_type = ?
                        RETURNING next_value - ?
                        """)) {
            statement.setInt(1, count);
            statement.setObject(2, companyId);
            statement.setString(3, entityType);
            statement.setInt(4, count);
            try (ResultSet rows = statement.executeQuery()) {
                assertThat(rows.next())
                        .as("the counter row must exist — an empty result here is the V5 defect")
                        .isTrue();
                return rows.getLong(1);
            }
        }
    }

    private static List<String> strings(UUID companyId, String sql) throws SQLException {
        List<String> values = new ArrayList<>();
        try (var connection = appConnection(companyId);
                var statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(sql)) {
            while (rows.next()) {
                values.add(rows.getString(1));
            }
        }
        return values;
    }

    private static long count(UUID companyId, String sql) throws SQLException {
        try (var connection = appConnection(companyId);
                var statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(sql)) {
            assertThat(rows.next()).isTrue();
            return rows.getLong(1);
        }
    }
}
