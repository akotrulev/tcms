package com.tcms.integration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * What this instance is willing to connect <em>out</em> to — {@code tcms.integration.outbound}
 * (plan 004 D10).
 *
 * <h2>Its own record rather than a component on {@link IntegrationProperties}</h2>
 *
 * <p>The two answer different questions and fail differently. That one is a key with <strong>no
 * default of any kind</strong>, because a missing key means every credential in the instance is
 * unreadable and the only safe behaviour is refusing to start. This one is a policy with a
 * defensible default, and a deployment that never sets it is the ordinary deployment.
 *
 * @param allowPrivateHosts whether {@link OutboundUrlGuard} accepts a destination that resolves to
 *                          a private, loopback, link-local or unique-local address. <strong>False
 *                          by default, and turning it on must be an operator's sentence</strong>: a
 *                          self-hosted Jira Data Center on a private LAN is exactly who sets it,
 *                          and a hostname that resolves to {@code 169.254.169.254} is exactly who
 *                          it is refused for. {@code ${VAR:false}} in {@code application.yml} —
 *                          Spring's default syntax, one colon, not the shell's {@code :-} form
 *                          ({@code ConfigConventionTest} reads the shipped text).
 */
@ConfigurationProperties(prefix = "tcms.integration.outbound")
public record OutboundProperties(boolean allowPrivateHosts) {
}
