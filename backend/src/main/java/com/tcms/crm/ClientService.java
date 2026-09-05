package com.tcms.crm;

import com.tcms.api.ClientRef;
import com.tcms.api.UserRef;
import com.tcms.project.GateResult;
import com.tcms.project.PeopleDirectory;
import com.tcms.project.Project;
import com.tcms.project.ProjectRepository;
import com.tcms.project.ProjectService;
import com.tcms.project.ProjectView;
import com.tcms.tenancy.CompanyContext;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The client surface — CRUD, contacts, and the project-settings picker.
 *
 * <h2>One key for the reads, two for the writes</h2>
 *
 * <p>{@code client:read} answers both questions this service reads, so no read here needs the
 * either-or gate {@code staffing} and {@code time} both needed. Finance holds that key and
 * <strong>not</strong> {@code client:edit}: an invoice has to name a client, and maintaining the
 * record is the Account Manager's job. That is a grant {@code V2} seeded and this module must not
 * move.
 *
 * <h2>Three refusals, and each is a different status on purpose</h2>
 *
 * <ul>
 *   <li><strong>403</strong> — the caller lacks the key. {@link GateResult.Denied}.</li>
 *   <li><strong>404</strong> — no such client, in this company or at all, and the two are folded
 *       together because telling them apart makes the endpoint an oracle for ids in another
 *       client's tenancy (§6t). {@link GateResult.Unreachable}.</li>
 *   <li><strong>409</strong> — the client exists, the caller may delete, and a project names it.
 *       That is not a concealment question and not a field the caller can fix, so it is neither of
 *       the two above (018 D-k).</li>
 * </ul>
 *
 * <h2>Nothing here can answer what an hour costs</h2>
 *
 * <p>No rate, no budget, no invoice — see the package comment. {@code client.html}'s
 * hours-and-invoices table is M8's whole table, not three of its columns.
 */
@Service
public class ClientService {

    private static final String READ = "client:read";
    private static final String CREATE = "client:create";
    private static final String EDIT = "client:edit";
    private static final String DELETE = "client:delete";

    /**
     * The key the PICKER costs, and it is deliberately not one of the four above.
     *
     * <p>Story C2's picker rides {@code project:settings} (plan 010 D-M0-9). Project Manager is
     * PROJECT-scope, so a company-wide {@code client:read} would be an inert row for them; and
     * granting {@code client:read} to the {@code Company Member} baseline would break the one
     * sentence that role is defined by — "exactly {@code company:read} and nothing else".
     */
    private static final String PROJECT_SETTINGS = "project:settings";

    private final ClientRepository clients;
    private final ClientContactRepository contacts;
    private final ProjectRepository projects;
    private final ProjectService access;
    private final PeopleDirectory people;
    private final Clock clock;

    ClientService(
            ClientRepository clients,
            ClientContactRepository contacts,
            ProjectRepository projects,
            ProjectService access,
            PeopleDirectory people,
            Clock clock) {
        this.clients = clients;
        this.contacts = contacts;
        this.projects = projects;
        this.access = access;
        this.people = people;
        this.clock = clock;
    }

    /**
     * The outcome of a client write.
     *
     * <p>Sealed so every controller {@code switch} over it is exhaustive at compile time — the
     * failure that guards against is a fifth outcome added later and handled in three of the five
     * endpoints, with the others falling through to whatever their {@code else} branch happened to
     * be. Same shape as {@code staffing.ProfileService.Outcome}, plus {@link Missing}, which that
     * one did not need: a client write names a target and a staffing write names a person the
     * caller already resolved.
     */
    public sealed interface Outcome<T> {

        /** A field the caller can fix — a blank name, an account manager who is not a member. */
        record Invalid<T>(Map<String, List<String>> errors) implements Outcome<T> {
        }

        /** No such client or contact here. The 404. */
        record Missing<T>() implements Outcome<T> {
        }

        /** The 409 — today only a delete refused because a project names the client. */
        record Conflict<T>(String title, String detail) implements Outcome<T> {
        }

        record Written<T>(T value) implements Outcome<T> {
        }

        record Removed<T>() implements Outcome<T> {
        }
    }

    /* ------------------------------------------------------------------- reads --- */

    /**
     * {@code GET /clients} — {@code client:read} at company scope.
     *
     * <p><strong>An empty list is a complete answer, not an empty state.</strong> A company building
     * its own products never records a client (scope D1 rule 2), and nothing in the product nags,
     * blocks or degrades because of it. Every client-shaped surface reads this and renders nothing.
     */
    @Transactional(readOnly = true)
    public GateResult<List<ClientView>> list(UUID userId, ClientStatus status, UUID managerId) {
        return access.readInCompany(userId, READ, () -> {
            List<Client> rows = rowsFor(status, managerId);
            Map<UUID, UserRef> managers = resolveManagers(rows);
            List<ClientView> views = new ArrayList<>(rows.size());
            for (Client row : rows) {
                views.add(viewOf(row, managers));
            }
            return List.copyOf(views);
        });
    }

