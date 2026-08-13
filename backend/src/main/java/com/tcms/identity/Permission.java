package com.tcms.identity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * One entry in the permission catalogue — seeded reference data, instance-level, keyed
 * by its own string key ({@code case:edit}, {@code run:close}, …).
 *
 * <p><strong>There is no wildcard permission and there must never be one</strong>
 * (plan §6b). Instance Admin's grant is derived by joining the whole catalogue, not by
 * storing a {@code '*'} sentinel: a sentinel cannot distinguish "holds every permission"
 * from "holds a permission that happens to be present", so adding a permission the role
 * did not hold would still render as full coverage. The frontend learned this the
 * expensive way — see {@code frontend/src/lib/permissions.ts} and handoff §6 lesson 10.
 *
 * <p>{@link #area} matches {@code lib/permissions.ts} exactly, because both sides group a
 * 55-item list for humans and two different groupings would be two different products.
 */
@Entity
@Table(name = "permission")
public class Permission {

    @Id
    @Column(name = "key", nullable = false, updatable = false)
    private String key;

    /**
     * The grouping the catalogue is rendered in — and <strong>nothing in this application reads
     * it.</strong> No endpoint serves it, no query selects it, and {@code PermissionRepository}
     * declares no method; the SPA groups from {@code frontend/src/lib/permissions.ts}, whose own
     * copy is exhaustive against the contract by construction and is therefore the stronger of the
     * two. Serving this column would not retire that copy, so the reader is not a wiring fix.
     *
     * <p>In {@code ArchitectureRulesTest.READERLESS_BY_DECISION} since §188. The class header's
     * claim that the two groupings <em>agree</em> is a different question from whether either reads
     * the other, and it is the one worth checking.
     */
    @Column(name = "area", nullable = false)
    private String area;

    @Column(name = "description", nullable = false)
    private String description;

    /** JPA only. */
    protected Permission() {
    }

    public String getKey() {
        return key;
    }

    public String getArea() {
        return area;
    }

    public String getDescription() {
        return description;
    }
}
