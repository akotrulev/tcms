package com.tcms.provisioning;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tcms.identity.AppUser;
import com.tcms.identity.AppUserRepository;
import com.tcms.identity.CompanyMembership;
import com.tcms.identity.CompanyMembershipRepository;
import com.tcms.identity.InvitationService;
import com.tcms.identity.Role;
import com.tcms.identity.RoleAssignment;
import com.tcms.identity.RoleAssignmentRepository;
import com.tcms.identity.RoleRepository;
import com.tcms.identity.RoleScope;
import com.tcms.project.Project;
import com.tcms.project.ProjectRepository;
import com.tcms.tenancy.Company;
import com.tcms.tenancy.CompanyContext;
import com.tcms.tenancy.CompanyRepository;
import com.tcms.tenancy.InstanceAuditEvent;
import com.tcms.tenancy.InstanceAuditEventRepository;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Brings a client company into existence: the company row, its vocabularies and
 * display-key counters, an optional first project, the first Company Admin as an
 * invitation, and the operator audit entry.
 *
 * <p><strong>The only provisioning path.</strong> A company that exists without its
 * classification vocabularies, its {@code key_sequence} counters or an administrator is
 * worse than no company — it fails later, in another subsystem, in a way that reads as a
 * bug in that subsystem. So a second caller (a future admin API) calls this class; it
 * does not assemble the parts itself.
 *
 * <h2>Idempotent by design, because the alternative is a half-provisioned company</h2>
 *
 * <p>Every step is "create if absent". Re-running against an existing slug tops it up —
 * which is the supported way to give a company a vocabulary or a counter added in a later
 * release (see {@code V5}) — and reports per part what it actually did, so a top-up run
 * and a fresh one do not read alike. What it deliberately does <em>not</em> do is
 * <em>update</em> anything: a differing display name on a re-run is left alone rather
 * than silently overwriting a rename the client asked for.
 *
 * <h2>The ordering is load-bearing, in two directions</h2>
 *
 * <ol>
 *   <li><strong>Company context is opened around the transaction, not inside it.</strong>
 *       {@code CompanyAwareTransactionManager} reads {@link CompanyContext} in
 *       {@code doBegin}, so a context opened after the transaction started would leave
 *       the {@code app.current_company} GUC unset — and every company-owned insert here
 *       would then be refused by the RLS {@code WITH CHECK}, or silently see nothing.</li>
 *   <li><strong>Each row is flushed before whatever depends on it.</strong> Postgres
 *       checks foreign keys immediately, and {@code tcms_seed_project_defaults} refuses a
 *       project it cannot see. {@code hibernate.order_inserts} orders inserts by entity
 *       type, which is not the dependency order this needs.
 *       <p><em>Reported rather than overclaimed:</em> mutating {@code saveAndFlush} to
 *       {@code save} before the project seed call <strong>failed no test</strong> —
 *       Hibernate's AUTO flush mode already flushes the persistence context ahead of a
 *       native query. So the explicit flush is not what makes that call work today; it is
 *       what stops it depending on a flush mode nobody set deliberately. Kept, with the
 *       survival recorded, so it is not deleted later on the evidence that no test
 *       covers it.</li>
 * </ol>
 *
 * <h2>Concurrency</h2>
 *
 * <p>Two operators provisioning the same slug at the same instant is resolved by
 * {@code uq_company_slug} — one transaction commits and the other fails on the
 * constraint. Not serialized here: an operator CLI run twice concurrently is a mistake
 * worth surfacing loudly, and an advisory lock would only convert a clear constraint
 * violation into a silent second no-op run.
 */
@Service
public class CompanyProvisioningService {

    /** Matches the role seeded by {@code V3}. Grants are by role, never by name at runtime. */
    static final String COMPANY_ADMIN_ROLE = "Company Admin";

    /** The audit action recorded for every run, top-up or not. */
    static final String AUDIT_ACTION = "company.provision";

    private static final Logger log = LoggerFactory.getLogger(CompanyProvisioningService.class);

    private final CompanyRepository companies;
    private final AppUserRepository users;
    private final CompanyMembershipRepository memberships;
    private final RoleRepository roles;
    private final RoleAssignmentRepository roleAssignments;
    private final ProjectRepository projects;
    private final InstanceAuditEventRepository auditEvents;
    private final InvitationService invitations;
    private final EntityManager entityManager;
    private final TransactionTemplate transactions;
    private final ObjectMapper json;
    private final Clock clock;

