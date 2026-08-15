package com.tcms.auth;

import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Which company a provider id belongs to, asked before any session or company context exists.
 *
 * <p>Extracted from {@link SsoLoginService} in the LDAP slice, unchanged: both sign-in flows have to
 * ask it and only one of them can own it. {@code tcms_resolve_identity_provider} is V53's function
 * and its header carries the reason it is SECURITY INVOKER — {@code identity_provider} carries FORCE
 * ROW LEVEL SECURITY, so a definer function would be subject to its own policy and return zero rows
 * in production while every superuser-migrated test stayed green (V7's defect, V8's fix).
 *
 * <p><strong>It answers a company and a lifecycle flag, and nothing else.</strong> The directory
 * configuration is read afterwards, through the repository, INSIDE the opened context — widening
 * this to carry it would move an RLS-protected read into the one place RLS is being walked around.
 */
@Component
class ProviderResolution {

    /** {@code enabled} is RETURNED rather than filtered on, so refusal happens in Java (§6r). */
    record Resolved(UUID companyId, String issuerUri, String clientId, boolean enabled) {
    }

    private final EntityManager entityManager;
    private final TransactionTemplate transactions;

    ProviderResolution(EntityManager entityManager, TransactionTemplate transactions) {
        this.entityManager = entityManager;
        this.transactions = transactions;
    }

    Optional<Resolved> forProvider(UUID providerId) {
        List<?> rows = transactions.execute(status -> entityManager
                .createNativeQuery(
                        "SELECT * FROM tcms_resolve_identity_provider(CAST(:providerId AS uuid))")
                .setParameter("providerId", providerId.toString())
                .getResultList());
        if (rows == null || rows.isEmpty()) {
            return Optional.empty();
        }
        Object[] row = (Object[]) rows.getFirst();
        return Optional.of(new Resolved(
                (UUID) row[0], (String) row[1], (String) row[2], (Boolean) row[3]));
    }
}
