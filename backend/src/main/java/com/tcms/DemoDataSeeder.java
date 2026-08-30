package com.tcms;

import com.tcms.identity.InvitationService;
import com.tcms.identity.MembershipInviteService;
import com.tcms.identity.Role;
import com.tcms.identity.RoleGrantRequest;
import com.tcms.identity.RoleGrantService;
import com.tcms.identity.RoleRepository;
import com.tcms.identity.TeamInput;
import com.tcms.identity.TeamMemberInput;
import com.tcms.identity.TeamWriteService;
import com.tcms.identity.UserInvitationRequest;
import com.tcms.project.ProjectArchiveRequest;
import com.tcms.project.ProjectArchiveService;
import com.tcms.project.ProjectCreateService;
import com.tcms.project.ProjectRepository;
import com.tcms.project.ProjectView;
import com.tcms.project.ProjectWriteRequest;
import com.tcms.provisioning.CompanyProvisioningService;
import com.tcms.provisioning.ProvisionCompanyRequest;
import com.tcms.provisioning.ProvisionCompanyResult;
import com.tcms.tenancy.CompanyContext;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Creates the companies, the people and the projects {@link DemoSeedPlan} describes, then hands
 * each project to the content seeders.
 *
 * <p>Everything goes through the same services the API calls — see {@link SeedDemoDataCommand}'s
 * header for why that is the whole point of this command rather than an implementation detail.
 *
 * <h2>Company context is opened around each company, outside every transaction</h2>
 *
 * <p>{@code CompanyAwareTransactionManager} reads {@link CompanyContext} in {@code doBegin}, so a
 * context opened inside a transaction arrives after that transaction has already decided there is
 * no company: the {@code app.current_company} GUC stays unset, every RLS policy evaluates
 * {@code company_id = NULL}, and the seeder writes nothing while reporting success. This is the
 * third operator command to need a company at all, and the trap is the same one
 * {@code ExportCompanyCommand} carries the long form of.
 *
 * <h2>Every person is created by being invited and redeeming it</h2>
 *
 * <p>Not by writing an {@code app_user} with a password hash. The invitation path is what sets
 * {@code status}, {@code accepted_at} and the membership together, and a person assembled around
 * it is a person the sign-in screen may or may not accept — which would make the first thing
 * anybody does with this fixture the thing it gets wrong.
 */
@Component
public class DemoDataSeeder {

    private static final Logger log = LoggerFactory.getLogger(DemoDataSeeder.class);

    private final CompanyProvisioningService provisioning;
    private final MembershipInviteService membershipInvites;
    private final InvitationService invitations;
    private final RoleGrantService roleGrants;
    private final RoleRepository roles;
    private final TeamWriteService teams;
    private final ProjectCreateService projectCreate;
    private final ProjectArchiveService projectArchive;
    private final ProjectRepository projects;
    private final DemoProjectSeeder projectSeeder;

    public DemoDataSeeder(
            CompanyProvisioningService provisioning,
            MembershipInviteService membershipInvites,
            InvitationService invitations,
            RoleGrantService roleGrants,
            RoleRepository roles,
            TeamWriteService teams,
            ProjectCreateService projectCreate,
            ProjectArchiveService projectArchive,
            ProjectRepository projects,
            DemoProjectSeeder projectSeeder) {

        this.provisioning = provisioning;
        this.membershipInvites = membershipInvites;
        this.invitations = invitations;
        this.roleGrants = roleGrants;
        this.roles = roles;
        this.teams = teams;
        this.projectCreate = projectCreate;
        this.projectArchive = projectArchive;
        this.projects = projects;
        this.projectSeeder = projectSeeder;
    }

    /**
     * Seeds every company in the plan.
     *
     * @param force create content for a project that already holds some. Off, a project with
     *              cases is left alone, which is what makes a re-run a top-up rather than a
     *              second helping.
     */
    public DemoSeedReport seed(boolean force) {
        DemoSeedReport report = new DemoSeedReport();
        for (DemoSeedPlan.CompanySpec company : DemoSeedPlan.COMPANIES) {
            seedCompany(company, force, report);
        }
        return report;
    }

