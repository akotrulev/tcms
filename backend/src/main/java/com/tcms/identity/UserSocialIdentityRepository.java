package com.tcms.identity;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Instance-level reads over {@code user_social_identity} — no RLS and no company filter, because
 * the login path resolves a subject before any company context can exist (ERD §0.1, {@code V58}).
 */
// See ApiTokenRepository's note: a derived method on an un-annotated repository interface runs
// outside any transaction.
@Transactional(readOnly = true)
public interface UserSocialIdentityRepository extends JpaRepository<UserSocialIdentity, UUID> {

    Optional<UserSocialIdentity> findByProviderAndSubject(SocialProviderKey provider, String subject);

    Optional<UserSocialIdentity> findByProviderAndUserId(SocialProviderKey provider, UUID userId);

    List<UserSocialIdentity> findByUserIdOrderByLinkedAt(UUID userId);

    long countByUserId(UUID userId);
}