    public CompanyProvisioningService(
            CompanyRepository companies,
            AppUserRepository users,
            CompanyMembershipRepository memberships,
            RoleRepository roles,
            RoleAssignmentRepository roleAssignments,
            ProjectRepository projects,
            InstanceAuditEventRepository auditEvents,
            InvitationService invitations,
            EntityManager entityManager,
            TransactionTemplate transactions,
            ObjectMapper json,
            Clock clock) {
        this.companies = companies;
        this.users = users;
        this.memberships = memberships;
        this.roles = roles;
        this.roleAssignments = roleAssignments;
        this.projects = projects;
        this.auditEvents = auditEvents;
        this.invitations = invitations;
        this.entityManager = entityManager;
        this.transactions = transactions;
        this.json = json;
        this.clock = clock;
    }

    /** Provisions unattended — the CLI case, where there is no human actor to attribute. */
    public ProvisionCompanyResult provision(ProvisionCompanyRequest request) {
        return provision(request, null);
    }

    /**
     * Provisions on behalf of an operator.
     *
     * @param actorUserId the Instance Admin performing this, or {@code null} for an
     *                    unattended run. Recorded in the instance audit trail; a
     *                    synthetic "system" actor is deliberately not invented, because a
     *                    fake actor row is indistinguishable from a real one later.
     */
    public ProvisionCompanyResult provision(ProvisionCompanyRequest request, UUID actorUserId) {
        if (request == null) {
            throw new IllegalArgumentException("request must not be null");
        }

        // Resolve (or reserve) the company id BEFORE opening the context, because the
        // context is what the transaction manager turns into the RLS GUC — and on a
        // re-run the existing company's id is the one every company-owned row must carry.
        UUID companyId = companies.findBySlug(request.slug())
                .map(Company::getId)
                .orElseGet(UUID::randomUUID);

        try (var ignored = CompanyContext.open(companyId)) {
            return transactions.execute(status -> doProvision(request, companyId, actorUserId));
        }
    }

    private ProvisionCompanyResult doProvision(
            ProvisionCompanyRequest request, UUID companyId, UUID actorUserId) {
        Instant now = clock.instant();

        boolean companyCreated = createCompanyIfAbsent(request, companyId, now);

        // Vocabularies and the five display-key counters. Idempotent, and the reason a
        // re-run is the supported way to top up a company provisioned before V5.
        // Nothing outside provisioning may call these two functions (ERD §10).
        callFunction("SELECT tcms_seed_company_defaults(CAST(:companyId AS uuid))", companyId, null);

        AdminOutcome adminOutcome = findOrInviteAdmin(request, now);
        AppUser admin = adminOutcome.user();

        boolean membershipCreated = inviteMembershipIfAbsent(request, companyId, admin, now);
        boolean grantCreated = grantCompanyAdminIfAbsent(companyId, admin, actorUserId, now);

        // Every member's company:read baseline (V15) — the admin just invited above, and everyone
        // who joined before this release. AFTER the membership insert, so the fresh admin is one of
        // the rows it covers rather than needing a second call.
        //
        // Company-wide rather than "the admin we just created", deliberately: re-running the CLI is
        // the supported way to top a company up after a release (V5), and a company provisioned
        // before V15 whose members were then granted by hand has exactly the gap this repairs. It
        // is one statement guarded by NOT EXISTS, so a re-run on a healthy company writes nothing.
        int baselineGrants = grantCompanyReadBaseline(companyId);

        ProjectOutcome project = createFirstProjectIfRequested(request, companyId, now);

        // Inside this transaction on purpose: a failure below must take the invitation with
        // it, rather than leaving a live token for a company that rolled back. It is also
        // why the mail is sent AFTER commit and not from here.
        InvitationService.IssueOutcome invitation =
                invitations.issueIfPending(companyId, admin.getId(), actorUserId);

        ProvisionCompanyResult result = new ProvisionCompanyResult(
                companyId,
                companyCreated,
                admin.getId(),
                adminOutcome.created(),
                membershipCreated,
                grantCreated,
                baselineGrants,
                project.id(),
                project.created(),
                invitation.acceptUrl(),
                invitation.expiresAt());

        auditEvents.save(InstanceAuditEvent.record(
                UUID.randomUUID(),
                actorUserId,
                AUDIT_ACTION,
                companyId,
                "company",
                describe(request, result),
                now));

        log.info("Provisioned company {} ({}) — created={} admin={} project={}",
                request.slug(), companyId, companyCreated, admin.getId(), project.id().orElse(null));
        return result;
    }