    private void seedCompany(
            DemoSeedPlan.CompanySpec spec, boolean force, DemoSeedReport report) {

        String label = spec.displayName() + "  (" + spec.slug() + ")";
        log.info("Seeding {}", label);

        // Provisioning opens its own context and is idempotent, so this both creates the company
        // and tops up one seeded by an earlier release.
        ProvisionCompanyResult provisioned = provisioning.provision(new ProvisionCompanyRequest(
                spec.slug(), spec.displayName(), spec.adminEmail(), spec.adminName(),
                Optional.empty()));

        UUID companyId = provisioned.companyId();
        UUID adminId = provisioned.adminUserId();
        report.line(label, provisioned.companyCreated() ? "company created" : "company already existed");

        // The admin's own invitation, redeemed here rather than by a human clicking a link. An
        // absent link means one was already outstanding or already redeemed; either way the
        // account either has the demo password or has one somebody else set, and overwriting the
        // second would be this command reaching into an account it did not make.
        provisioned.invitationLink()
                .flatMap(DemoDataSeeder::secretIn)
                .ifPresent(secret -> redeem(secret, spec.adminEmail(), report));
        report.credential(spec.adminEmail(), "Company Admin", spec.displayName());

        try (var ignored = CompanyContext.open(companyId)) {
            Map<String, UUID> projectIds = seedProjects(spec, adminId, report, label);
            Map<String, UUID> peopleIds = seedPeople(spec, adminId, projectIds, report, label);
            seedTeams(spec, adminId, peopleIds, report, label);

            for (DemoSeedPlan.ProjectSpec project : spec.projects()) {
                UUID projectId = projectIds.get(project.key());
                if (projectId == null) {
                    continue;
                }
                // Scoped to this project, not one cast for the company: a PROJECT-scope grant
                // names one project, and DemoCast's fallback to the admin is only correct if a
                // person who cannot reach this project is absent from the map rather than in it.
                // Authoring as somebody without the grant is refused NotFound — the project is
                // not visible to them — which is how the plan's tester, granted WEB alone, was
                // silently dropping every third case in MOB.
                DemoCast cast = new DemoCast(
                        companyId, adminId, authorsFor(spec, peopleIds, project.key()));
                projectSeeder.seed(cast, project, projectId, force, report, label);
            }

            // Archiving is LAST: an archived project refuses writes, so a project archived before
            // its content was seeded would be seeded empty and the flag would look like the cause
            // of a bug in the content seeders rather than the order they were called in.
            archiveIfAsked(spec, adminId, projectIds, report, label);
        }
    }

    private Map<String, UUID> seedProjects(
            DemoSeedPlan.CompanySpec spec, UUID adminId, DemoSeedReport report, String label) {

        Map<String, UUID> ids = new LinkedHashMap<>();
        for (DemoSeedPlan.ProjectSpec project : spec.projects()) {
            Optional<UUID> existing = projects.findByKey(project.key()).map(p -> p.getId());
            if (existing.isPresent()) {
                ids.put(project.key(), existing.get());
                continue;
            }

            ProjectCreateService.Outcome outcome = projectCreate.create(adminId,
                    new ProjectWriteRequest(project.key(), project.name(), project.description()));
            switch (outcome) {
                case ProjectCreateService.Outcome.Created created -> {
                    ids.put(project.key(), created.view().id());
                    report.line(label, "project " + project.key() + " — " + project.name());
                }
                default -> report.refusal(
                        "project " + project.key() + " was refused: " + outcome.getClass().getSimpleName());
            }
        }
        return ids;
    }

    /**
     * The people who may author content in one project: those the plan grants it, plus everyone
     * whose grant is company-wide.
     *
     * <p>A person the plan does not place here is left out of the map entirely, so
     * {@link DemoCast}'s lookups fall through to the Company Admin, who holds the company-wide
     * grant and can therefore always write.
     */
    private static Map<String, UUID> authorsFor(
            DemoSeedPlan.CompanySpec spec, Map<String, UUID> peopleIds, String projectKey) {

        Map<String, UUID> scoped = new LinkedHashMap<>();
        for (DemoSeedPlan.Person person : spec.people()) {
            UUID id = peopleIds.get(person.email());
            if (id == null) {
                continue;
            }
            if (person.projectKeys().isEmpty() || person.projectKeys().contains(projectKey)) {
                scoped.put(person.email(), id);
            }
        }
        return scoped;
    }

