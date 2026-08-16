package com.tcms.repository;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

// A repository interface must be @Transactional or its QUERY METHODS run with no transaction at
// all — and therefore with no app.current_company, which under RLS means zero rows. See
// ProjectRepository's header and RepositoryTransactionTest, which fails the build on a repository
// missing this.
//
// NOT readOnly, for CaseFlakeRepository's stated reason: this repository's owner writes through it,
// and readOnly = true puts Hibernate in a mode where the flush is skipped — so the write would
// silently do nothing on a path where every assertion about the read still passes.
@Transactional
public interface TestCaseDraftRepository extends JpaRepository<TestCaseDraft, TestCaseDraftId> {

    /**
     * One person's buffered draft on one case, or empty.
     *
     * <p><strong>Empty is the ordinary state of almost every case</strong>, not an edge one: a draft
     * exists only between a keystroke and the next explicit save, so the common answer is nothing.
     * {@link CaseDraftService} therefore serves absence as a <em>204</em> and not a 404 — the case is
     * perfectly readable, it simply has no unsaved work — which is §35's argument for
     * {@code 200 []} on a case with no versions, arriving at a different status code because there
     * is no collection to be empty.
     *
     * <p><strong>Keyed on the reader as well as the case since {@code V64}</strong> (audit D1). A
     * colleague's buffer on the same case is a different row and is not served here, which is what
     * makes the answer to "which draft do I open" one row rather than a choice.
     *
     * <p><strong>Not {@code findById}</strong>, for the reason {@link CaseFlakeRepository} states: a
     * primary-key lookup resolves through the persistence context and Hibernate filters do not apply
     * to that path. RLS still covers it, and using one layer where both are available is how a
     * layered defence quietly becomes a single point.
     */
    @Query("""
            SELECT d FROM TestCaseDraft d
             WHERE d.caseId = :caseId AND d.userId = :userId
            """)
    Optional<TestCaseDraft> findScopedById(
            @Param("caseId") UUID caseId, @Param("userId") UUID userId);

    /**
     * Drops one person's buffer on a case — the {@code clears the row} half of V1's comment on the
     * table.
     *
     * <h2>The caller's own row, and never anybody else's ({@code V64}, audit D1)</h2>
     *
     * <p>~~{@link CaseUpdateService} clears the buffer regardless of who saved, because the row is
     * per case.~~ It clears the <em>saver's</em> buffer now. A colleague's draft survives their
     * colleague's save and comes back marked stale, which is the whole of what D1 bought: the
     * previous shape discarded unsaved work belonging to somebody who was not in the room.
     *
     * <h2>A bulk delete rather than {@code delete(entity)}, and the reason is the caller</h2>
     *
     * <p>{@link CaseUpdateService} calls this on every successful save, and the overwhelmingly common
     * case is that there is no draft at all. Loading an entity to discover it does not exist and then
     * deleting nothing is two round trips to do nothing; this is one statement that usually affects
     * zero rows, on the hottest write path in the product.
     *
     * <p>It is also the reason there is no {@code @Version} to lose: the row is a buffer, so deleting
     * one that a concurrent autosave has just rewritten is the correct outcome rather than a race —
     * the person pressing Save is the one whose intent wins, and what they save is what the editor
     * sent, not what the buffer held.
     *
     * <p>{@code clearAutomatically} is deliberately <strong>not</strong> set. The persistence context
     * in this transaction has no reason to hold a draft — nothing in the save path loads one — so
     * clearing it would detach the case and its steps mid-transaction, which is exactly the
     * surprising action-at-a-distance that makes {@code @Modifying} on a shared transaction risky.
     *
     * @return how many rows went, so a caller can tell a discard that did something from one that
     *         found nothing. {@link CaseDraftService} uses it; {@link CaseUpdateService} ignores it,
     *         because a save with no buffered draft is the normal path and not a finding.
     */
    @Modifying
    @Query("""
            DELETE FROM TestCaseDraft d
             WHERE d.caseId = :caseId AND d.userId = :userId
            """)
    int deleteForCase(@Param("caseId") UUID caseId, @Param("userId") UUID userId);
}
