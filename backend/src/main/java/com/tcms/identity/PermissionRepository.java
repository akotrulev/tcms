package com.tcms.identity;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * The permission catalogue — 55 seeded rows, instance-level. Read through
 * {@link PermissionCatalogue} rather than directly, so the key set is loaded once
 * instead of on every authorization check.
 */
public interface PermissionRepository extends JpaRepository<Permission, String> {

    List<Permission> findByAreaOrderByKeyAsc(String area);
}
