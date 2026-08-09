package com.tcms.provisioning;

import java.util.Set;

/**
 * The operator CLI verbs this jar answers to, in one place.
 *
 * <p>{@code TcmsApplication} asks this before the context exists, to decide whether to run
 * non-web: with the web starter on the classpath the default is a servlet container, and a
 * CLI run would then do its work correctly and never exit — an operator sees a command that
 * "hangs" after succeeding.
 *
 * <p>A set rather than a chain of {@code isRequestedBy} calls in {@code main}: adding a verb
 * and forgetting to extend that chain produces exactly the hang above, on the new command
 * only, which is the kind of defect that is found by an operator rather than by a test.
 */
public final class OperatorCommands {

    static final String PROVISION_COMPANY = "provision-company";
    static final String ISSUE_INVITATION = "issue-invitation";
    static final String GRANT_INSTANCE_ADMIN = "grant-instance-admin";

    /**
     * Package-private rather than private so {@code OperatorCommandsTest} can derive its cases
     * from the set instead of listing the verbs a second time. A test that lists them is a test
     * that is silent about the fourth one — which is the same defect the set replaced.
     */
    static final Set<String> ALL =
            Set.of(PROVISION_COMPANY, ISSUE_INVITATION, GRANT_INSTANCE_ADMIN);

    private OperatorCommands() {
    }

    /** Whether these raw {@code main} arguments invoke any operator command. */
    public static boolean isRequestedBy(String[] args) {
        for (String arg : args) {
            if (ALL.contains(arg)) {
                return true;
            }
        }
        return false;
    }
}
