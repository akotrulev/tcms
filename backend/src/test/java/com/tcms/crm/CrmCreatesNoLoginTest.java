package com.tcms.crm;

import static org.assertj.core.api.Assertions.assertThat;

import com.tcms.identity.PermissionEvaluator;
import com.tcms.project.GateResult;
import com.tcms.tenancy.CompanyContext;
import com.tcms.tracker.TrackerEndpointTestBase;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;

/**
 * The two negatives scope D1 rests on, <strong>asserted rather than read</strong>.
 *
 * <p>The working agreement's rule applies literally: <em>a catalogue-wide invariant needs a computed
 * check, not a careful reading.</em> Reading the roles spec §A3 and concluding that a Company Member
 * lacks the client keys is the same pass that missed five join tables without {@code company_id} —
 * and worse here, because §A3 is the document the seed is <em>built from</em>, so a test that
 * consulted it would agree with a seed that had the grant inverted. What is asserted is what a
 * person actually evaluates to, and what the endpoints actually answer them.
 *
 * <p>The second negative is a claim about what is <strong>absent</strong>: adding a contact must
 * create no account. A service test cannot demonstrate that by what it does — it can only count the
 * identity tables on either side of the call, which is what this does.
 *
 * <p><strong>Written so Mc's boundary is standing before anything can widen it</strong>, the way
 * {@code time.TimeMoneyBoundaryTest} was written before M8 existed. The client surface is the screen
 * M8 adds rate cards to, so the money assertion below is here rather than waiting for the module
 * that would be the one to break it.
 *
 * <p><strong>UUID prefix {@code ff}, and it was verified against the tree rather than assumed.</strong>
 * The first attempt took {@code fb}, which is ClassificationValueWriteEndpointTest's — {@code fb001000-0000-4000-8000-000000000001} is
 * literally that class's project id, so both classes passed alone and the pair failed in the suite
 * with a {@code project_pkey} duplicate naming neither of them. That is the exact failure
 * {@code StaffingTestBase}'s header describes, and the check that would have caught it is one
 * command: grep the tree for the prefix before taking it.
 */
@AutoConfigureMockMvc
@DisplayName("Clients — a Company Member is refused, and a contact is never a login")
class CrmCreatesNoLoginTest extends TrackerEndpointTestBase {

    /** Company Member — "exactly {@code company:read} and nothing else", the baseline everybody has. */
    private static final UUID COMPANY_MEMBER_ROLE =
            UUID.fromString("10000000-0000-4000-8000-000000000007");
    private static final UUID COMPANY_ADMIN_ROLE =
            UUID.fromString("10000000-0000-4000-8000-000000000002");

    /** The four keys {@code V2} seeded. Held as a literal AND compared to the catalogue below. */
    private static final Set<String> CLIENT_KEYS =
            Set.of("client:read", "client:create", "client:edit", "client:delete");

    private static final UUID ACME = UUID.fromString("ff000000-0000-4000-8000-000000000001");
    private static final UUID PROJECT = UUID.fromString("ff001000-0000-4000-8000-000000000001");
    private static final UUID ADMIN = UUID.fromString("ff002000-0000-4000-8000-000000000001");
    private static final UUID NOBODY = UUID.fromString("ff002000-0000-4000-8000-000000000002");
    private static final UUID ADMIN_GRANT = UUID.fromString("ff003000-0000-4000-8000-000000000001");
    private static final UUID NOBODY_GRANT = UUID.fromString("ff003000-0000-4000-8000-000000000002");

    @Autowired
    private ClientService clients;

    @Autowired
    private PermissionEvaluator permissions;

