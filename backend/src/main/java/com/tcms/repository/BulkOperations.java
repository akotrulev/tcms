package com.tcms.repository;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Validates a bulk request and says which permission it needs — <strong>the two questions that must
 * be answered before a transaction opens</strong>.
 *
 * <p>Pure and static, like {@link CaseEdit}: no repository, no clock, no context. Everything here is
 * decidable from the request alone, which is what lets a 400 be raised before any row is read and
 * makes the whole class testable without a database.
 *
 * <h2>The permission depends on the argument, and that is the point of this class</h2>
 *
 * <p>See {@link #permission}. It is the reason parsing and gating are one file rather than the
 * controller resolving a constant.
 */
final class BulkOperations {

    /**
     * The permission every bulk action needs, except the one that needs more.
     *
     * <p>Not {@code project:read}. Every PROJECT-scope built-in role holds that — it is what makes a
     * granted project visible — so a write gated on it is a write a Viewer may perform
     * ({@link com.tcms.project.ProjectAccess#canDo}).
     */
    private static final String EDIT_CASE = "case:edit";

    /**
     * The single gate on the review state machine (plan §6g), and the reason this endpoint could not
     * be gated on one constant.
     *
     * <p>The roles spec's Review table grants this to Instance Admin, Company Admin and Test Lead
     * and <strong>explicitly not to Test Engineer</strong>, while {@code case:edit} is granted to
     * Test Engineer as well. So a bulk endpoint gated on {@code case:edit} alone would let a Test
     * Engineer approve two hundred cases at once — silently, because they hold the permission the
     * gate names and the widening is invisible at the gate.
     */
    private static final String APPROVE_CASE = "case:approve";

    /** Matches {@code BulkOperation.caseIds.maxItems}. See the contract for why there is a cap. */
    static final int MAX_SELECTION = 500;

    private BulkOperations() {
    }

    /** Valid, with the parsed command and the de-duplicated selection; or the field errors. */
    sealed interface Outcome {

        record Valid(BulkCommand command, List<UUID> caseIds) implements Outcome {
        }

        /** Field name to messages, so the SPA lands each one on its control. */
        record Invalid(Map<String, List<String>> errors) implements Outcome {
        }
    }

    /**
     * Which permission this command needs on the project.
     *
     * <p><strong>Only the target status can raise it.</strong> Everything else — attaching a
     * condition, moving a suite, assigning an owner — is editing, and editing is producing, which
     * roles spec §A3 deliberately does not restrict for a Test Engineer.
     *
     * <p>The gate asks for one key rather than both {@code case:edit} and {@code case:approve},
     * because every role holding approve also holds edit. That is a property of the seeded
     * catalogue rather than an assumption: {@code SeedDataTest} derives it, so a future role granted
     * approve without edit fails the build here rather than getting a 403 it should not.
     */
    static String permission(BulkCommand command) {
        return switch (command) {
            case BulkCommand.SetStatus status ->
                    status.target() == LifecycleStatus.APPROVED ? APPROVE_CASE : EDIT_CASE;
            case BulkCommand.SetAutomationStatus ignored -> EDIT_CASE;
            case BulkCommand.SetOwner ignored -> EDIT_CASE;
            case BulkCommand.MoveToSuite ignored -> EDIT_CASE;
            case BulkCommand.AddTags ignored -> EDIT_CASE;
            case BulkCommand.AttachCondition ignored -> EDIT_CASE;
            case BulkCommand.DetachCondition ignored -> EDIT_CASE;
        };
    }

    /**
     * The sentence to show a caller this operation refused.
     *
     * <h2>It depends on what the caller CAN do, not only on what they asked for</h2>
     *
     * <p>Two callers are refused an approval for two different reasons, and the advice that helps
     * one misleads the other:
     *
     * <ul>
     *   <li>A <strong>Test Engineer</strong> holds {@code case:edit} and lacks only
     *       {@code case:approve}. "You cannot change cases here" is simply false — they change cases
     *       every day — and would send them to ask for a permission they already hold. What they
     *       need to hear is that approval is a lead's, and that submitting for review is the step
     *       they can take.</li>
     *   <li>A <strong>Viewer</strong> holds neither. Telling them to submit for review sends them to
     *       a second thing they will also be refused, which is worse than saying nothing specific.</li>
     * </ul>
     *
     * <p>Keying this on the command alone gives both callers the engineer's message — the first
     * version of this method did exactly that, and the test asserting the two differ is what caught
     * it. So the caller's own edit rights are the parameter.
     *
     * <p>§31's disable-don't-hide works here as designed, unlike §61's scope refusal:
     * {@code case:approve} is a real key and {@code GET /me/permissions} carries it, so the SPA
     * disables the APPROVED option and this refusal should be unreachable from the product's own UI.
     * It is answered properly anyway, for anyone arriving another way.
     *
     * @param mayEditCases whether this caller holds {@code case:edit} on this project — the fact
     *                     that tells the two refusals apart
     */
    static String refusal(BulkCommand command, boolean mayEditCases) {
        if (mayEditCases
                && command instanceof BulkCommand.SetStatus status
                && status.target() == LifecycleStatus.APPROVED) {
            return "Only a test lead can approve cases. Submit them for review instead.";
        }
        return "You do not have permission to change cases in this project.";
    }

    /**
     * Binds the request to a command, or reports every field that is wrong.
     *
     * <p>Every problem is collected rather than short-circuiting on the first, for
     * {@link CaseEdit#validate}'s reason: a form that reveals one error per round trip trains people
     * to submit repeatedly rather than to read.
     */
    static Outcome parse(BulkOperationRequest request) {
        Map<String, List<String>> errors = new LinkedHashMap<>();

        if (request == null || request.action() == null) {
            // Unrecoverable — every other check below is a question about the action.
            return new Outcome.Invalid(Map.of("action", List.of("Choose an operation.")));
        }

        List<UUID> caseIds = distinct(request.caseIds());
        if (caseIds.isEmpty()) {
            errors.put("caseIds", List.of("Select at least one case."));
        } else if (caseIds.size() > MAX_SELECTION) {
            errors.put("caseIds", List.of(
                    "Select at most " + MAX_SELECTION + " cases at a time. "
                            + "This selection has " + caseIds.size() + "."));
        }

        BulkPayloadInput payload =
                request.payload() == null ? BulkPayloadInput.empty() : request.payload();

        BulkCommand command = bind(request.action(), payload, errors);

        if (!errors.isEmpty()) {
            return new Outcome.Invalid(Map.copyOf(errors));
        }
        // Unreachable with a null command: bind() records an error for every path that returns one.
        return new Outcome.Valid(command, caseIds);
    }

    /**
     * The per-action requirement, in one switch.
     *
     * <p>Returns null exactly when it has recorded an error, which is what {@link #parse} relies on.
     * The pairing is kept honest by the switch being exhaustive: a new action with no arm does not
     * compile, so it cannot reach the null return by omission.
     */
    private static BulkCommand bind(
            BulkAction action, BulkPayloadInput payload, Map<String, List<String>> errors) {

        return switch (action) {
            case SET_STATUS -> {
                if (payload.lifecycleStatus() == null) {
                    errors.put("lifecycleStatus", List.of("Choose a status."));
                    yield null;
                }
                yield new BulkCommand.SetStatus(payload.lifecycleStatus());
            }

            // The alias. No payload is read, so sending one is not an error — the status is implied
            // by the verb, and a client that sends both consistent values is not doing anything
            // wrong. A client that sends a CONTRADICTING lifecycleStatus is, and is refused: the
            // request would otherwise be applied as something other than what it says.
            case SUBMIT_FOR_REVIEW -> {
                if (payload.lifecycleStatus() != null
                        && payload.lifecycleStatus() != LifecycleStatus.IN_REVIEW) {
                    errors.put("lifecycleStatus", List.of(
                            "SUBMIT_FOR_REVIEW always moves cases to In review. "
                                    + "Use SET_STATUS to choose a different status."));
                    yield null;
                }
                yield new BulkCommand.SetStatus(LifecycleStatus.IN_REVIEW);
            }

            case SET_AUTOMATION_STATUS -> {
                if (payload.automationStatus() == null) {
                    errors.put("automationStatus", List.of("Choose an automation status."));
                    yield null;
                }
                String reason = trimToNull(payload.notAutomatableReason());
                // The V1 check constraint, enforced here so it is a field error rather than a
                // DataIntegrityViolationException surfacing as a 500 five hundred rows in.
                if (payload.automationStatus() == AutomationStatus.NOT_AUTOMATABLE
                        && reason == null) {
                    errors.put("notAutomatableReason",
                            List.of("Say why these cases cannot be automated."));
                    yield null;
                }
                yield new BulkCommand.SetAutomationStatus(
                        payload.automationStatus(),
                        // Carried only where the column allows it. Storing a reason beside
                        // AUTOMATED would leave a sentence explaining an impossibility that is not
                        // claimed, which reads as data rather than as residue.
                        payload.automationStatus() == AutomationStatus.NOT_AUTOMATABLE
                                ? reason
                                : null);
            }

            // No null check: a null ownerId is the unassign, not a missing argument.
            case SET_OWNER -> new BulkCommand.SetOwner(payload.ownerId());

            case MOVE_TO_SUITE -> {
                if (payload.suiteId() == null) {
                    errors.put("suiteId", List.of("Choose a suite to move these cases into."));
                    yield null;
                }
                yield new BulkCommand.MoveToSuite(payload.suiteId());
            }

            case ADD_TAGS -> {
                List<String> tags = normalizeTags(payload.tags());
                if (tags.isEmpty()) {
                    errors.put("tags", List.of("Type at least one tag."));
                    yield null;
                }
                yield new BulkCommand.AddTags(tags);
            }

            case ATTACH_CONDITIONS -> {
                if (payload.conditionId() == null) {
                    errors.put("conditionId", List.of("Choose a condition."));
                    yield null;
                }
                yield new BulkCommand.AttachCondition(
                        payload.conditionId(),
                        payload.conditionKind() == null
                                ? ConditionKind.PRE
                                : payload.conditionKind());
            }

            case DETACH_CONDITIONS -> {
                if (payload.conditionId() == null) {
                    errors.put("conditionId", List.of("Choose a condition."));
                    yield null;
                }
                yield new BulkCommand.DetachCondition(payload.conditionId());
            }
        };
    }

    /**
     * The selection, de-duplicated, in the caller's order.
     *
     * <p>Order is the caller's rather than sorted, so the preview lists cases in the order they were
     * selected on screen. De-duplication is unconditional and silent: the same id twice is one case,
     * and reporting it in two buckets — or twice in one — would break the arithmetic the dialog
     * renders. A {@code LinkedHashSet} says both properties in one line.
     */
    private static List<UUID> distinct(List<UUID> ids) {
        if (ids == null) {
            return List.of();
        }
        Set<UUID> seen = new LinkedHashSet<>();
        for (UUID id : ids) {
            if (id != null) {
                seen.add(id);
            }
        }
        return List.copyOf(seen);
    }

    /**
     * Tags trimmed, emptied-to-nothing, de-duplicated, order preserved.
     *
     * <p>Case is <em>not</em> normalized. {@code case_tag} has no collation making
     * {@code @Regression} and {@code @regression} the same row, so folding them here would make the
     * server store something other than what the author typed while the grid still shows both if one
     * arrived by another path.
     */
    private static List<String> normalizeTags(List<String> tags) {
        if (tags == null) {
            return List.of();
        }
        Set<String> seen = new LinkedHashSet<>();
        for (String tag : tags) {
            String trimmed = trimToNull(tag);
            if (trimmed != null) {
                seen.add(trimmed);
            }
        }
        return List.copyOf(seen);
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