    /**
     * {@code GET /clients/{clientId}} — the client, its contacts and its projects.
     *
     * <p>Three sections from one read because {@code client.html} renders them together and none of
     * them is paged. The hours and invoice figures that screen also draws are M8's (018 D-g).
     */
    @Transactional(readOnly = true)
    public GateResult<ClientDetailView> read(UUID userId, UUID clientId) {
        if (!access.holdsInCompany(userId, READ)) {
            return new GateResult.Denied<>();
        }
        Optional<Client> found = clients.findById(clientId);
        if (found.isEmpty()) {
            return new GateResult.Unreachable<>();
        }
        Client row = found.get();
        return new GateResult.Allowed<>(new ClientDetailView(
                viewOf(row, resolveManagers(List.of(row))),
                contactViews(clientId),
                projectViews(clientId)));
    }

    /**
     * {@code GET /projects/{projectId}/settings/client-options} — story C2's picker.
     *
     * <p>Gated on {@code project:settings} and not on {@code client:read}; see
     * {@link #PROJECT_SETTINGS}. It is a separate path from the settings document because that one
     * is readable by anyone who can see the project, and an array of every client the company has is
     * not that kind of fact (018 D-e).
     *
     * <p><strong>The project's own client is added back whatever its status.</strong> Archiving
     * hides a client from the picker, but a project already attached to one keeps naming it — and a
     * picker that dropped it would render "None (internal)" as the selected value and detach the
     * project on the next save, silently, on a screen whose Save the administrator pressed to change
     * something else.
     */
    @Transactional(readOnly = true)
    public GateResult<List<ClientRef>> pickableFor(UUID userId, UUID projectId) {
        return access.writeGranted(userId, projectId, PROJECT_SETTINGS, project -> {
            List<Client> active = clients.findByStatusOrderByNameAsc(ClientStatus.ACTIVE);
            List<ClientRef> options = new ArrayList<>(active.size() + 1);
            for (Client row : active) {
                options.add(new ClientRef(row.getId(), row.getName()));
            }
            UUID attached = project.getClientId();
            if (attached != null && active.stream().noneMatch(row -> row.getId().equals(attached))) {
                clients.findById(attached).ifPresent(row ->
                        options.add(new ClientRef(row.getId(), row.getName())));
            }
            return List.copyOf(options);
        });
    }

    /**
     * {@code PUT /projects/{projectId}/client} — attach, change or detach, gated on
     * {@code project:settings}.
     *
     * <p><strong>The foreign key is not the company check.</strong> {@code fk_project_client} is on
     * {@code id} alone and referential integrity is not subject to RLS, so a client belonging to
     * another company would satisfy it and attach one company's project to another's customer.
     * {@link ClientRepository#findById} runs under RLS and does not find it, which is what turns
     * that into a {@code 400} on the right field rather than a {@code 500} that would also be an
     * oracle for what exists in another client's tenancy (018 D-l, §6t).
     *
     * <p>Sending the client the project already has is a no-op that answers {@code 200}, per the
     * endpoint's target-state contract.
     */
    @Transactional
    public GateResult<Outcome<ProjectView>> attachTo(
            UUID userId, UUID projectId, ProjectClientRequest body) {

        return access.writeGranted(userId, projectId, PROJECT_SETTINGS, project -> {
            UUID wanted = body == null ? null : body.clientId();
            if (wanted != null && clients.findById(wanted).isEmpty()) {
                return new Outcome.Invalid<>(Map.of(
                        "clientId", List.of("That client does not exist.")));
            }
            project.attachClient(wanted);
            /*
              Re-read through ProjectService so the response is assembled by the SAME method the
              list and detail endpoints use. A second assembler here would be a second answer to
              "what is a Project on the wire", and the one that drifts is always the one written
              beside the write.
            */
            return new Outcome.Written<>(
                    access.viewOfChanged(projects.saveAndFlush(project), userId));
        });
    }

    /* ------------------------------------------------------------------ writes --- */

    /** {@code POST /clients} — {@code client:create}. A name is the only required field. */
    @Transactional
    public GateResult<Outcome<ClientView>> create(UUID userId, ClientWriteRequest submitted) {
        return access.createGranted(userId, CREATE, () -> {
            Optional<Map<String, List<String>>> errors = validate(submitted);
            if (errors.isPresent()) {
                return new Outcome.Invalid<>(errors.get());
            }
            Instant now = clock.instant();
            Client saved = clients.save(Client.create(
                    UUID.randomUUID(), CompanyContext.require(), editOf(submitted), now));
            return new Outcome.Written<>(viewOf(saved, resolveManagers(List.of(saved))));
        });
    }