    @BeforeEach
    void seed() throws SQLException {
        Instant now = Instant.now();
        tx.executeWithoutResult(status -> {
            company(ACME, "crm-neg", "Acme", now);
            activeUser(ADMIN, "admin@crmneg.test", "Ada Admin", now);
            activeUser(NOBODY, "member@crmneg.test", "Mo Member", now);
        });
        inCompany(ACME, () -> tx.executeWithoutResult(status -> {
            seedProject(PROJECT, ACME, "NEG", "Storefront", now);
            member(ACME, ADMIN, "Ada Admin", now);
            member(ACME, NOBODY, "Mo Member", now);
        }));
        grantInCompany(ADMIN_GRANT, ACME, COMPANY_ADMIN_ROLE, ADMIN);
        grantInCompany(NOBODY_GRANT, ACME, COMPANY_MEMBER_ROLE, NOBODY);
        seedProjectDefaults(ACME, PROJECT);
    }

    @Test
    @DisplayName("every client key in the catalogue is covered by this test")
    void everyClientKeyInTheCatalogueIsCoveredByThisTest() throws SQLException {
        Set<String> fromCatalogue = new java.util.LinkedHashSet<>();
        try (Connection connection = superuserConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT key FROM permission WHERE key LIKE 'client:%' ORDER BY key");
                ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                fromCatalogue.add(rows.getString(1));
            }
        }

