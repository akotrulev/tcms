package com.tcms.documentation;

import java.util.UUID;

/**
 * {@code IssueLinkInput} — the body of a case-to-issue link.
 *
 * <p><strong>The caller names a binding and an id, and never a provider or a host.</strong> Those
 * are the server's to state, resolved from the binding through
 * {@code project.TrackerBindingDirectory}, for {@code DefectLink}'s reason applied one table over: a
 * caller-supplied provider is an unverified assertion recorded as a fact.
 *
 * @param issueId the tracker's <strong>stable id</strong> (004 D17) — from the search beside the
 *                form, which is the only place a person sees one. Not a key: a key moves.
 * @param url     whatever the caller pasted or the search returned. A pointer allowed to go
 *                historical, and <strong>no label is ever derived from it</strong>.
 */
public record IssueLinkInput(UUID bindingId, String issueId, String url) {
}
