package com.tcms.project;

import com.tcms.api.UserRef;
import java.util.List;
import java.util.UUID;

/**
 * <strong>Who is staffed on this project</strong> — the third port this package declares, and the
 * first that answers about a <em>set</em> of people rather than about one.
 *
 * <h2>Why it is a port and not an import</h2>
 *
 * <p>{@link PeopleDirectory}'s header states the rule and this is the same case: {@code project} may
 * depend on {@code tenancy} alone, and the membership predicate lives in {@code identity} —
 * {@code ProjectMembershipService}, which is <em>active company membership</em> AND <em>a grant on
 * this project</em>, resolved through the permission evaluator so a grant reaching somebody through
 * a team is not silently omitted. Reproducing that predicate anywhere else would be §58's E1: two
 * hand-maintained copies under an instruction to keep them in step.
 *
 * <p>The caller that needed it is {@code integration}'s connected-members list (004 D19), which may
 * depend on {@code project} and may not depend on {@code identity}. So the arrow points the way this
 * package's comment prescribes — <em>"if Modulith ever fails on this edge, the fix is a port, never
 * a wider allowedDependencies"</em>.
 *
 * <h2>It answers about the project, not about the caller</h2>
 *
 * <p>There is no caller id here and there must not be. Whether the person asking may see this list
 * is a permission question their own endpoint has already settled — {@code integration:manage}, for
 * that list — and folding it in would make this port a second place authorization is decided, which
 * is exactly the property {@code ArchitectureRules.FOUNDATION_MODULES} asks a foundation module to
 * keep.
 */
public interface ProjectMemberDirectory {

    /**
     * Every person who may be handed work in this project, by display name.
     *
     * <p>Empty is a real answer: a project created a minute ago by the company's first admin has one
     * member, and a project nobody is staffed on has none. An <em>unreachable</em> project is not
     * distinguishable here, because the caller has already resolved it.
     *
     * @param projectId the project, already resolved by the caller's own gate
     */
    List<UserRef> membersOf(UUID projectId);
}
