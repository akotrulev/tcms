package com.tcms.identity;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * The six built-in roles — seeded reference data, instance-level, never written from
 * application code. Read-only in practice; {@link Role} deliberately offers no factory.
 */
public interface RoleRepository extends JpaRepository<Role, UUID> {

    Optional<Role> findByName(String name);

    java.util.List<Role> findByScope(RoleScope scope);
}
