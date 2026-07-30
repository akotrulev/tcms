package com.tcms.tenancy;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.util.UUID;
import org.hibernate.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.orm.jpa.EntityManagerHolder;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Establishes company isolation at the start of every transaction, in both layers at
 * once: the Hibernate filter (application) and the {@code app.current_company} GUC
 * that the Postgres RLS policies read (database).
 *
 * <p><strong>Why a transaction manager and not a servlet filter.</strong> Both layers
 * are transaction-scoped. {@code SET LOCAL} only survives inside a transaction, and a
 * Hibernate filter is enabled on a {@code Session}, which is bound to one. Setting
 * either from a web filter would miss every non-web entry point — scheduled jobs,
 * {@code @Async} event handlers, the ingestion pipeline, the provisioning CLI —
 * which is precisely where context loss is invisible (plan §0.2). Hooking
 * {@code doBegin} means <em>every</em> transaction is covered, whatever opened it.
 *
 * <h2>No context is a legitimate state, and it must stay safe</h2>
 *
 * <p>When no company is active the GUC is deliberately left unset rather than
 * defaulted. The policies read
 * {@code current_setting('app.current_company', true)::uuid}, where the {@code true}
 * yields NULL for a missing setting and {@code company_id = NULL} is never true — so a
 * context-less transaction sees <strong>zero</strong> rows, not the whole instance.
 * That inversion is silent when wrong, so {@code RlsIsolationTest} asserts it directly.
 *
 * <p>Instance-level tables ({@code company}, {@code app_user}, {@code permission},
 * {@code role}, {@code role_permission}, {@code instance_role_assignment},
 * {@code instance_setting}, {@code instance_audit_event}) carry no RLS, so operator
 * work and the login-time SYSTEM-grant lookup function normally with no context.
 */
public class CompanyAwareTransactionManager extends JpaTransactionManager {

    private static final Logger log = LoggerFactory.getLogger(CompanyAwareTransactionManager.class);

    /**
     * {@code set_config(name, value, is_local=true)} is the function form of
     * {@code SET LOCAL}. Used instead of literal {@code SET LOCAL} because the latter
     * does not accept bind parameters — it would force string interpolation of a value
     * into DDL-ish SQL. The value here is a {@code UUID} rendered by the driver, so it
     * cannot carry SQL, but binding keeps that true by construction rather than by
     * argument.
     */
    private static final String SET_COMPANY_SQL = "SELECT set_config('app.current_company', ?, true)";

    public CompanyAwareTransactionManager(EntityManagerFactory entityManagerFactory) {
        super(entityManagerFactory);
    }

    @Override
    protected void doBegin(Object transaction, TransactionDefinition definition) {
        super.doBegin(transaction, definition);

        UUID companyId = CompanyContext.currentOrNull();
        if (companyId == null) {
            // Instance-level transaction. Leave the GUC unset so RLS yields zero rows
            // on any company-owned table reached from here — see the class comment.
            return;
        }

        Session session = currentSession();
        if (session == null) {
            log.warn("Transaction began with company {} active but no EntityManager was bound; "
                    + "company isolation was NOT applied to this transaction", companyId);
            return;
        }

        session.enableFilter(CompanyFilter.NAME).setParameter(CompanyFilter.PARAMETER, companyId);

        session.doWork(connection -> {
            try (var statement = connection.prepareStatement(SET_COMPANY_SQL)) {
                statement.setObject(1, companyId.toString());
                statement.execute();
            }
        });
    }

    private Session currentSession() {
        Object resource = TransactionSynchronizationManager.getResource(obtainEntityManagerFactory());
        if (!(resource instanceof EntityManagerHolder holder)) {
            return null;
        }
        EntityManager entityManager = holder.getEntityManager();
        return entityManager == null ? null : entityManager.unwrap(Session.class);
    }
}
