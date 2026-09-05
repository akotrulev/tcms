package com.tcms.crm;

import static org.assertj.core.api.Assertions.assertThat;

import com.tcms.project.GateResult;
import com.tcms.project.ProjectView;
import com.tcms.tenancy.CompanyContext;
import com.tcms.tracker.TrackerEndpointTestBase;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;

/**
 * The client surface, end to end — and the states the module's acceptance criterion is about.
 *
 * <p><strong>UUID prefix {@code fd}, and it was verified against the tree rather than assumed.</strong>
 * The first attempt took {@code fa}, which is PlanStatisticsEndpointTest's — {@code fa001000-0000-4000-8000-000000000001} is
 * literally that class's project id, so both classes passed alone and the pair failed in the suite
 * with a {@code project_pkey} duplicate naming neither of them. That is the exact failure
 * {@code StaffingTestBase}'s header describes, and the check that would have caught it is one
 * command: grep the tree for the prefix before taking it.
 */
@AutoConfigureMockMvc
@DisplayName("Clients — recording one, attaching a project, and the company that has none")
class ClientEndpointTest extends TrackerEndpointTestBase {

    private static final UUID COMPANY_ADMIN_ROLE =
            UUID.fromString("10000000-0000-4000-8000-000000000002");
    private static final UUID PROJECT_MANAGER_ROLE =
            UUID.fromString("10000000-0000-4000-8000-000000000009");

    private static final UUID ACME = UUID.fromString("fd000000-0000-4000-8000-000000000001");
    private static final UUID PROJECT = UUID.fromString("fd001000-0000-4000-8000-000000000001");
    private static final UUID ADMIN = UUID.fromString("fd002000-0000-4000-8000-000000000001");
    private static final UUID PM = UUID.fromString("fd002000-0000-4000-8000-000000000002");
    private static final UUID ADMIN_GRANT = UUID.fromString("fd003000-0000-4000-8000-000000000001");
    private static final UUID PM_GRANT = UUID.fromString("fd003000-0000-4000-8000-000000000002");

    @Autowired
    private ClientService clients;

    @BeforeEach
    void seed() throws SQLException {
        Instant now = Instant.now();
        tx.executeWithoutResult(status -> {
            company(ACME, "crm-acme", "Acme", now);
            activeUser(ADMIN, "admin@crm.test", "Ada Admin", now);
            activeUser(PM, "pm@crm.test", "Pat Manager", now);
        });
        inCompany(ACME, () -> tx.executeWithoutResult(status -> {
            seedProject(PROJECT, ACME, "CRM", "Storefront", now);
            member(ACME, ADMIN, "Ada Admin", now);
            member(ACME, PM, "Pat Manager", now);
        }));
        grantInCompany(ADMIN_GRANT, ACME, COMPANY_ADMIN_ROLE, ADMIN);
        grantOnProject(PM_GRANT, ACME, PROJECT_MANAGER_ROLE, PM, PROJECT);
        seedProjectDefaults(ACME, PROJECT);
    }

    @Test
    @DisplayName("a company with no clients answers an empty list, and that is a complete answer")
    void aCompanyWithNoClientsAnswersAnEmptyList() {
        /*
          The module's acceptance criterion, from the server end. Every client-shaped surface in the
          SPA is driven by this one array being empty — the projects list's filter, the Client field
          on creation and settings, the overview chip — so an endpoint that 403'd, 404'd or errored
          here instead would make six screens render an error where they must render nothing.
        */
        assertThat(allowed(scoped(() -> clients.list(ADMIN, ClientStatus.ACTIVE, null))))
                .isEmpty();
    }

    @Test
    @DisplayName("a name is the only required field — story C1")
    void aNameIsTheOnlyRequiredField() {
        ClientView created = written(scoped(() -> clients.create(
                ADMIN, new ClientWriteRequest("Acme Retail", null, null, null, null, null))));

        assertThat(created.name()).isEqualTo("Acme Retail");
        assertThat(created.status()).isEqualTo(ClientStatus.ACTIVE);
        assertThat(created.location()).isNull();
        assertThat(created.accountManager()).isNull();
        assertThat(created.projectCount()).isZero();
        assertThat(created.contactCount()).isZero();
    }