    private boolean createCompanyIfAbsent(ProvisionCompanyRequest request, UUID companyId, Instant now) {
        if (companies.findBySlug(request.slug()).isPresent()) {
            return false;
        }
        // saveAndFlush, not save: role_assignment, company_membership and key_sequence all
        // carry a foreign key to this row and Postgres checks it immediately.
        companies.saveAndFlush(
                Company.provision(companyId, request.slug(), request.displayName(), now));
        return true;
    }

    /**
     * The instance-wide email rule made concrete: a person who works with two of your
     * clients has <em>one</em> account and two memberships. Creating a second account
     * would be refused by {@code uq_app_user_email} anyway — this is what makes that a
     * supported case rather than a failure.
     */
    private AdminOutcome findOrInviteAdmin(ProvisionCompanyRequest request, Instant now) {
        Optional<AppUser> existing = users.findByEmail(request.adminEmail());
        if (existing.isPresent()) {
            return new AdminOutcome(existing.get(), false);
        }
        // canonicalName is the Instance Admin directory's name, NOT a user-facing one.
        // The user-facing name is the per-membership display name below (§6u).
        AppUser invited = users.saveAndFlush(
                AppUser.invite(UUID.randomUUID(), request.adminEmail(), request.adminName(), now));
        return new AdminOutcome(invited, true);
    }

    private boolean inviteMembershipIfAbsent(
            ProvisionCompanyRequest request, UUID companyId, AppUser admin, Instant now) {
        if (memberships.findByCompanyIdAndUserId(companyId, admin.getId()).isPresent()) {
            return false;
        }
        // INVITED, not ACTIVE. Accepting is an explicit act by the human, and provisioning
        // must not be able to fabricate one — an operator activating a client's admin
        // account on their behalf is the audit finding nobody wants to explain.
        // invitedBy is the actor where there is one; the FK allows null for an unattended run.
        memberships.saveAndFlush(CompanyMembership.invite(
                companyId, admin.getId(), request.adminName(), null, now));
        return true;
    }

    private boolean grantCompanyAdminIfAbsent(
            UUID companyId, AppUser admin, UUID actorUserId, Instant now) {
        Role companyAdmin = roles.findByName(COMPANY_ADMIN_ROLE)
                .orElseThrow(() -> new IllegalStateException(
                        "The '" + COMPANY_ADMIN_ROLE + "' role is missing. It is seeded by V3; a "
                                + "database this is absent from has not been fully migrated."));

        if (companyAdmin.getScope() != RoleScope.COMPANY) {
            throw new IllegalStateException(
                    "'" + COMPANY_ADMIN_ROLE + "' is seeded with scope " + companyAdmin.getScope()
                            + ", expected COMPANY. Granting it company-wide would widen or narrow the "
                            + "role beyond what the roles spec defines.");
        }

        if (roleAssignments.existsByCompanyIdAndRoleIdAndUserIdAndProjectIdIsNull(
                companyId, companyAdmin.getId(), admin.getId())) {
            return false;
        }

        // COMPANY scope: every project in the company, present and future. That is the
        // whole point of the role — a Company Admin must not need a grant per project.
        roleAssignments.saveAndFlush(RoleAssignment.forUserOnCompany(
                UUID.randomUUID(), companyId, companyAdmin.getId(), admin.getId(), actorUserId, now));
        return true;
    }

    /**
     * Grants the {@code company:read} baseline to every member who lacks it, and reports how many
     * rows that wrote.
     *
     * <p>The statement lives in {@code tcms_grant_company_read_baseline} (V15) rather than here for
     * the reason V2 §4 gives about the vocabularies: three callers need exactly it — that
     * migration's backfill, this, and invitation acceptance — and the copy that drifts is the one
     * that decides whether a client can see their own company. Same shape as the two seed functions
     * above, including the refusal to run without a matching company context.
     */
    private int grantCompanyReadBaseline(UUID companyId) {
        Object created = entityManager
                .createNativeQuery("SELECT tcms_grant_company_read_baseline(CAST(:companyId AS uuid))")
                .setParameter("companyId", companyId.toString())
                .getSingleResult();
        return ((Number) created).intValue();
    }

