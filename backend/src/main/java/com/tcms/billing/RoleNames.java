package com.tcms.billing;

import com.tcms.identity.Role;
import com.tcms.identity.RoleRepository;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The built-in roles, in both directions — this module's one reader of {@code identity}'s role
 * table.
 *
 * <p>Two consumers need opposite mappings and neither should read the table itself:
 * {@link PersonRoles} has role <em>names</em> from {@code identity}'s own projections and needs ids,
 * because a rate-card entry points at {@code role.id}; {@link EntryLabels} has ids and needs names,
 * because {@code billing.html} renders <em>Test Lead</em> and not a UUID. {@code uq_role_name} is
 * what makes both directions total and unambiguous.
 *
 * <p>{@code role} is instance-level reference data on ERD §0.1's exception list — seven rows, no
 * {@code company_id}, no RLS — so this is a whole-table read and not a per-company one, and it is
 * cheap enough that nothing here caches it. A cache would be the second thing in this codebase to
 * hold a copy of the role catalogue, and the first one is invalidated by
 * {@code PermissionEvaluator}'s own rules.
 */
@Component
@Transactional(readOnly = true)
class RoleNames {

    private final RoleRepository roles;

    RoleNames(RoleRepository roles) {
        this.roles = roles;
    }

    Map<UUID, String> byId() {
        Map<UUID, String> names = new HashMap<>();
        for (Role role : all()) {
            names.put(role.getId(), role.getName());
        }
        return names;
    }

    Map<String, UUID> byName() {
        Map<String, UUID> ids = new HashMap<>();
        for (Role role : all()) {
            ids.put(role.getName(), role.getId());
        }
        return ids;
    }

    private List<Role> all() {
        return roles.findAllByOrderByNameAsc();
    }
}
