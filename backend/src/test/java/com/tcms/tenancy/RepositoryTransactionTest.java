package com.tcms.tenancy;

import static org.assertj.core.api.Assertions.assertThat;

import com.tcms.IntegrationTestBase;
import com.tcms.project.Project;
import com.tcms.project.ProjectRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A repository read must see the active company's rows <strong>whether or not the caller
 * already had a transaction open</strong>.
 *
 * <h2>The defect this exists for</h2>
 *
 * <p>Isolation is established in {@link CompanyAwareTransactionManager#doBegin}, so it
 * requires a transaction to exist. Spring Data gives one to the CRUD methods it inherits
 * from {@code SimpleJpaRepository} — {@code findById}, {@code count}, {@code save} — via
 * that class's own {@code @Transactional}. It gives <strong>none</strong> to the methods
 * declared on the repository interface: derived queries and {@code @Query} methods have no
 * implementation method to read an annotation from, so unless the <em>interface</em>
 * carries one, they execute outside any transaction.
 *
 * <p>Which means: no {@code doBegin}, no {@code app.current_company}, and every
 * RLS-protected table answers with <strong>zero rows</strong> — while
 * {@link CompanyContext} sits there holding the right company. Measured, not inferred:
 * before the fix, {@code findByCompanyIdAndUserId} returned empty on a company whose
 * membership row {@code count()} could see from the same thread, one line apart.
 *
 * <p>It fails closed, so it was never a leak. What it is, is the permission evaluator
 * resolving to the empty set for a user who holds grants, a login path that cannot find a
 * membership, and a list screen that renders "no rows" instead of a client's data —
 * every one of which reads as a bug in the feature rather than in the plumbing.
 *
 * <p>It also stayed invisible because the existing suite wraps its reads in an explicit
 * {@code TransactionTemplate}. That is not wrong, but it means the suite was shaped
 * around the defect rather than exposing it. The first test below deliberately does
 * <strong>not</strong> wrap.
 */
@DisplayName("Repository reads reach the company context — with or without a caller's transaction")
class RepositoryTransactionTest extends IntegrationTestBase {

    private static final UUID COMPANY = UUID.fromString("77777777-7777-7777-7777-777777777777");
    private static final UUID PROJECT = UUID.fromString("77770000-0000-4000-8000-000000000001");

    @Autowired
    private ApplicationContext context;

    @Autowired
    private CompanyRepository companies;

    @Autowired
    private ProjectRepository projects;

    @Autowired
    private TransactionTemplate transactions;

    @BeforeEach
    void seed() {
        Instant now = Instant.parse("2026-07-30T00:00:00Z");
        if (companies.findById(COMPANY).isEmpty()) {
            companies.save(Company.provision(COMPANY, "rtx-co", "Repo Tx Co", now));
        }
        try (var ignored = CompanyContext.open(COMPANY)) {
            transactions.executeWithoutResult(status -> {
                if (projects.findById(PROJECT).isEmpty()) {
                    projects.save(Project.create(PROJECT, COMPANY, "RTX", "Repo Tx", null, now));
                }
            });
        }
    }

    @Test
    @DisplayName("a DERIVED query with only CompanyContext open — no transaction of the caller's")
    void derivedQueryWithoutACallerTransaction() {
        try (var ignored = CompanyContext.open(COMPANY)) {
            // Deliberately NOT wrapped in a TransactionTemplate. This is the assertion:
            // if the repository interface loses its @Transactional, this returns empty
            // while the row plainly exists.
            assertThat(projects.findByKey("RTX"))
                    .as("a derived query must open its own transaction, or RLS sees no company "
                            + "and answers with zero rows")
                    .isPresent();
            assertThat(projects.existsByKey("RTX")).isTrue();
            assertThat(projects.findScopedById(PROJECT)).isPresent();
        }
    }

    @Test
    @DisplayName("and still sees nothing when there is genuinely no company — fail closed, not open")
    void staysClosedWithoutACompany() {
        try (var ignored = CompanyContext.clear()) {
            assertThat(projects.findByKey("RTX")).isEmpty();
            assertThat(projects.findAllByOrderByNameAsc()).isEmpty();
        }
    }

    @Test
    @DisplayName("inherited CRUD was never affected — which is why the gap was one-sided and easy to miss")
    void inheritedCrudAlwaysWorked() {
        try (var ignored = CompanyContext.open(COMPANY)) {
            assertThat(projects.count()).isPositive();
            assertThat(projects.findAll()).extracting(Project::getKey).contains("RTX");
        }
    }

    /**
     * The mechanical half. A schema- or catalogue-wide invariant needs a computed check
     * rather than a careful reading (handoff §6 lesson 9) — and "remember to annotate the
     * next repository" is not a mechanism. A repository added a year from now fails the
     * build until it complies.
     */
    @Test
    @DisplayName("EVERY repository interface carries @Transactional, so a new one cannot forget")
    void everyRepositoryIsTransactional() {
        // Derived from the live context rather than from a hand-maintained list, so a
        // repository added later is covered without anyone remembering to add it here.
        List<Class<?>> repositories = java.util.Arrays
                .stream(context.getBeanNamesForType(JpaRepository.class))
                .map(context::getType)
                .filter(java.util.Objects::nonNull)
                // Spring Data's factory bean reports the repository INTERFACE as the bean
                // type; a proxy class would report its interfaces instead, so cover both.
                .flatMap(type -> type.isInterface()
                        ? java.util.stream.Stream.of(type)
                        : java.util.Arrays.stream(type.getInterfaces()))
                .filter(JpaRepository.class::isAssignableFrom)
                .filter(type -> type.getName().startsWith("com.tcms."))
                .distinct()
                .toList();

        assertThat(repositories)
                .as("the probe found no repositories at all, so the assertion below would pass "
                        + "vacuously")
                .hasSizeGreaterThan(5);

        assertThat(repositories)
                .allSatisfy(repository -> assertThat(
                        repository.getAnnotation(Transactional.class))
                        .as("%s must be annotated @Transactional(readOnly = true): without it its "
                                + "derived and @Query methods run outside any transaction, so "
                                + "app.current_company is never set and every RLS-protected read "
                                + "returns nothing", repository.getSimpleName())
                        .isNotNull());
    }
}