    /** {@code PUT /clients/{clientId}} — {@code client:edit}. A full replacement. */
    @Transactional
    public GateResult<Outcome<ClientView>> update(
            UUID userId, UUID clientId, ClientWriteRequest submitted) {

        return access.createGranted(userId, EDIT, () -> {
            Optional<Client> found = clients.findById(clientId);
            if (found.isEmpty()) {
                return new Outcome.Missing<>();
            }
            Optional<Map<String, List<String>>> errors = validate(submitted);
            if (errors.isPresent()) {
                return new Outcome.Invalid<>(errors.get());
            }
            Client row = found.get();
            row.apply(editOf(submitted), clock.instant());
            Client saved = clients.save(row);
            return new Outcome.Written<>(viewOf(saved, resolveManagers(List.of(saved))));
        });
    }

    /**
     * {@code DELETE /clients/{clientId}} — {@code client:delete}, and refused while a project names
     * it (018 D-k).
     *
     * <p>Detaching those projects on the way past would silently leave live work with no customer.
     * The conflict names archiving, which is the same shape of answer a project's own archive gives:
     * hide and freeze, delete nothing.
     *
     * <p>Its contacts go with it, by {@code ON DELETE CASCADE}. They describe who to talk to at an
     * organisation this company no longer records, and there was never an account to disable.
     */
    @Transactional
    public GateResult<Outcome<Void>> delete(UUID userId, UUID clientId) {
        return access.createGranted(userId, DELETE, () -> {
            Optional<Client> found = clients.findById(clientId);
            if (found.isEmpty()) {
                return new Outcome.Missing<>();
            }
            long naming = clients.countProjectsNaming(clientId);
            if (naming > 0) {
                return new Outcome.Conflict<>(
                        "The client still has projects",
                        naming + (naming == 1 ? " project names" : " projects name")
                                + " this client. Detach them first, or archive the client instead —"
                                + " deleting it would leave that work with no customer.");
            }
            clients.delete(found.get());
            return new Outcome.Removed<>();
        });
    }

    /**
     * {@code POST /clients/{clientId}/contacts} — {@code client:edit}.
     *
     * <p><strong>This creates a row of contact information and nothing else.</strong> No
     * {@code app_user}, no {@code company_membership}, no invitation, no credential.
     * {@code CrmCreatesNoLoginTest} asserts it by counting those tables across this call, because
     * "creates no login" is a claim about what is absent and this method cannot demonstrate it by
     * what it does.
     */
    @Transactional
    public GateResult<Outcome<ClientContactView>> addContact(
            UUID userId, UUID clientId, ClientContactWriteRequest submitted) {

        return access.createGranted(userId, EDIT, () -> {
            if (clients.findById(clientId).isEmpty()) {
                return new Outcome.Missing<>();
            }
            Optional<Map<String, List<String>>> errors = validateContact(submitted);
            if (errors.isPresent()) {
                return new Outcome.Invalid<>(errors.get());
            }
            ClientContact saved = contacts.save(ClientContact.create(
                    UUID.randomUUID(), CompanyContext.require(), clientId,
                    contactEditOf(submitted), clock.instant()));
            return new Outcome.Written<>(viewOf(saved));
        });
    }

    /** {@code PUT /clients/{clientId}/contacts/{contactId}} — creates no login either. */
    @Transactional
    public GateResult<Outcome<ClientContactView>> updateContact(
            UUID userId, UUID clientId, UUID contactId, ClientContactWriteRequest submitted) {

        return access.createGranted(userId, EDIT, () -> {
            Optional<ClientContact> found = contactIn(clientId, contactId);
            if (found.isEmpty()) {
                return new Outcome.Missing<>();
            }
            Optional<Map<String, List<String>>> errors = validateContact(submitted);
            if (errors.isPresent()) {
                return new Outcome.Invalid<>(errors.get());
            }
            ClientContact row = found.get();
            row.apply(contactEditOf(submitted), clock.instant());
            return new Outcome.Written<>(viewOf(contacts.save(row)));
        });
    }

    /** {@code DELETE /clients/{clientId}/contacts/{contactId}}. There was no account to disable. */
    @Transactional
    public GateResult<Outcome<Void>> removeContact(UUID userId, UUID clientId, UUID contactId) {
        return access.createGranted(userId, EDIT, () -> {
            Optional<ClientContact> found = contactIn(clientId, contactId);
            if (found.isEmpty()) {
                return new Outcome.Missing<>();
            }
            contacts.delete(found.get());
            return new Outcome.Removed<>();
        });
    }

    /* ------------------------------------------------------------------ innards --- */

