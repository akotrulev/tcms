package com.tcms.identity;

/**
 * The creation response, and <strong>the only place the secret ever appears</strong>.
 *
 * <p>A separate type from {@link ApiTokenView} on purpose. The two could have been one record with
 * a nullable {@code secret} that the list leaves null, and that shape is how a secret eventually
 * reaches a response nobody meant it to: every read path would then be one forgotten null away from
 * serving it, and no reviewer could tell by looking at the type whether a given endpoint does.
 * Here the {@code POST} returns the type that carries a secret and every other endpoint returns the
 * type that cannot.
 *
 * @param secret the plaintext, shown once. Unrecoverable afterwards — only its digest is stored, in
 *               {@code api_token_lookup}. <strong>Never log this, and it must never reach an audit
 *               row</strong> ({@code AuditEvent.record}'s own rule: the trail is readable by the
 *               client's admins).
 */
public record IssuedApiTokenView(ApiTokenView token, String secret) {
}