    /**
     * Invites each person, redeems the invitation with the shared demo password, and makes their
     * grant.
     *
     * <p>The grant is made <em>after</em> the projects exist, because a PROJECT-scope role
     * assignment names a project and one made against a key that has not been created yet is a
     * person who signs in and sees nothing — the failure this fixture is least able to explain to
     * whoever meets it.
     */
    private Map<String, UUID> seedPeople(
            DemoSeedPlan.CompanySpec spec,
            UUID adminId,
            Map<String, UUID> projectIds,
            DemoSeedReport report,
            String label) {

        Map<String, UUID> ids = new LinkedHashMap<>();
        for (DemoSeedPlan.Person person : spec.people()) {
            MembershipInviteService.Invite invited = membershipInvites.invite(adminId,
                    new UserInvitationRequest(person.email(), person.displayName()));

            UUID userId = switch (invited) {
                case MembershipInviteService.Invite.Invited i -> i.view().user().id();
                case MembershipInviteService.Invite.Unchanged u -> u.view().user().id();
                default -> null;
            };
            if (userId == null) {
                report.note("could not invite " + person.email() + ": "
                        + invited.getClass().getSimpleName());
                continue;
            }
            ids.put(person.email(), userId);

            activate(userId, adminId, person.email(), report);

            grant(person, userId, adminId, projectIds, report, label);
            report.credential(person.email(), person.roleName(), spec.displayName());
        }
        return ids;
    }

    private void grant(
            DemoSeedPlan.Person person,
            UUID userId,
            UUID adminId,
            Map<String, UUID> projectIds,
            DemoSeedReport report,
            String label) {

        Optional<Role> role = roles.findByName(person.roleName());
        if (role.isEmpty()) {
            report.note("no built-in role named '" + person.roleName() + "'");
            return;
        }
        UUID roleId = role.get().getId();

        List<UUID> targets = person.projectKeys().isEmpty()
                ? List.of()
                : person.projectKeys().stream().map(projectIds::get).filter(id -> id != null).toList();

        if (targets.isEmpty()) {
            apply(roleGrants.grant(adminId, new RoleGrantRequest(roleId, userId, null, null)),
                    person, "company-wide", report, label);
            return;
        }
        for (UUID projectId : targets) {
            apply(roleGrants.grant(adminId, new RoleGrantRequest(roleId, userId, null, projectId)),
                    person, "on a project", report, label);
        }
    }

    private void apply(
            RoleGrantService.Grant grant,
            DemoSeedPlan.Person person,
            String scope,
            DemoSeedReport report,
            String label) {

        switch (grant) {
            case RoleGrantService.Grant.Created ignored ->
                    report.line(label, person.displayName() + " — " + person.roleName() + " " + scope);
            case RoleGrantService.Grant.Existing ignored -> {
                // A re-run. Nothing to say.
            }
            default -> report.note("could not grant " + person.roleName() + " to " + person.email()
                    + ": " + grant.getClass().getSimpleName());
        }
    }

    /**
     * One team per company, so the org chart, the team board and the team-scoped grant screens
     * have something in them. A tree rather than a flat list: {@code team.parent_team_id} is the
     * only thing the org chart draws, and a single level renders as a list that happens to be
     * indented.
     */
    private void seedTeams(
            DemoSeedPlan.CompanySpec spec,
            UUID adminId,
            Map<String, UUID> peopleIds,
            DemoSeedReport report,
            String label) {

        UUID quality = createTeam(adminId, new TeamInput("Quality Engineering",
                "Everyone who writes or runs tests here.", null), report);
        if (quality == null) {
            return;
        }
        UUID web = createTeam(adminId, new TeamInput("Storefront Squad",
                "The half of Quality Engineering that owns the web storefront.", quality), report);

        peopleIds.forEach((email, userId) ->
                teams.putMember(adminId, quality, userId, new TeamMemberInput(
                        email.startsWith("lead@"))));
        if (web != null && !peopleIds.isEmpty()) {
            peopleIds.entrySet().stream()
                    .filter(entry -> entry.getKey().startsWith("engineer@")
                            || entry.getKey().startsWith("tester@"))
                    .forEach(entry -> teams.putMember(adminId, web, entry.getValue(),
                            new TeamMemberInput(entry.getKey().startsWith("engineer@"))));
        }
        report.line(label, "teams — Quality Engineering, Storefront Squad");
    }

