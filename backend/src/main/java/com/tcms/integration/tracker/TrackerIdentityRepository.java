package com.tcms.integration.tracker;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code tracker_identity}'s reads and writes.
 *
 * <p>{@code @Transactional(readOnly = true)} for {@link TrackerBindingRepository}'s reason.
 *
 * <h2>Every finder here names {@code userId}, except one — and the exception is the risky one</h2>
 *
 * <p>RLS bounds these queries to the company and no further, so a finder that omits the person is a
 * finder that can return a colleague's credential row. {@link #findByUserIdAndProviderAndBaseUrl}
 * and {@link #findByIdAndUserId} are the shapes every endpoint uses.
 *
 * <p>{@link #findByProviderAndBaseUrlIn} is the one that does not, and it exists solely for
 * {@code GET …/tracker/connected-members} (D19), which answers a <em>state per person per host</em>
 * and must never carry a hint, an account id, an expiry, a scope list or an error string. That
 * endpoint's view is built from this query and closes its own key set in a test, because the natural
 * implementation reuses {@link TrackerIdentityView} and that view has all five.
 */
@Transactional(readOnly = true)
public interface TrackerIdentityRepository extends JpaRepository<TrackerIdentity, UUID> {

    List<TrackerIdentity> findByUserIdOrderByBaseUrlAsc(UUID userId);

    Optional<TrackerIdentity> findByUserIdAndProviderAndBaseUrl(
            UUID userId, TrackerProvider provider, String baseUrl);

    /** The caller's own row, or empty — which is what makes somebody else's id a bodiless 404. */
    Optional<TrackerIdentity> findByIdAndUserId(UUID id, UUID userId);

    /** See the class header: connected-members only, and its response is a closed set of keys. */
    List<TrackerIdentity> findByProviderAndBaseUrlIn(
            TrackerProvider provider, Collection<String> baseUrls);
}
