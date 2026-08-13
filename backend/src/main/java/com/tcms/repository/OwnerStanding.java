package com.tcms.repository;

import jakarta.persistence.EntityManager;
import java.time.Instant;
import org.springframework.stereotype.Component;

/**
 * <strong>The last writerless health signal</strong> — {@link HealthSignal#ORPHANED}, derived from
 * {@code company_membership} and {@code app_user} (plan §6o: <em>"owner deactivated or left the
 * project"</em>).
 *
 * <p>{@code ApprovalFacts}' shape and its module argument, one foundation module over:
 * {@code identity} is an edge this module may take (plan §3, and {@code CaseOwners}' header makes
 * the same crossing for the owner's <em>name</em>), so the single file is a convention rather than
 * a boundary. SQL through the {@link EntityManager} for that class's second reason, which does
 * bind: a JPA entity mapped over {@code company_membership} here would give {@code ddl-auto:
 * validate} two sets of column expectations for a table another module owns.
 *
 * <h2>The decision this signal was waiting for, and it was not a schema question</h2>
 *
 * <p>Every slice from §156 to §180 named this one as needing <em>"a decision about what 'left the
 * project' means"</em> before it could need a statement, and each moved on to a cheaper signal.
 * The decision, taken here:
 *
 * <blockquote>A case is orphaned when it <strong>has</strong> an owner and that owner is no longer
 * somebody this company's administrator would see in its directory — the membership is gone or
 * {@code REMOVED}, or the account behind it is {@code DEACTIVATED}.</blockquote>
 *
 * <p><strong>Membership, not grants — and that is the whole of "left the project".</strong> The
 * tempting reading is the literal one: the owner holds no {@code role_assignment} covering this
 * case's project any more. It is refused on this repository's oldest rule. Answering it means
 * re-deriving project visibility from grants, teams, company-scope roles and SYSTEM-scope
 * assignments — a second implementation of {@code PermissionEvaluator}, in SQL, in a scheduled job,
 * whose disagreement with the real one would surface as a chip nobody can explain. It is also
 * <em>wrong</em> on the two cases that matter most: a Company Admin owns cases in projects they
 * hold no project-scope grant for, and so does an Instance Admin, whose access needs no row in this
 * company at all. Both would be reported as having left a project they administer.
 *
 * <p>Membership answers the same question without asking any of that, because
 * {@code CompanyMembership}'s header already made it the answer: membership is a
 * <strong>precondition</strong> for every permission, so a non-member resolves to the empty
 * permission set <em>regardless of stale grants</em>. {@code MembershipRemovalService} is built on
 * exactly that — offboarding is one status update and every grant the person holds here goes inert
 * without being deleted. So "their grants were revoked" is a state this product does not really
 * have, and "they were offboarded" is one status the derivation can read.
 *
 * <h2>It is the SQL spelling of the Members screen, deliberately</h2>
 *
 * <p>{@code AdminDirectoryService.listUsers} already answers "can this person work here", and its
 * javadoc enumerates the three cases: {@code REMOVED} is <em>absent</em> from the directory,
 * {@code INVITED} reads {@code INVITED} whatever the account says, and otherwise the account's own
 * status shows through — so a deactivated account reads {@code DEACTIVATED}. This predicate is that
 * list, negated: orphaned is <em>absent, or present as deactivated</em>. A second definition of
 * "still here" is a chip on the case grid and a row on the Members screen disagreeing about one
 * person, each internally consistent — §180's defect, which lived only in the transition between
 * two screens.
 *
 * <p><strong>An outstanding invitation is therefore not an orphan</strong>, and that is the one
 * place this predicate is narrower than "not ACTIVE". A case assigned to somebody being onboarded
 * is waiting for them, not abandoned by them; both words this signal renders — "orphaned", "owner
 * has left" — are about a person who <em>was</em> here. It also self-heals the wrong way round: the
 * signal would clear itself when they accept, having spent the onboarding week telling the team
 * their new colleague had left.
 *
 * <h2>An owner is required, so an unowned case is not an orphan</h2>
 *
 * <p>{@code test_case.owner_id} is nullable and plenty of cases carry no owner at all. They are not
 * reported: §6o's two spellings both presuppose an owner, {@code lib/labels.ts} renders words about
 * one, and — the operational half — this is INFO severity on a signal that would otherwise fire on
 * most rows of a young project. {@link HealthSignal#UNLINKED} is the worked example of what that
 * costs: a panel people learn to ignore, and the two ERROR signals go with it. "Nobody owns this"
 * is a real gap and it is the dashboard's <em>by owner</em> breakdown, which shows it as a
 * population rather than as a fault in each case.
 *
 * <h2>The join carries a company predicate and the other statements do not</h2>
 *
 * <p>Every sibling here relies on RLS alone, and this one names {@code m.company_id = c.company_id}
 * as well. Not distrust of RLS — under a set GUC the two are equivalent, because
 * {@code company_membership} is under {@code FORCE ROW LEVEL SECURITY} and only this company's rows
 * are visible. It is <strong>cardinality</strong>: this is the first of the six statements to join
 * a table where one person legitimately has many rows (one membership per client company, plan
 * §6s), so with RLS disabled — which {@code V1}'s own header permits per deployment on a measured
 * planner cost — an owner who belongs to three companies would contribute three copies of every
 * case they own, and {@code ON CONFLICT DO UPDATE} refuses to affect a row twice. The failure is
 * loud rather than silent, which is why this is a note and not the signal's correctness argument;
 * a job that dies in the one configuration nobody tests is still worth one predicate.
 *
 * <p>{@code app_user} needs no such predicate and gets none: it is instance-level, keyed by its
 * primary key here, and outside RLS entirely — the ERD §0.1 exception list, because one person is
 * one account across every client they work for.
 */
