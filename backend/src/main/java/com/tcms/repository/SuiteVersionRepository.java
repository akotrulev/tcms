package com.tcms.repository;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

// A repository interface must be @Transactional or its QUERY METHODS run with no transaction at
// all — and therefore with no app.current_company, which under RLS means zero rows. See
// ProjectRepository's header and RepositoryTransactionTest, which fails the build on a repository
// missing this.
//
// No declared query methods yet, deliberately: nothing reads a suite's composition history, because
// nothing changes a composition after v1 (see SuiteVersions). The annotation is here anyway — it is
// what RepositoryTransactionTest asserts over every repository in the context, and a repository that
// acquires its first query method later must not also need someone to remember this.
@Transactional(readOnly = true)
public interface SuiteVersionRepository extends JpaRepository<SuiteVersion, UUID> {
}
