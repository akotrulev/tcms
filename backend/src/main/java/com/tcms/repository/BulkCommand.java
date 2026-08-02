package com.tcms.repository;

import java.util.List;
import java.util.UUID;

/**
 * A bulk operation after validation — <strong>the request, with everything decidable already
 * decided</strong>.
 *
 * <h2>Why the untyped payload is parsed into this before anything is applied</h2>
 *
 * <p>{@code BulkPayload} is eight optional members and the action says which are required. Carrying
 * that pair into the apply loop means every one of the eight branches re-asks "is
 * {@code lifecycleStatus} present here", 500 times over, and the branch that forgets applies a
 * default nobody chose. Parsing once at the edge means the loop works on members that are non-null
 * by construction, and the 400 is raised before a transaction is opened.
 *
 * <h2>Sealed, so a ninth action cannot be added quietly</h2>
 *
 * <p>Every consumer switches over this exhaustively, which under {@code -Werror} makes a new record
 * a <em>compile error at each one</em> — the permission resolver, the verdict, the applier, the
 * audit detail. That is the property §61 got for free when {@code PLAN_SCOPE} made
 * {@code canDoOnAuthored}'s switch non-exhaustive and the compiler made every consumer decide what
 * it meant, and it is the reason this is a sealed interface rather than the {@link BulkAction} enum
 * carrying a payload.
 *
 * <h2>Two of these are inside the version content hash, and it is not cosmetic</h2>
 *
 * <p>{@link CaseVersions#hash} digests {@link CaseEdit}, whose members include {@code suiteId} and
 * {@code automationStatus}. So {@link MoveToSuite} and {@link SetAutomationStatus} change
 * <em>authored content</em> and must write a {@code test_case_version} row like any other edit.
 * A bulk path that changed them silently would leave the next single-case save computing a hash
 * that no longer matches the newest stored version — and that save would write a version
 * <strong>attributing the bulk change to whoever saved next</strong>, in the one table whose job is
 * answering who changed what. {@link #changesAuthoredContent()} is what the applier asks.
 *
 * <p>The other five touch {@code lifecycle_status}, {@code owner_id}, {@code case_tag} and
 * {@code case_condition}, none of which {@code CaseEdit} carries, so they correctly write no
 * version. That asymmetry is a fact about what {@code CaseEdit} hashes, not a policy choice, which
 * is why it is asked of the command rather than configured somewhere.
 */
sealed interface BulkCommand {

    /**
     * Move to a lifecycle status.
     *
     * <p>{@code SUBMIT_FOR_REVIEW} parses to this with {@code IN_REVIEW}, so §6g's vocabulary exists
     * on the wire without a second implementation of the state change behind it.
     */
    record SetStatus(LifecycleStatus target) implements BulkCommand {
    }

    /** @param notAutomatableReason non-null exactly when {@code target} is {@code NOT_AUTOMATABLE} */
    record SetAutomationStatus(AutomationStatus target, String notAutomatableReason)
            implements BulkCommand {
    }

    /** @param ownerId null clears the owner — see {@link BulkPayloadInput#ownerId()} */
    record SetOwner(UUID ownerId) implements BulkCommand {
    }

    record MoveToSuite(UUID suiteId) implements BulkCommand {
    }

    /** @param tags normalized, de-duplicated, never empty */
    record AddTags(List<String> tags) implements BulkCommand {
    }

    record AttachCondition(UUID conditionId, ConditionKind kind) implements BulkCommand {
    }

    record DetachCondition(UUID conditionId) implements BulkCommand {
    }

    /**
     * Whether applying this changes a field {@link CaseVersions} hashes, and therefore whether a
     * version row must be written. See the type header — getting this wrong is silent.
     */
    default boolean changesAuthoredContent() {
        return switch (this) {
            case MoveToSuite ignored -> true;
            case SetAutomationStatus ignored -> true;
            case SetStatus ignored -> false;
            case SetOwner ignored -> false;
            case AddTags ignored -> false;
            case AttachCondition ignored -> false;
            case DetachCondition ignored -> false;
        };
    }
}
