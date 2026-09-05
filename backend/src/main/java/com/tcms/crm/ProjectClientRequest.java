package com.tcms.crm;

import java.util.UUID;

/**
 * The body of {@code PUT /projects/{projectId}/client}.
 *
 * <p><strong>One field, and it is the target state rather than a verb</strong> — the shape
 * {@code ProjectArchiveInput} already uses, for its recorded reason: a pair of attach/detach
 * endpoints would make the client decide which to call from a copy of the state it may have read
 * minutes ago.
 *
 * <p>{@code null} detaches, and is a legitimate target state rather than a clearing operation: a
 * project with no client is internal work, which is the common case (scope D1 rule 1). Because this
 * object has exactly one key, an absent field is an empty body — which no caller sends by accident,
 * and which is the whole reason this is a single-field endpoint rather than a field on the
 * settings document (018 D-d).
 */
public record ProjectClientRequest(UUID clientId) {
}