        assertThat(fromCatalogue)
                .as("a client key was added to the catalogue and this test did not move with it — "
                        + "the surface grew and nothing here is asserting the new key's absence")
                .isEqualTo(CLIENT_KEYS);
    }

    @Test
    @DisplayName("a Company Member holds company:read and not one client key")
    void aCompanyMemberHoldsCompanyReadAndNotOneClientKey() {
        Set<String> held = heldBy(NOBODY);

        assertThat(held)
                .as("the one sentence the Company Member role is defined by")
                .contains("company:read");
        assertThat(held)
                .as("plan 010 D-M0-9: granting client:read to the baseline would break that "
                        + "sentence, which is why story C2's picker rides project:settings instead")
                .doesNotContainAnyElementsOf(CLIENT_KEYS);
    }

    @Test
    @DisplayName("and is refused by every client endpoint, not merely absent from the grant")
    void aCompanyMemberIsRefusedByEveryClientEndpoint() {
        /*
          The grant above and the refusal here are two different claims, and asserting only the
          first is how an endpoint that forgot its gate ships green. Every arm, because a gate is
          added per method and the one nobody listed is the one nobody wrote.
        */
        UUID anyClient = UUID.fromString("ff004000-0000-4000-8000-000000000001");
        ClientWriteRequest body = new ClientWriteRequest("Acme", null, null, null, null, null);
        ClientContactWriteRequest contact = new ClientContactWriteRequest("H", null, null, null);

        assertThat(scoped(() -> clients.list(NOBODY, ClientStatus.ACTIVE, null)))
                .isInstanceOf(GateResult.Denied.class);
        assertThat(scoped(() -> clients.read(NOBODY, anyClient)))
                .isInstanceOf(GateResult.Denied.class);
        assertThat(scoped(() -> clients.create(NOBODY, body)))
                .isInstanceOf(GateResult.Denied.class);
        assertThat(scoped(() -> clients.update(NOBODY, anyClient, body)))
                .isInstanceOf(GateResult.Denied.class);
        assertThat(scoped(() -> clients.delete(NOBODY, anyClient)))
                .isInstanceOf(GateResult.Denied.class);
        assertThat(scoped(() -> clients.addContact(NOBODY, anyClient, contact)))
                .isInstanceOf(GateResult.Denied.class);
        assertThat(scoped(() -> clients.updateContact(NOBODY, anyClient, anyClient, contact)))
                .isInstanceOf(GateResult.Denied.class);
        assertThat(scoped(() -> clients.removeContact(NOBODY, anyClient, anyClient)))
                .isInstanceOf(GateResult.Denied.class);

        /*
          The picker too, and it refuses for a DIFFERENT reason — project:settings, which a Company
          Member also lacks. Unreachable rather than Denied: they cannot see the project at all, and
          telling them a project exists that they may not configure would be the oracle §6t forbids.
        */
        assertThat(scoped(() -> clients.pickableFor(NOBODY, PROJECT)))
                .isInstanceOf(GateResult.Unreachable.class);
    }

    @Test
    @DisplayName("scope D1 — adding a contact creates no user, no membership and no invitation")
    void addingAContactCreatesNoIdentity() throws SQLException {
        /*
          Five tables, and the last two are the ones that actually carry a credential:
          `user_identity` is a federated or password identity and `user_social_identity` is an OAuth
          one. Counting only `app_user` would miss a contact that acquired a way to sign in without
          acquiring a row in the user table — which is the shape a client portal would take if
          somebody built one by extending D1 rather than reopening it.
        */
        long usersBefore = countAll("app_user");
        long membershipsBefore = countAll("company_membership");
        long invitationsBefore = countAll("membership_invitation");
        long identitiesBefore = countAll("user_identity");
        long socialBefore = countAll("user_social_identity");

        ClientView client = written(scoped(() -> clients.create(ADMIN,
                new ClientWriteRequest("Acme Retail", null, null, null, null, null))));
        written(scoped(() -> clients.addContact(ADMIN, client.id(),
                new ClientContactWriteRequest(
                        "Hanna Lund", "Product owner", "hanna.lund@crmneg.test", "+46 8 555 0142"))));

        /*
          Counted over the WHOLE instance and not this company, on the superuser connection: the
          failure this guards against is a contact write reaching an identity table at all, and a
          company-scoped count would miss a row written with the wrong company context — which RLS
          hides rather than refuses.
        */
        assertThat(countAll("app_user"))
                .as("a client contact is a record, not a principal (scope D1). If this ever fails, "
                        + "D1 is being reopened rather than extended — a client portal is a second "
                        + "authentication path and a real confidentiality boundary")
                .isEqualTo(usersBefore);
        assertThat(countAll("company_membership")).isEqualTo(membershipsBefore);
        assertThat(countAll("membership_invitation")).isEqualTo(invitationsBefore);
        assertThat(countAll("user_identity")).isEqualTo(identitiesBefore);
        assertThat(countAll("user_social_identity")).isEqualTo(socialBefore);
    }

    @Test
    @DisplayName("the client surface carries no money word anywhere in what it serves")
    void theClientSurfaceCarriesNoMoneyWord() throws Exception {
        ClientView client = written(scoped(() -> clients.create(ADMIN,
                new ClientWriteRequest("Acme Retail", "Stockholm", "retail", null, null, null))));
        written(scoped(() -> clients.addContact(ADMIN, client.id(),
                new ClientContactWriteRequest("Hanna Lund", "Product owner", null, null))));
        ClientDetailView detail = allowed(scoped(() -> clients.read(ADMIN, client.id())));

        String body = json.writeValueAsString(detail).toLowerCase(java.util.Locale.ROOT);

        /*
          `time.TimeMoneyBoundaryTest`'s assertion, on the surface M8 will add rate cards to.
          Over the SERIALIZED body rather than the record's components, for its reason: a field
          reaching the wire under a renamed member, or on a nested view somebody adds below the one
          this test names, is still a money word served.

          client.html draws an hours-and-invoices table and a rate card panel on THIS page. Both are
          M8's, and an Account Manager — who holds client:read and no billing key — sees the client
          and the hours and never a rate (scope §7). This is the assertion that makes that a fact
          about the code rather than a claim in a comment.
        */
        assertThat(body)
                .as("nothing on the client surface may carry money: what a client's work is worth "
                        + "is M8's, gated on billing:* and ratecard:read")
                .doesNotContain("rate")
                .doesNotContain("cost")
                .doesNotContain("amount")
                .doesNotContain("currency")
                .doesNotContain("invoice")
                .doesNotContain("budget");
        assertThat(body).contains("acme retail");
    }

    /**
     * The sibling 019 T4 added, and it is a sibling rather than a replacement.
     *
     * <p>{@link #theClientSurfaceCarriesNoMoneyWord} above is <strong>unchanged by M8 and must stay
     * that way</strong>: its subject is {@code ClientDetail}, which carries no money word before or
     * after this module. Deleting or weakening it to accommodate billing would be the boundary
     * moving because a new module found it inconvenient, which is exactly what writing it before M8
     * existed was meant to prevent.
     *
     * <p>What M8 adds is a <em>separate schema on a separate path</em>, and this records where — so
     * the next reader of the assertion above does not conclude that the boundary moved, and so a
     * second money-bearing path under a {@code clients} URL fails here rather than passing
     * unnoticed.
     *
     * <p>Parsed from the document's text rather than from an object model, which is
     * {@code billing.BillingMoneyBoundaryTest}'s device and the same argument: what is being
     * asserted is what somebody wrote.
     */
    @Test
    @DisplayName("019 — the one path under a clients URL where a money word is now allowed")
    void theOnlyClientPathThatMayCarryMoneyIsTheBillingSummary() {
        List<String> clientPaths = pathsTagged("clients");

        assertThat(clientPaths)
                .as("no clients-tagged path was found at all — the tag, the document's location or "
                        + "this parser has moved, and the assertion below would pass over nothing")
                .hasSizeGreaterThanOrEqualTo(4);
        assertThat(clientPaths)
                .as("""
                        GET /clients/{clientId}/billing is billing's, gated on client:read, and it \
                        is the only clients-tagged path that may serve an amount — and only to a \
                        caller holding billing:read, which ClientBillingSummary.amountsVisible \
                        answers server-side (019 D-i). Every other one is crm's and carries no \
                        money word at all, which the assertion above is what proves.""")
                .filteredOn(path -> path.endsWith("/billing"))
                .singleElement()
                .isEqualTo("/clients/{clientId}/billing");
    }

    /** Every path item in the contract whose operations carry this tag. */
    private static List<String> pathsTagged(String tag) {
        String contract;
        try {
            contract = Files.readString(Path.of("..", "docs", "specs", "openapi.yaml"));
        } catch (IOException unreadable) {
            throw new UncheckedIOException(
                    "the contract is this rule's population, and backend/build.gradle.kts declares "
                            + "docs/specs/ as a test input so it is on disk", unreadable);
        }
        int from = contract.indexOf("\npaths:");
        int to = contract.indexOf("\ncomponents:");
        String section = contract.substring(from, to > from ? to : contract.length());

        Pattern pathItem = Pattern.compile("^ {2}(/\\S*):\\s*$");
        String marker = "tags: [" + tag + "]";
        List<String> tagged = new java.util.ArrayList<>();
        String current = null;
        StringBuilder body = new StringBuilder();
        for (String line : section.split("\n", -1)) {
            Matcher header = pathItem.matcher(line);
            if (header.matches()) {
                if (current != null && body.indexOf(marker) >= 0) {
                    tagged.add(current);
                }
                current = header.group(1);
                body.setLength(0);
            } else if (current != null) {
                body.append(line).append('\n');
            }
        }
        if (current != null && body.indexOf(marker) >= 0) {
            tagged.add(current);
        }
        return List.copyOf(tagged);
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

    private long countAll(String table) throws SQLException {
        try (Connection connection = superuserConnection();
                PreparedStatement statement =
                        connection.prepareStatement("SELECT count(*) FROM " + table);
                ResultSet rows = statement.executeQuery()) {
            rows.next();
            return rows.getLong(1);
        }
    }

    private Set<String> heldBy(UUID userId) {
        return scoped(() -> tx.execute(status -> permissions.evaluate(userId, PROJECT).keys()));
    }

    private <T> T scoped(Supplier<T> call) {
        try (var ignored = CompanyContext.open(ACME)) {
            return tx.execute(status -> call.get());
        }
    }

    private static <T> T allowed(GateResult<T> gate) {
        assertThat(gate).isInstanceOf(GateResult.Allowed.class);
        return ((GateResult.Allowed<T>) gate).value();
    }

    private static <T> T written(GateResult<ClientService.Outcome<T>> gate) {
        ClientService.Outcome<T> outcome = allowed(gate);
        assertThat(outcome).isInstanceOf(ClientService.Outcome.Written.class);
        return ((ClientService.Outcome.Written<T>) outcome).value();
    }
}