    /**
     * The contact, but only if it belongs to the client the path names.
     *
     * <p><strong>Both segments are checked, and checking only the second is the bug this exists to
     * prevent.</strong> RLS keeps another company's contact out; nothing but this keeps
     * {@code /clients/A/contacts/{a contact of B}} from editing B's row through A's path, which
     * would let a caller who may see one client write to another's contact list.
     */
    private Optional<ClientContact> contactIn(UUID clientId, UUID contactId) {
        return contacts.findById(contactId)
                .filter(contact -> contact.getClientId().equals(clientId));
    }

    private List<Client> rowsFor(ClientStatus status, UUID managerId) {
        if (status == null) {
            return managerId == null
                    ? clients.findAllByOrderByNameAsc()
                    : clients.findByAccountManagerIdOrderByNameAsc(managerId);
        }
        return managerId == null
                ? clients.findByStatusOrderByNameAsc(status)
                : clients.findByStatusAndAccountManagerIdOrderByNameAsc(status, managerId);
    }

    /**
     * Every field error a client body can carry.
     *
     * <p>Two rules, and the second is the one worth reading. An {@code accountManagerId} naming
     * somebody who is not a member of this company is a <strong>400 on that field</strong> and not a
     * 404: the target resolved fine as far as the caller is concerned, the picker is where the
     * message belongs, and a 404 would be an oracle for user ids in another tenancy.
     */
    private Optional<Map<String, List<String>>> validate(ClientWriteRequest submitted) {
        String name = submitted.name();
        if (name == null || name.isBlank()) {
            return Optional.of(Map.of("name", List.of("A client needs a name.")));
        }
        UUID managerId = submitted.accountManagerId();
        if (managerId != null && !isMember(managerId)) {
            return Optional.of(Map.of(
                    "accountManagerId", List.of("That person is not a member of this company.")));
        }
        return Optional.empty();
    }

    private Optional<Map<String, List<String>>> validateContact(ClientContactWriteRequest submitted) {
        String name = submitted.name();
        if (name == null || name.isBlank()) {
            return Optional.of(Map.of("name", List.of("A contact needs a name.")));
        }
        return Optional.empty();
    }

    private boolean isMember(UUID userId) {
        return people.membersOf(CompanyContext.require()).stream()
                .anyMatch(member -> member.id().equals(userId));
    }

    private ClientEdit editOf(ClientWriteRequest submitted) {
        return new ClientEdit(
                submitted.name().strip(),
                blankToNull(submitted.location()),
                blankToNull(submitted.industry()),
                blankToNull(submitted.notes()),
                /*
                  ACTIVE when the caller left it out, which the contract declares as the default.
                  Resolved here rather than in the entity so that "what a caller may omit" stays a
                  property of the request and never becomes a nullable column.
                */
                submitted.status() == null ? ClientStatus.ACTIVE : submitted.status(),
                submitted.accountManagerId());
    }

    private ContactEdit contactEditOf(ClientContactWriteRequest submitted) {
        return new ContactEdit(
                submitted.name().strip(),
                blankToNull(submitted.role()),
                blankToNull(submitted.email()),
                blankToNull(submitted.phone()));
    }

    /**
     * An empty string is not a value here, it is a field the user cleared.
     *
     * <p>Storing {@code ""} would make "no location recorded" and "location deliberately blank" two
     * states the screens render identically and no query can tell apart.
     */
    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private Map<UUID, UserRef> resolveManagers(List<Client> rows) {
        List<UUID> ids = rows.stream()
                .map(Client::getAccountManagerId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        return ids.isEmpty() ? Map.of() : people.resolve(CompanyContext.require(), ids);
    }

    private ClientView viewOf(Client row, Map<UUID, UserRef> managers) {
        return new ClientView(
                row.getId(),
                row.getName(),
                row.getLocation(),
                row.getIndustry(),
                row.getNotes(),
                row.getStatus(),
                row.getAccountManagerId() == null ? null : managers.get(row.getAccountManagerId()),
                (int) clients.countProjectsNaming(row.getId()),
                contacts.findByClientIdOrderByNameAsc(row.getId()).size(),
                row.getCreatedAt());
    }

    private static ClientContactView viewOf(ClientContact row) {
        return new ClientContactView(
                row.getId(), row.getName(), row.getRole(), row.getEmail(), row.getPhone());
    }

    private List<ClientContactView> contactViews(UUID clientId) {
        return contacts.findByClientIdOrderByNameAsc(clientId).stream()
                .map(ClientService::viewOf)
                .toList();
    }

    private List<ClientProjectView> projectViews(UUID clientId) {
        return projects.findAllByOrderByNameAsc().stream()
                .filter(project -> clientId.equals(project.getClientId()))
                .map(project -> new ClientProjectView(
                        project.getId(), project.getKey(), project.getName(), project.isArchived()))
                .toList();
    }
}
