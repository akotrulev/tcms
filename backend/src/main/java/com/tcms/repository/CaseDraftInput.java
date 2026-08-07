package com.tcms.repository;

/**
 * {@code CaseDraftInput} in {@code docs/specs/openapi.yaml} — one autosave.
 *
 * <h2>{@code lockVersion} is required, and it is the field that stops a restore eating someone's
 * edit</h2>
 *
 * <p>It is the {@code lockVersion} of the case the editor was looking at when it started typing —
 * the same value it would send as {@code If-Match} were it saving. Stored with the content and read
 * back by {@link CaseDraftView#stale()}, and without it the buffer has a defect the existing
 * concurrency machinery cannot see:
 *
 * <ol>
 *   <li>Ana opens {@code TC-9} at {@code lockVersion 3} and types. The draft is buffered.</li>
 *   <li>Bob saves {@code TC-9}. It is now at {@code lockVersion 4}.</li>
 *   <li>Ana returns. The editor loads the case fresh — {@code lockVersion 4} — and offers to restore
 *       her draft. She accepts and saves.</li>
 * </ol>
 *
 * <p>The {@code If-Match} on that save is <strong>4</strong>, because it came from the fresh read, so
 * {@link CaseUpdateService}'s stale-editor check passes and Bob's change is silently overwritten by
 * text written before it existed. The check cannot catch this: it compares what the client read
 * against what is stored, and the client did read the current version. What it never asked is what
 * the <em>draft</em> was written against, and that is this field.
 *
 * <p>It is not used as a precondition on the autosave itself. A draft written against a stale
 * version is still the author's work and refusing to buffer it would discard exactly the keystrokes
 * this table exists to keep. The value is recorded and reported; the decision is the returning
 * editor's.
 *
 * <h2>{@code content} is a {@link CaseUpdateRequest} and is NOT validated</h2>
 *
 * <p>Typed, so the buffer is a case-shaped thing rather than a general-purpose blob store that
 * anyone holding {@code case:edit} can write to — and so the restored value is exactly what the
 * {@code PATCH} takes, which is what makes "restore" mean "put this back in the form".
 *
 * <p>But nothing in it is checked. A draft is half-written by definition: the title may be blank,
 * a step may name a shared step that has since been retired, {@code automationKey} may collide with
 * another case's. {@link CaseEdit} refuses all three on save and must — validating them here would
 * make autosave fail while somebody is mid-sentence, which is the one thing a keystroke buffer may
 * never do. The consequence is stated rather than hidden: <strong>a draft that restores cleanly can
 * still be refused when the user presses Save</strong>, and that refusal is the ordinary 400 with
 * its field errors.
 */
public record CaseDraftInput(Long lockVersion, CaseUpdateRequest content) {
}
