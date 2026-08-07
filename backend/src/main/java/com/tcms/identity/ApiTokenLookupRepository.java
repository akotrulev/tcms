package com.tcms.identity;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

/**
 * The pre-authentication half of a token lookup — <strong>read with no company context open, and
 * that is the whole reason this type exists.</strong>
 *
 * <p>{@code api_token_lookup} carries no RLS and {@link ApiTokenLookup} carries no company filter,
 * so both layers pass. Every other repository in this module would return zero rows here, which is
 * the correct behaviour for company-owned data and the exact defect {@code V7} shipped.
 *
 * <p>The transaction is still opened by {@code CompanyAwareTransactionManager}; with no context it
 * simply leaves the GUC unset, so any company-owned table reached from this transaction sees
 * nothing. That is the layering, not a gap: this read resolves a company, and the reads that
 * follow it happen inside one.
 *
 * <h2>Revocation does not delete from here, deliberately</h2>
 *
 * <p>The tempting acceleration is to drop the lookup row on revoke so a withdrawn credential stops
 * resolving one step earlier. It is refused because it would make revocation <em>two</em>
 * enforcement points where one suffices, and the second one would not cover expiry — leaving a
 * mechanism that is exercised by one of the two ways a token dies, which is how the unexercised
 * half rots. There is one check, {@link ApiToken#isUsable}, on the policed row, and it answers both.
 * The row here is removed only by {@code V36}'s {@code ON DELETE CASCADE}.
 */
// See ApiTokenRepository's note: a derived method on an un-annotated repository interface runs
// outside any transaction.
@Transactional(readOnly = true)
public interface ApiTokenLookupRepository extends JpaRepository<ApiTokenLookup, String> {

    /**
     * Resolves a presented secret's digest to the company that issued it.
     *
     * <p>Deliberately by digest and never by prefix: the prefix is a partial disclosure meant for a
     * list, and a lookup that accepted it would turn a display field into a credential.
     */
    Optional<ApiTokenLookup> findByTokenHash(String tokenHash);
}
