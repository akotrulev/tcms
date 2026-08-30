package com.tcms.tracker;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The depth rule, and the rest of {@link IssueEdit}.
 *
 * <p>No container: {@link IssueEdit} is pure, the caller resolves the two type rows and hands them
 * in, and every branch below is therefore reachable from a plain unit test. That is what made these
 * cases cheap enough to write before the class existed.
 *
 * <p><strong>Every case here is about LEVELS.</strong> {@link #allThreeStoryLevelTypesTakeAnEpicAndHoldTasks}
 * is the one that would need a fourth arm per new type if the rule were written against type names,
 * which is the whole argument for {@code issue_type.level} (scope §4).
 */
@DisplayName("IssueEdit — the depth rule is about levels, never about a type's name")
class IssueEditTest {

    private static final IssueTypeRow EPIC = type("Epic", IssueLevel.EPIC);
    private static final IssueTypeRow STORY = type("Story", IssueLevel.STORY);
    private static final IssueTypeRow BUG = type("Bug", IssueLevel.STORY);
    private static final IssueTypeRow QUICK_TASK = type("Task", IssueLevel.STORY);
    private static final IssueTypeRow SUBTASK = type("Sub-task", IssueLevel.TASK);

    @Test
    void aTaskLevelTypeRefusesToBeCreatedWithoutAParent() {
        // Scope §4: a Task is a breakdown OF something. Story H3's third criterion —
        // "cannot be detached into an orphan" — is this rule read from the other end.
        IssueEdit.Outcome outcome = IssueEdit.validateCreate(input(SUBTASK, null), SUBTASK, null);

        assertThat(errorsOf(outcome)).containsKey("parentKey");
        assertThat(errorsOf(outcome).get("parentKey").getFirst())
                .contains("needs a story-level parent");
    }

    @Test
    void anEpicRefusesAParent() {
        IssueEdit.Outcome outcome = IssueEdit.validateCreate(input(EPIC, "ACME-1"), EPIC, STORY);

        assertThat(errorsOf(outcome)).containsKey("parentKey");
    }

    @Test
    void aStoryLevelTypeMayStandAlone() {
        // Optional, not absent: requiring an epic would force a junk epic per stray
        // bug (scope §4), and story H2's second criterion says a story created
        // without one "is saved without one and appears in the backlog".
        assertThat(IssueEdit.validateCreate(input(STORY, null), STORY, null))
                .isInstanceOf(IssueEdit.Outcome.Valid.class);
    }

    @Test
    void allThreeStoryLevelTypesTakeAnEpicAndHoldTasks() {
        // The whole reason the rule is written against LEVELS and not against type
        // names: this test would need a fourth case per new type if it were.
        for (IssueTypeRow storyLevel : List.of(STORY, BUG, QUICK_TASK)) {
            assertThat(IssueEdit.validateCreate(input(storyLevel, "ACME-1"), storyLevel, EPIC))
                    .as("%s under an epic", storyLevel.name())
                    .isInstanceOf(IssueEdit.Outcome.Valid.class);
            assertThat(IssueEdit.validateCreate(input(SUBTASK, "ACME-9"), SUBTASK, storyLevel))
                    .as("sub-task under %s", storyLevel.name())
                    .isInstanceOf(IssueEdit.Outcome.Valid.class);
        }
    }

    @Test
    void aTaskRefusesAnEpicAsItsParent() {
        // One level above, not "any level above". Skipping the story level is what
        // makes an epic's completion count something other than what it displays.
        IssueEdit.Outcome outcome = IssueEdit.validateCreate(input(SUBTASK, "ACME-1"), SUBTASK, EPIC);

        assertThat(errorsOf(outcome).get("parentKey").getFirst()).contains("one level above");
    }

    @Test
    void startAndTargetDatesAreRefusedOnAnythingButAnEpic() {
        IssueEdit.Outcome outcome = IssueEdit.validateCreate(
                inputWithDates(STORY, LocalDate.of(2026, 7, 1), LocalDate.of(2026, 9, 18)),
                STORY,
                null);

        assertThat(errorsOf(outcome)).containsKeys("startDate", "targetDate");
    }

    @Test
    void aTargetDateBeforeItsStartIsRefused() {
        IssueEdit.Outcome outcome = IssueEdit.validateCreate(
                inputWithDates(EPIC, LocalDate.of(2026, 9, 18), LocalDate.of(2026, 7, 1)),
                EPIC,
                null);

        assertThat(errorsOf(outcome)).containsKey("targetDate");
    }

    @Test
    void aNegativeEstimateIsRefusedAndZeroIsNot() {
        // Zero is a real answer — "I looked, it is nothing" — and refusing it would
        // push people to leave the field empty, which D8 reads as "no estimate" and
        // silently flips the epic's whole basis from ESTIMATE to COUNT.
        assertThat(errorsOf(IssueEdit.validateCreate(estimated(STORY, -1), STORY, null)))
                .containsKey("estimateHours");
        assertThat(IssueEdit.validateCreate(estimated(STORY, 0), STORY, null))
                .isInstanceOf(IssueEdit.Outcome.Valid.class);
    }

    // =====================================================================
    // Fixture
    // =====================================================================

    private static IssueTypeRow type(String name, IssueLevel level) {
        return new IssueTypeRow(UUID.randomUUID(), name, level, level != IssueLevel.EPIC);
    }

    private static IssueInput input(IssueTypeRow type, String parentKey) {
        return new IssueInput(UUID.randomUUID(), type.id(), "A title", null, parentKey,
                null, null, null, null, null, null, null);
    }

    private static IssueInput inputWithDates(IssueTypeRow type, LocalDate start, LocalDate target) {
        return new IssueInput(UUID.randomUUID(), type.id(), "A title", null, null,
                null, null, null, start, target, null, null);
    }

    private static IssueInput estimated(IssueTypeRow type, double hours) {
        return new IssueInput(UUID.randomUUID(), type.id(), "A title", null, null,
                null, null, hours, null, null, null, null);
    }

    private static Map<String, List<String>> errorsOf(IssueEdit.Outcome outcome) {
        assertThat(outcome).isInstanceOf(IssueEdit.Outcome.Invalid.class);
        return ((IssueEdit.Outcome.Invalid) outcome).errors();
    }
}
