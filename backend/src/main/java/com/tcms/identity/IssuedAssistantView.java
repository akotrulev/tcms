package com.tcms.identity;

/**
 * The connect response, and <strong>the only place an assistant's secret ever appears</strong> —
 * the contract's {@code IssuedAssistant}.
 *
 * <p>A separate type from {@link AssistantView} for {@link IssuedApiTokenView}'s reason, which is
 * worth restating because this surface is the one an ordinary developer uses rather than an
 * administrator: the two could have been one record with a nullable {@code secret} the list leaves
 * null, and that shape is how a secret eventually reaches a response nobody meant it to. Here the
 * {@code POST} returns the type that carries a secret and the list returns the type that cannot.
 *
 * @param secret the plaintext bearer token, shown once and unrecoverable afterwards. Never logged
 *               and never in an audit row ({@code AuditEvent.record}'s own rule).
 */
public record IssuedAssistantView(AssistantView assistant, String secret) {
}