    private UUID createTeam(UUID adminId, TeamInput input, DemoSeedReport report) {
        TeamWriteService.Outcome outcome = teams.create(adminId, input);
        if (outcome instanceof TeamWriteService.Outcome.Created created) {
            return created.view().id();
        }
        // A re-run: the name is unique per company, so a second create is refused as Invalid.
        report.note("team '" + input.name() + "' was not created ("
                + outcome.getClass().getSimpleName() + ") — it most likely already exists.");
        return null;
    }

    private void archiveIfAsked(
            DemoSeedPlan.CompanySpec spec,
            UUID adminId,
            Map<String, UUID> projectIds,
            DemoSeedReport report,
            String label) {

        for (DemoSeedPlan.ProjectSpec project : spec.projects()) {
            if (!project.archived()) {
                continue;
            }
            UUID id = projectIds.get(project.key());
            if (id == null) {
                continue;
            }
            ProjectArchiveService.Outcome outcome =
                    projectArchive.set(adminId, id, new ProjectArchiveRequest(true));
            if (outcome instanceof ProjectArchiveService.Outcome.Updated updated) {
                ProjectView view = updated.view();
                report.line(label, "project " + view.key() + " archived");
            }
        }
    }

    /**
     * Turns an invited person into an active member, which is what every later write depends on.
     *
     * <p><strong>{@code reissue}, not {@code issueIfPending}.</strong> The line above has just
     * called {@code invite}, which mints an outstanding invitation; {@code issueIfPending} then
     * finds that very invitation redeemable and deliberately answers with <em>no</em> accept URL,
     * because its contract is to leave a live link in somebody's inbox alone. Only a digest of the
     * token is stored, so a URL not returned here cannot be recovered afterwards — the redemption
     * was therefore skipped for every person in every run, and every one of them stayed
     * {@code INVITED}. An invited user is not an active member, so the permission evaluator
     * returns nothing for them, the project is invisible, and every write they author is refused
     * {@code NotFound}: the whole fixture came up empty except the one company with no people in
     * its plan, which falls back to authoring as the admin.
     *
     * <p>Idempotent across runs for the reason {@code reissue} states: it answers
     * {@code IssueOutcome.none()} for a membership that is already active, so a second run
     * activates nobody and replaces no token.
     */
    private void activate(UUID userId, UUID adminId, String email, DemoSeedReport report) {
        Optional<String> acceptUrl =
                invitations.reissue(CompanyContext.require(), userId, adminId).acceptUrl();
        if (acceptUrl.isEmpty()) {
            // Already active — the re-run path, and the only one that is silent.
            return;
        }
        Optional<String> secret = acceptUrl.flatMap(DemoDataSeeder::secretIn);
        if (secret.isEmpty()) {
            report.note("could not activate " + email + ": the accept URL carried no token");
            return;
        }
        redeem(secret.get(), email, report);
    }

    /** Redeems one invitation with the shared demo password. */
    private void redeem(String secret, String email, DemoSeedReport report) {
        InvitationService.AcceptOutcome outcome =
                invitations.accept(secret, DemoSeedPlan.PASSWORD);
        if (outcome instanceof InvitationService.Rejected rejected) {
            report.note("could not activate " + email + ": " + rejected.reason() + " — "
                    + rejected.detail());
        }
    }

    /** The single-use token out of an accept URL, which is where the only copy of it lives. */
    private static Optional<String> secretIn(String acceptUrl) {
        String query = URI.create(acceptUrl).getQuery();
        if (query == null) {
            return Optional.empty();
        }
        for (String pair : query.split("&")) {
            if (pair.startsWith("token=")) {
                return Optional.of(pair.substring("token=".length()));
            }
        }
        return Optional.empty();
    }
}
