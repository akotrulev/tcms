package com.tcms.identity;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /permissions/{permission}/grantees} — who to ask.
 *
 * <p>{@link OrgChartController}'s rule: one path family, one controller, and this is not an admin
 * read. It is what a <strong>refused</strong> screen renders, so it takes no permission of its own
 * beyond company membership — see {@link GranteeLookupService} for why that is the gate and what
 * keeps it from being a directory.
 *
 * <p>No caller parameter and no company parameter. The company comes from
 * {@link com.tcms.tenancy.CompanyContext}, which the transaction manager establishes from the
 * authenticated session; a {@code companyId} parameter here would let request input select the
 * tenancy discriminator, which is the one thing that context exists to prevent (ERD §0.1 point 2).
 */
@RestController
@RequestMapping("/api/v1/permissions")
public class GranteeController {

    private final GranteeLookupService grantees;

    public GranteeController(GranteeLookupService grantees) {
        this.grantees = grantees;
    }

    @GetMapping("/{permission}/grantees")
    public GranteeListView grantees(@PathVariable String permission) {
        return grantees.grantees(permission);
    }
}
