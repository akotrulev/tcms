package com.tcms.architecture;

import com.tngtech.archunit.core.domain.JavaClass;
import java.util.Set;

/**
 * The two facts both architecture tests are written against, in one place so they cannot
 * drift apart.
 */
final class ArchitectureRules {

    /** The application root package. Modules are its direct sub-packages. */
    static final String ROOT_PACKAGE = "com.tcms";

    /**
     * The three modules a cross-module dependency may point at (plan §3). Everything else
     * crosses via a {@code CompanyScopedEvent}.
     *
     * <p>The list is short for a reason: {@code tenancy} is the isolation boundary,
     * {@code identity} answers "may they?", and {@code project} is the scope almost every
     * permission is expressed against. A module that needs anything else needs an event,
     * because a direct edge would let two feature modules become jointly deployable only
     * — which is the property a modulith exists to keep.
     */
    static final Set<String> FOUNDATION_MODULES = Set.of("tenancy", "identity", "project");

    private ArchitectureRules() {
    }

    /**
     * The module a type belongs to, or {@code null} for a type outside the modules —
     * which means either the composition root {@code com.tcms} itself or a third-party
     * class. Both are legitimately unconstrained: the root wires everything by
     * definition, and a rule about our modules has nothing to say about Spring's.
     */
    static String moduleOf(JavaClass type) {
        String packageName = type.getPackageName();
        if (!packageName.startsWith(ROOT_PACKAGE + ".")) {
            return null;
        }
        String remainder = packageName.substring(ROOT_PACKAGE.length() + 1);
        int dot = remainder.indexOf('.');
        return dot < 0 ? remainder : remainder.substring(0, dot);
    }
}