    private ProjectOutcome createFirstProjectIfRequested(
            ProvisionCompanyRequest request, UUID companyId, Instant now) {
        Optional<ProvisionCompanyRequest.FirstProject> requested = request.firstProject();
        if (requested.isEmpty()) {
            // A normal outcome, not a half-provisioned company: project defaults are
            // seeded by project creation too, so the first project can arrive later.
            return new ProjectOutcome(Optional.empty(), false);
        }

        ProvisionCompanyRequest.FirstProject spec = requested.get();
        Optional<Project> existing = projects.findByKey(spec.key());
        if (existing.isPresent()) {
            // Still seed: a project created before a status or the Default configuration
            // was added needs the same top-up the company does.
            callFunction(
                    "SELECT tcms_seed_project_defaults(CAST(:companyId AS uuid), CAST(:projectId AS uuid))",
                    companyId, existing.get().getId());
            return new ProjectOutcome(Optional.of(existing.get().getId()), false);
        }

        UUID projectId = UUID.randomUUID();
        projects.saveAndFlush(
                Project.create(projectId, companyId, spec.key(), spec.name(), null, now));
        callFunction(
                "SELECT tcms_seed_project_defaults(CAST(:companyId AS uuid), CAST(:projectId AS uuid))",
                companyId, projectId);
        return new ProjectOutcome(Optional.of(projectId), true);
    }

    /**
     * Both seed functions return {@code void}, which Postgres delivers as one row holding
     * NULL — so this is a query, not an update, and {@code getResultList()} rather than
     * {@code executeUpdate()}.
     *
     * <p>Both refuse to run unless {@code app.current_company} matches the company they
     * are seeding, and raise SQLSTATE 42501 if it does not. That check is the reason a
     * context bug here surfaces as a loud failure rather than as rows the policy then
     * hides.
     */
    private void callFunction(String sql, UUID companyId, UUID projectId) {
        var query = entityManager.createNativeQuery(sql).setParameter("companyId", companyId.toString());
        if (projectId != null) {
            query.setParameter("projectId", projectId.toString());
        }
        query.getResultList();
    }

    private String describe(ProvisionCompanyRequest request, ProvisionCompanyResult result) {
        // A plain map: the column is jsonb, which stores a normalised object and does not
        // preserve key order, so ordering the map here would only look like it did
        // something. Grouped in provisioning order for the reader of THIS method.
        Map<String, Object> changes = new LinkedHashMap<>();
        changes.put("slug", request.slug());
        changes.put("displayName", request.displayName());
        changes.put("companyCreated", result.companyCreated());
        // The address, not the account id: an audit reader reconstructing "who was made
        // administrator of this client" should not have to join to app_user, and the
        // address is the thing the operator actually typed.
        changes.put("adminEmail", request.adminEmail());
        changes.put("adminUserId", result.adminUserId().toString());
        changes.put("adminUserCreated", result.adminUserCreated());
        changes.put("membershipCreated", result.membershipCreated());
        changes.put("adminGrantCreated", result.adminGrantCreated());
        // How many members gained the company:read baseline on this run — 1 on a fresh company, and
        // the reason an operator can tell a top-up that repaired something from one that did not.
        changes.put("baselineGrantsCreated", result.baselineGrantsCreated());
        result.projectId().ifPresent(id -> changes.put("projectId", id.toString()));
        changes.put("projectCreated", result.projectCreated());
        // Whether an invitation was minted, never the link — the audit trail is read by
        // people who are not the invitee, and a redeemable token in it would let any of
        // them become the client's administrator.
        changes.put("invitationIssued", result.invitationLink().isPresent());
        changes.put("noOp", result.wasNoOp());
        try {
            return json.writeValueAsString(changes);
        } catch (JsonProcessingException e) {
            // A map of strings and booleans cannot fail to serialize; if it somehow does,
            // losing the audit entry is not an acceptable trade for completing the run.
            throw new IllegalStateException("Could not serialize the provisioning audit entry", e);
        }
    }

    private record AdminOutcome(AppUser user, boolean created) {
    }

    private record ProjectOutcome(Optional<UUID> id, boolean created) {
    }
}
