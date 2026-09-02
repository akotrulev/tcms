package com.tcms.tracker.imports;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * What an import is allowed to do to this instance — {@code tcms.tracker.imports} (plan 014 D-b).
 *
 * <p>Every value here is defaulted, and the default is the safe one. None of the three is a
 * secret, so none of them is a {@code ${VAR}} with no colon: {@code ${VAR:5000}} is Spring's
 * default syntax, one colon, and {@code ${VAR:-5000}} is the shell's and would bind the literal
 * text {@code -5000}. {@code ConfigConventionTest} reads the shipped text rather than the resolved
 * value, because a test that supplies its own configuration cannot see a defect in the
 * expression.
 *
 * @param maxIssues the ceiling on one source project, because D-b's read pass holds the whole
 *                  snapshot in heap before the write transaction opens. <strong>The preview
 *                  refuses above it, naming the number</strong>, rather than a run dying at minute
 *                  four with nothing written and no sentence anybody can act on.
 * @param readTimeout how long the source system may take to answer one page. The read runs on a
 *                  request thread, so a site that accepts the connection and never answers holds a
 *                  Tomcat worker — {@code TrackerClient}'s argument, and the JDK client's default
 *                  read timeout is indefinite. Longer than that client's ten seconds because a
 *                  hundred-issue Jira page with comments and changelog counts genuinely is slower
 *                  than the one-issue reads it was sized for.
 * @param allowPrivateHosts whether {@link ImportUrlGuard} accepts a destination that resolves to a
 *                  private, loopback, link-local or unique-local address. <strong>False by
 *                  default.</strong> This module's own switch and deliberately not
 *                  {@code tcms.integration.outbound.allow-private-hosts}: that key is unreachable
 *                  from here (D-c), and it also answers a different question — an operator who
 *                  lets a self-hosted Jira Data Center be imported from has said nothing about
 *                  where notification webhooks may post.
 */
@ConfigurationProperties(prefix = "tcms.tracker.imports")
public record ImportProperties(int maxIssues, Duration readTimeout, boolean allowPrivateHosts) {
}
