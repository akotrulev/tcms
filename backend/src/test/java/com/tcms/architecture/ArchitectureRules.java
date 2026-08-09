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
     * The modules a cross-module dependency may point at (plan §3). Everything else
     * crosses via a {@code CompanyScopedEvent}.
     *
     * <p>The list is short for a reason: {@code tenancy} is the isolation boundary,
     * {@code identity} answers "may they?", and {@code project} is the scope almost every
     * permission is expressed against. A module that needs anything else needs an event,
     * because a direct edge would let two feature modules become jointly deployable only
     * — which is the property a modulith exists to keep.
     *
     * <p><strong>{@code storage} is a fourth, added in §125 (decision round 3 #51), and it
     * is the first entry that is not a domain concept.</strong> It owns the bytes that are
     * not in Postgres — evidence and rendered reports — and both {@code execution} and
     * {@code reporting} need them back <em>synchronously, inside a request</em>. A download
     * cannot be an event, so keeping the list at three was not available once the ports
     * moved out of the two feature modules that happened to hold them.
     *
     * <p>Three properties make it a defensible fourth rather than a precedent for a fifth,
     * and a proposed fifth should be held to them: it declares {@code tenancy} <em>alone</em>,
     * so it can never become a second place authorization is decided; it holds no domain
     * concept, so an {@code owner_id} is a {@code UUID} it never resolves; and it answers a
     * question the database cannot, which is the test the other three pass and a feature
     * module proposing itself will not. §76 refused a {@code review} module on exactly this
     * bar — "a fourth foundation module is a decision, not an import" — and the sentence is
     * still the rule; this is the decision.
     *
     * <p><strong>{@code api} is a fifth, added in §146, and it clears that bar in the
     * strongest available form.</strong> It declares <em>nothing</em> — not even
     * {@code tenancy}, which {@code storage} needs because its table is company-owned — so
     * it cannot reach the isolation boundary, let alone become a second place authorization
     * is decided. It holds no domain concept and no persistence: a {@code Problem} is four
     * fields and a map, and the writer beside it turns one into bytes. And the question it
     * answers is not the database's at all but the one underneath {@code storage}'s test —
     * <em>whose is a shape the CONTRACT defines?</em> For eleven slices the answer was
     * "whichever module noticed first, plus a comment explaining that it could not import
     * the last copy", which produced five copies of two records across five modules.
     *
     * <p>Its charter is narrow enough to refuse things, and refusing is the point: <em>a
     * type belongs there only if the contract defines it and more than one module serves
     * it.</em> A helper two modules happen to share is a copy or an event, not an entry
     * here. If this list ever needs a sixth, it needs a paragraph like this one first.
     */
    static final Set<String> FOUNDATION_MODULES =
            Set.of("api", "tenancy", "identity", "project", "storage");

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