    @Test
    @DisplayName("a blank name is a field error on `name`, not a constraint violation")
    void aBlankNameIsAFieldError() {
        GateResult<ClientService.Outcome<ClientView>> gate = scoped(() -> clients.create(
                ADMIN, new ClientWriteRequest("   ", null, null, null, null, null)));

        assertThat(valueOf(gate)).isInstanceOf(ClientService.Outcome.Invalid.class);
        assertThat(((ClientService.Outcome.Invalid<ClientView>) valueOf(gate)).errors())
                .containsKey("name");
    }

    @Test
    @DisplayName("a contact is added, and creates no account of any kind")
    void aContactIsAddedAndCreatesNoAccount() {
        ClientView client = written(scoped(() -> clients.create(
                ADMIN, new ClientWriteRequest("Acme Retail", null, null, null, null, null))));

        ClientContactView contact = written(scoped(() -> clients.addContact(
                ADMIN, client.id(),
                new ClientContactWriteRequest("Hanna Lund", "Product owner",
                        "hanna.lund@crm.test", "+46 8 555 0142"))));

        assertThat(contact.name()).isEqualTo("Hanna Lund");
        assertThat(contact.email()).isEqualTo("hanna.lund@crm.test");
        // That it created no login is CrmCreatesNoLoginTest's, asserted over the identity tables —
        // this one only proves the row exists, because a test cannot see an insert it does not make.

        ClientDetailView detail = allowed(scoped(() -> clients.read(ADMIN, client.id())));
        assertThat(detail.contacts()).hasSize(1);
        assertThat(detail.client().contactCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("a project attaches to a client, and detaching is a target state rather than a verb")
    void aProjectAttachesAndDetaches() {
        ClientView client = written(scoped(() -> clients.create(
                ADMIN, new ClientWriteRequest("Acme Retail", null, null, null, null, null))));

        ProjectView attached = written(scoped(() ->
                clients.attachTo(PM, PROJECT, new ProjectClientRequest(client.id()))));
        assertThat(attached.client()).isNotNull();
        assertThat(attached.client().name()).isEqualTo("Acme Retail");

        // Null is a legitimate target state and not a clearing operation — scope D1 rule 1.
        ProjectView detached = written(scoped(() ->
                clients.attachTo(PM, PROJECT, new ProjectClientRequest(null))));
        assertThat(detached.client()).isNull();
    }

    @Test
    @DisplayName("a client id that names nothing this company can see is a 400 on `clientId`")
    void anUnknownClientIsAFieldError() {
        UUID stranger = UUID.fromString("fd009000-0000-4000-8000-000000000001");

        GateResult<ClientService.Outcome<ProjectView>> gate = scoped(() ->
                clients.attachTo(PM, PROJECT, new ProjectClientRequest(stranger)));

        /*
          A 400 and not a 500 from `fk_project_client`, which is on `id` alone and would have
          accepted another company's client because referential integrity is not subject to RLS
          (018 D-l). And not a 404, which would be an oracle for client ids in another tenancy.
        */
        assertThat(valueOf(gate)).isInstanceOf(ClientService.Outcome.Invalid.class);
        assertThat(((ClientService.Outcome.Invalid<ProjectView>) valueOf(gate)).errors())
                .containsKey("clientId");
    }

    @Test
    @DisplayName("018 D-k — a client with projects is refused, and the refusal names archiving")
    void aClientWithProjectsCannotBeDeleted() {
        ClientView client = written(scoped(() -> clients.create(
                ADMIN, new ClientWriteRequest("Acme Retail", null, null, null, null, null))));
        written(scoped(() -> clients.attachTo(PM, PROJECT, new ProjectClientRequest(client.id()))));

        ClientService.Outcome<Void> refused = valueOf(scoped(() -> clients.delete(ADMIN, client.id())));
        assertThat(refused).isInstanceOf(ClientService.Outcome.Conflict.class);
        assertThat(((ClientService.Outcome.Conflict<Void>) refused).detail())
                .as("the refusal has to name the way forward, or it is a dead end")
                .contains("archive");

        // Detached, it deletes — the conflict is about the reference, not about the client.
        written(scoped(() -> clients.attachTo(PM, PROJECT, new ProjectClientRequest(null))));
        assertThat(valueOf(scoped(() -> clients.delete(ADMIN, client.id()))))
                .isInstanceOf(ClientService.Outcome.Removed.class);
    }

    @Test
    @DisplayName("the picker offers active clients, plus the archived one this project still names")
    void thePickerKeepsTheProjectsOwnArchivedClient() {
        ClientView live = written(scoped(() -> clients.create(
                ADMIN, new ClientWriteRequest("Acme Retail", null, null, null, null, null))));
        ClientView retired = written(scoped(() -> clients.create(
                ADMIN, new ClientWriteRequest("Bergstrom AG", null, null, null, null, null))));

        written(scoped(() -> clients.attachTo(PM, PROJECT, new ProjectClientRequest(retired.id()))));
        written(scoped(() -> clients.update(ADMIN, retired.id(), new ClientWriteRequest(
                "Bergstrom AG", null, null, null, null, ClientStatus.ARCHIVED))));

        /*
          Without the archived client, the picker would render "None (internal)" as the selected
          value and detach the project on the next save — silently, on a screen whose Save the
          administrator pressed to change something else.
        */
        assertThat(allowed(scoped(() -> clients.pickableFor(PM, PROJECT))))
                .extracting(com.tcms.api.ClientRef::name)
                .containsExactlyInAnyOrder("Acme Retail", "Bergstrom AG");

        written(scoped(() -> clients.attachTo(PM, PROJECT, new ProjectClientRequest(live.id()))));
        assertThat(allowed(scoped(() -> clients.pickableFor(PM, PROJECT))))
                .extracting(com.tcms.api.ClientRef::name)
                .containsExactly("Acme Retail");
    }

    /**
     * Everything this class wrote, in foreign-key order.
     *
     * <p><strong>Projects are detached first.</strong> {@code fk_project_client} is
     * {@code ON DELETE RESTRICT} (018 D-k), so a client a test attached to the shared project
     * cannot be deleted while that reference stands — and the failure would be a constraint
     * violation in teardown, which reports as the *next* test failing.
     *
     * <p>{@code @BeforeEach} inserts the company, the users and the project with
     * {@code ON CONFLICT DO NOTHING}, so those persist across the class deliberately. The clients
     * must not: {@code uq_client_name} is {@code (company_id, name)}, and every test here records
     * "Acme Retail". Without this, the second test to run fails on the constraint and the test that
     * asserts an EMPTY list — the module's whole acceptance criterion — sees the previous test's
     * rows and fails for a reason that has nothing to do with what it is checking.
     */
    @AfterEach
    void wipeClients() throws SQLException {
        try (Connection connection = superuserConnection()) {
            for (String statement : new String[] {
                    "UPDATE project SET client_id = NULL WHERE company_id = CAST(? AS uuid)",
                    "DELETE FROM client_contact WHERE company_id = CAST(? AS uuid)",
                    "DELETE FROM client WHERE company_id = CAST(? AS uuid)"}) {
                try (PreparedStatement prepared = connection.prepareStatement(statement)) {
                    prepared.setObject(1, ACME);
                    prepared.executeUpdate();
                }
            }
        }
    }

    // =====================================================================

    private <T> T scoped(Supplier<T> call) {
        try (var ignored = CompanyContext.open(ACME)) {
            return tx.execute(status -> call.get());
        }
    }

    private static <T> T allowed(GateResult<T> gate) {
        assertThat(gate).isInstanceOf(GateResult.Allowed.class);
        return ((GateResult.Allowed<T>) gate).value();
    }

    private static <T> ClientService.Outcome<T> valueOf(GateResult<ClientService.Outcome<T>> gate) {
        return allowed(gate);
    }

    private static <T> T written(GateResult<ClientService.Outcome<T>> gate) {
        ClientService.Outcome<T> outcome = valueOf(gate);
        assertThat(outcome).isInstanceOf(ClientService.Outcome.Written.class);
        return ((ClientService.Outcome.Written<T>) outcome).value();
    }
}