@Component
class OwnerStanding {

    private final EntityManager entities;

    OwnerStanding(EntityManager entities) {
        this.entities = entities;
    }

    /**
     * Re-derive {@link HealthSignal#ORPHANED} for every live case in the currently-open company, in
     * one statement.
     *
     * <h2>Three terms, and the one nothing in this product can currently produce is still here</h2>
     *
     * <p>{@code m.user_id IS NULL} (never a member of this company, or the row is gone),
     * {@code m.status = 'REMOVED'} (offboarded — {@code MembershipRemovalService}, an endpoint that
     * exists and is reachable from the Members screen) and {@code u.status = 'DEACTIVATED'} (the
     * instance-wide off switch, {@code AppUser.deactivate}).
     *
     * <p><strong>The third has no production caller.</strong> {@code AppUser.deactivate} is invoked
     * from four tests and from nothing in {@code src/main} — the account lifecycle is a
     * {@code user:manage} screen nobody has built, and §6r's IdP-removed path lands in M4. It is
     * written into the predicate anyway, because the alternative is worse in the direction this
     * repository keeps paying for: a term left out until its writer lands is a signal that silently
     * stops covering a state on the day the state becomes reachable, and nothing fails. A term
     * written now costs one {@code OR} and one fixture, and the fixture is what will still be
     * asserting the right thing when the screen ships.
     *
     * <p><strong>The SPA's words named that unreachable half and only it</strong> —
     * {@code healthLabels.ORPHANED} read "Owner deactivated" and the chip's sentence read "This
     * case's owner has been deactivated", which is precisely the one of the three terms no running
     * deployment can reach. The state a deployment <em>does</em> reach — a contractor offboarded at
     * the end of an engagement — had no words at all. Repaired in the same slice, on the frontend
     * side, because a signal and its sentence are one claim.
     *
     * <h2>{@code ON CONFLICT} names two columns</h2>
     *
     * <p>{@code orphaned} and {@code computed_at}. The row is shared with five other writers and
     * the columns are not — {@code ExecutionFacts} has the account, and
     * {@code CaseHealthRecomputeTest.Boundaries} is what would notice.
     *
     * @param now stamped into {@code computed_at}. With this statement in the job, every column the
     *     server's {@link HealthSignal} names is derived on every tick, so a {@code false} on a
     *     present row finally means what the read path has always assumed it means: a check ran and
     *     found nothing. {@code CaseHealth}'s header carries the account of the years it did not.
     * @return how many cases were re-derived, for the caller's log line.
     */
    int recomputeOrphaned(Instant now) {
        return entities.createNativeQuery("""
                INSERT INTO case_health (
                        case_id, company_id, project_id,
                        orphaned, computed_at)
                SELECT c.id, c.company_id, c.project_id,
                       c.owner_id IS NOT NULL
                           AND (m.user_id IS NULL
                                OR m.status = 'REMOVED'
                                OR u.status = 'DEACTIVATED'),
                       :now
                  FROM test_case c
                  LEFT JOIN company_membership m
                         ON m.user_id = c.owner_id
                        AND m.company_id = c.company_id
                  LEFT JOIN app_user u ON u.id = c.owner_id
                 WHERE c.deleted_at IS NULL
                    ON CONFLICT (case_id) DO UPDATE
                   SET orphaned    = EXCLUDED.orphaned,
                       computed_at = EXCLUDED.computed_at
                """)
                .setParameter("now", now)
                .executeUpdate();
    }
}
