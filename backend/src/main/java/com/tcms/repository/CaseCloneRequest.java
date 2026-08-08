package com.tcms.repository;

import java.util.UUID;

/**
 * {@code TestCaseCloneInput} on the wire — the two things a clone does not take from the source.
 *
 * <h2>Two fields, and the shortness is the design</h2>
 *
 * <p>Everything else about the new case comes from the one being cloned. A clone request that
 * accepted the editable set would be {@code POST /cases} with an extra id, and the two would then be
 * two answers to "what may a client set on a new case" — free to disagree, which is exactly the
 * shape {@link CaseCreateService}'s header refuses one level down for validation.
 *
 * <h2>{@code title} is required and the server does not default it</h2>
 *
 * <p>No {@code "Copy of " + title} here. Two rows a reader cannot tell apart in a list a week later
 * is the failure that default ships, and the prefix's author also has to decide what happens at the
 * length limit — silently truncating a title, or refusing a clone for a reason the caller did not
 * cause. The SPA prefills the suggestion in its dialog, where a person sees it and can change it
 * before it becomes a row.
 *
 * <p>It is not validated here either: it goes through {@link CaseEdit#validate} with the create
 * path's every other field, so "what is an acceptable title" keeps having one answer.
 *
 * @param suiteId where the clone lands, or null for <strong>the source's suite</strong> — what
 *                "clone this" means with no further instruction. A suite in another project fails
 *                the create path's own live-suite check as a field error; there is no cross-project
 *                clone, because that needs the classification revalidated against the target's
 *                vocabularies (§6d) and is its own slice.
 */
public record CaseCloneRequest(String title, UUID suiteId) {
}
