package com.tcms.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tcms.tenancy.CompanyContext;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Collection;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Writes a suite's <strong>composition</strong> history — the counterpart to {@link CaseVersions},
 * and <del>deliberately a much smaller one</del> no longer a much smaller one.
 *
 * <h2>~~What this writes today: v1, at creation, and nothing else~~ → superseded, §238</h2>
 *
 * <p>{@code V1}'s comment on {@code suite_version.snapshot} defines a suite version as composition —
 * member cases, order, nesting — and explicitly not the content of its cases. Measured against that
 * definition, of the three writes the M1 slice shipped:
 *
 * <ul>
 *   <li><strong>create</strong> changes a composition (the folder exists, under its parent) — so it
 *       writes <strong>v1</strong>;</li>
 *   <li><strong>rename</strong> changes content, not composition — so it writes <strong>nothing</strong>,
 *       and adding a bump there would start the every-edit-bumps-every-ancestor spiral the schema
 *       comment exists to refuse. <strong>This half is unchanged and still correct;</strong></li>
 *   <li><strong>delete</strong> ends the row's history rather than extending it; the deletion is in
 *       {@code audit_event}, which is the table that answers "who removed this".</li>
 * </ul>
 *
 * <p>~~What owes the later versions~~ was a list of three acts — moving a case between suites,
 * reparenting or reordering a suite, deleting a case out of a suite — with the note that a
 * {@code decide()} sibling of {@link CaseVersions#decide} belonged at each, and that there was none
 * "because a 'has the composition changed' comparison with exactly one caller that always answers
 * yes is a branch no test can make take its other path". <strong>§238 is that second caller and the
 * argument has expired exactly as it was written.</strong> It is struck through rather than deleted
 * because it was right for as long as it stood, and because {@link SuiteVersion#getContentHash()}
 * and {@code ArchitectureRulesTest} each cite it from the other end.
 *
 * <h2>What a version measures: the RESOLVED membership, not the suite's definition</h2>
 *
 * <p>A suite is flat since §98 and its members are three arms — the cases <strong>filed</strong> in
 * it, the cases <strong>added</strong> to it, and every live case carrying one of its
 * <strong>tags</strong> ({@link SuiteMembership}). Only two of those are written on the suite; the
 * third changes when somebody tags a case, with no write to the suite at all.
 *
 * <p>So there were two candidate definitions of "the composition changed", and this snapshots the
 * resolved one. Not because it reads better: because the alternative builds the thing
 * {@link SuiteMembership}'s own header exists to forbid. Snapshotting the <em>definition</em>
 * ({@link SuiteCase} rows plus {@link SuiteTagRule}s) would make this table a second implementation
 * of "in this suite", and it would disagree with the tree's counts, the case grid's filter and a
 * run's scope — each of which resolves the union. <strong>A recorded composition that contradicts
 * {@link SuiteMembership#caseIdsIn} is worse than no recorded composition</strong>, because both
 * screens look right.
 *
 * <p>The consequence is priced rather than hidden. Every act that changes the resolved set has to
 * reach {@link #reconcile}, which means the case write paths — create, edit, clone, delete, bulk —
 * and not only the two on the suite. What that costs is one {@link SuiteMembership#suitesByCase}
 * read taken <em>before</em> the write plus one resolve-and-hash per affected suite; what it does
 * <strong>not</strong> cost is a row per tag edit per suite, because reconciliation runs once per
 * transaction over a set. A bulk retag of 500 cases that three suites' rules match writes three
 * version rows.
 *
 * <h2>Why {@code decide} earns its existence now, and an unconditional bump would be wrong</h2>
 *
 * <p>Not merely noisy — wrong, in three ways a test takes:
 *
 * <ul>
 *   <li>{@link SuiteMembershipWriter#reconcile} is set-semantic and idempotent, so saving the suite
 *       dialog unchanged would mint a version on every save. That is the version-list-as-a-log-of-
 *       who-looked-at-it noise plan §6j added a hash to keep out;</li>
 *   <li>a case edit that changes only the title declares its suites as candidates and must write
 *       nothing;</li>
 *   <li>and the one only a hash over the <em>resolved</em> set gets right — filing a case into a
 *       suite that already held it through a tag rule. The filed arm changed; what the suite holds
 *       did not; there is no new composition.</li>
 * </ul>
 *
 * <h2>The v1 population, and why none of it is backfilled</h2>
 *
 * <p>Two facts about the rows that predate this class's second writer, both left standing:
 *
 * <ul>
 *   <li><strong>Every suite created before {@link SuiteVersions} existed has no version row at
 *       all</strong>, and every suite created after it has a v1 and nothing more. Reviews taken in
 *       that whole period pinned the number 1 — see {@link SuiteStatusService}. Nothing is rewritten:
 *       the pin is not dangling, it points at the only composition ever recorded, and restamping it
 *       would assert an auditor was shown a version that was never written. {@link CaseVersions}'
 *       v1-gap argument, unchanged — history began when it began.</li>
 *   <li><strong>That v1 says the suite was empty, and for some suites it was not.</strong> The
 *       initial write hard-coded {@code Composition(List.of())} under the comment "empty membership,
 *       not unknown: a suite is created before anything can be put in it" — which stopped being true
 *       at §98, when a create began accepting {@code caseIds} and {@code tagRules} in its payload.
 *       So v1 is a false statement for any suite created with members. {@link #writeInitial} takes
 *       the resolved membership now and records the truth going forward; the existing rows are not
 *       corrected, because correcting them needs the create-time membership and that is precisely
 *       what was not recorded.</li>
 * </ul>
 */
@Component
public class SuiteVersions {

    private final SuiteVersionRepository versions;
    private final SuiteRepository suites;
    private final SuiteMembership membership;

    /**
     * The application's configured mapper, injected for {@link CaseVersions}' reason: a private
     * {@code new ObjectMapper()} is a second serialization configuration, and the snapshot drifts
     * from what the API serves the moment either is configured.
     */
    private final ObjectMapper json;

    public SuiteVersions(
            SuiteVersionRepository versions,
            SuiteRepository suites,
            SuiteMembership membership,
            ObjectMapper json) {

        this.versions = versions;
        this.suites = suites;
        this.membership = membership;
        this.json = json;
    }

    /**
     * The composition of one suite at one moment.
     *
     * <p>A record rather than the {@link SuiteView} the API serves, which is the opposite of the
     * choice {@link CaseVersions} makes and for a reason that follows from the definition: the view
     * carries {@code name}, {@code description} and {@code caseCount}, of which the first two are
     * content and the third is a number that changes without this suite changing at all. Snapshotting
     * the view would make the hash move on a rename and on any case edit beneath — which is exactly
     * the coupling the schema comment refuses.
     *
     * @param caseIds  the resolved membership, in {@code key_seq} order — whatever
     *                 {@link SuiteMembership#caseIdsIn} answers, and deliberately nothing else, so
     *                 this table and every screen mean one thing by "in this suite". Empty is a
     *                 value: a suite with no cases is the normal state of a new one, not a missing
     *                 measurement.
     *                 <p>It was preceded by a {@code parentId} — "the nesting, which is the whole
     *                 of a suite's structural position" — until §98 removed the nesting. A suite's
     *                 structural position is now entirely its membership.
     *                 <p><strong>The order is stable but no longer authored.</strong> When suites
     *                 were trees this said "order is content here rather than incidental: a suite
     *                 whose cases were resequenced has a different composition". A flat suite has no
     *                 member order a person can set, so what the hash needs from the ordering is
     *                 only that it not flap — and {@code key_seq} is fixed at creation, so two
     *                 resolutions of an unchanged membership are byte-identical.
     */
    record Composition(List<UUID> caseIds) {
    }

    /**
     * Whether the composition changed, and which number it takes.
     *
     * <p>{@link CaseVersions.Decision}'s shape, and the {@code contentHash} is carried for the same
     * reason: so the write does not recompute it and the two halves cannot disagree.
     */
    record Decision(int versionNo, boolean isNew, String contentHash) {
    }

    /**
     * Writes v1 for a newly created suite, in the caller's transaction.
     *
     * <p>Same transaction as the {@code suite} row and never after it, for {@link CaseVersions}'
     * reason: a committed create whose version row failed to write is a folder whose history has a
     * hole exactly where it began.
     *
     * <p>The membership is <strong>resolved here rather than passed in</strong>, and it must be
     * called after the caller's {@link SuiteMembershipWriter} reconcile: a create carries
     * {@code caseIds} and {@code tagRules} since §98, so a suite can be born with members. Writing
     * {@code Composition(List.of())} — which this did until §238 — is what made v1 a false statement
     * for every such suite. See the class header for why those rows are not corrected.
     */
    void writeInitial(Suite suite, UUID projectId, UUID authorId, Instant now) {
        // Flushed for reconcile's reason: the membership rows the caller just reconciled are
        // pending, and caseIdsIn is native.
        suites.flush();
        Composition composition = new Composition(
                membership.caseIdsIn(CompanyContext.require(), projectId, suite.getId()));

        versions.save(SuiteVersion.of(
                suite.getId(),
                // Not a decide() call, even though there is now a decide() to call. Its answer is
                // currentVersionNo + 1 — which on a fresh row whose column default is 1 is precisely
                // the bug §29 caught on cases, where a created row would take v2 and its v1 would
                // never exist.
                1,
                serialize(composition),
                hash(composition),
                authorId,
                now));
    }

    /**
     * Which suites currently hold these cases — <strong>read before a case write, never after.</strong>
     *
     * <p>This is the half of {@link #reconcile} that cannot be recovered later, and the reason the
     * two calls are separate rather than one. A suite that <em>gains</em> a case can be found
     * afterwards; a suite that <em>loses</em> one — a case refiled elsewhere, untagged, or
     * soft-deleted — cannot, because after the write nothing relates the two rows any more. A caller
     * that skips this reconciles the winners and silently leaves every loser pinned to a composition
     * it no longer has.
     *
     * @param caseIds may be empty, which is the honest input from a create: the case does not exist
     *                yet, so nothing held it and the answer really is nothing
     */
    Set<UUID> holdersBefore(UUID projectId, List<UUID> caseIds) {
        return holders(caseIds);
    }

    /**
     * The case-write form: reconciles every suite that held these cases before the write, plus every
     * suite that holds them now.
     *
     * <p>One method rather than "call {@link #holdersBefore}, call it again, union, reconcile" at
     * each of the five case write paths. The union is the step with a wrong answer that looks right
     * — reconciling only the after-set leaves every suite the case left pinned to a composition it
     * no longer has — so it is taken once, here, rather than five times by hand.
     *
     * @param before what {@link #holdersBefore} answered <em>before</em> the caller mutated anything
     */
    void reconcileAfterCaseWrite(
            UUID projectId,
            Set<UUID> before,
            List<UUID> caseIds,
            UUID authorId,
            Instant now) {

        // Flushed before the after-read for the reason reconcile flushes: the holders query is
        // native, and a case whose new suite_id is still pending would answer with the old one.
        suites.flush();

        Set<UUID> touched = new LinkedHashSet<>(before);
        touched.addAll(holders(caseIds));
        reconcile(projectId, touched, authorId, now);
    }

    /**
     * Advances the composition version of every named suite whose resolved membership has actually
     * changed, in the caller's transaction.
     *
     * <p>Idempotent and order-independent, which is what lets a caller over-declare: a suite handed
     * in that turns out to be unchanged costs one resolve and one hash and writes nothing. That is
     * deliberate — the alternative is each of six call sites working out exactly which suites it
     * moved, and the one that gets it wrong fails silently.
     *
     * <p>Runs once per transaction over a <em>set</em>, so a bulk operation over five hundred cases
     * writes one row per affected suite rather than one per case.
     *
     * @param suiteIds the union of {@link #holdersBefore} and whatever holds the cases now. Suites
     *                 that are gone (soft-deleted, or never in this project) are skipped rather than
     *                 refused: a caller reconciling after a suite delete is asking a reasonable
     *                 question about a folder that no longer holds anything.
     */
    void reconcile(UUID projectId, Collection<UUID> suiteIds, UUID authorId, Instant now) {
        if (suiteIds.isEmpty()) {
            return;
        }
        UUID companyId = CompanyContext.require();

        // Before the first resolve, and it is load-bearing rather than defensive: caseIdsIn is a
        // native query, and the writes it has to see — a moveToSuite, a CaseTags reconcile, a
        // SuiteMembershipWriter reconcile — are pending entity state until something flushes. A
        // resolve taken against an unflushed session answers the membership as it was on the way in,
        // and the version row would record the composition this transaction just replaced.
        suites.flush();

        for (UUID suiteId : suiteIds) {
            suites.findLiveInProject(projectId, suiteId)
                    .ifPresent(suite -> reconcileOne(companyId, projectId, suite, authorId, now));
        }
    }

    private void reconcileOne(
            UUID companyId, UUID projectId, Suite suite, UUID authorId, Instant now) {

        Composition composition =
                new Composition(membership.caseIdsIn(companyId, projectId, suite.getId()));

        Decision decision = decide(suite, composition);
        if (!decision.isNew()) {
            return;
        }

        // The counter and the row together, in one transaction: a suite whose current_version_no
        // names a version row that does not exist is a review pinned to nothing, which is a worse
        // failure than the one this slice set out to fix.
        suite.applyCompositionVersion(decision.versionNo());
        suites.save(suite);
        versions.save(SuiteVersion.of(
                suite.getId(),
                decision.versionNo(),
                serialize(composition),
                decision.contentHash(),
                authorId,
                now));
    }

    /**
     * Whether this composition is a new version of that suite, and which number it takes — the
     * {@link CaseVersions#decide} sibling the old header said belonged here.
     *
     * <p>Compared against the <strong>newest</strong> stored version only, not against every version
     * the suite has had, for {@code CaseVersions}' reason: a membership that came back to what v3
     * held is a real change from v9 and gets its own version. Matching an older hash means the
     * composition returned, not that nothing happened.
     *
     * <p>A suite with no stored version is a change by definition — every suite predating
     * {@link #writeInitial} is in that population, and the honest reading of "nothing to compare
     * against" is that the first thing recorded about it is new. It takes
     * {@code currentVersionNo + 1} like any other, so it becomes v2 and its v1 stays absent, which
     * is the same shape and the same argument as {@code CaseVersions}' v1 gap.
     */
    private Decision decide(Suite suite, Composition composition) {
        String hash = hash(composition);

        Optional<SuiteVersion> newest = versions.findNewestForSuite(suite.getId());
        if (newest.isPresent() && newest.get().getContentHash().equals(hash)) {
            return new Decision(suite.getCurrentVersionNo(), false, hash);
        }
        return new Decision(suite.getCurrentVersionNo() + 1, true, hash);
    }

    private Set<UUID> holders(List<UUID> caseIds) {
        if (caseIds.isEmpty()) {
            return Set.of();
        }
        Set<UUID> suiteIds = new LinkedHashSet<>();
        membership.suitesByCase(CompanyContext.require(), caseIds).values()
                .forEach(suiteIds::addAll);
        // Not filtered to one project here: suitesByCase joins on the case's own project, so every
        // suite it answers with is already in the project the case belongs to. reconcile re-checks
        // it anyway, because that is the query that has a projectId to check against.
        return suiteIds;
    }

    /**
     * A stable digest of the composition — SHA-256 over its canonical JSON.
     *
     * <p>Deterministic because a record serializes its components in declaration order and the one
     * collection in it preserves the order {@link SuiteMembership#caseIdsIn} imposed. See
     * {@link Composition#caseIds()} for why that ordering is stable without being authored.
     */
    private String hash(Composition composition) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(json.writeValueAsBytes(composition)));
        } catch (NoSuchAlgorithmException impossible) {
            // Every JVM is required to provide SHA-256.
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        } catch (JsonProcessingException cannotSerialize) {
            throw new IllegalStateException("A Composition must be serializable", cannotSerialize);
        }
    }

    private String serialize(Composition composition) {
        try {
            return json.writeValueAsString(composition);
        } catch (JsonProcessingException cannotSerialize) {
            throw new IllegalStateException("A Composition must be serializable", cannotSerialize);
        }
    }
}
