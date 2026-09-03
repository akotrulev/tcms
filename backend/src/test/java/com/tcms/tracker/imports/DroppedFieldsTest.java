package com.tcms.tracker.imports;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link DroppedFields}, and the one claim in it that can be wrong without any gate noticing.
 *
 * <p>No Spring context and no container: this is a pure function of a snapshot and one boolean, and
 * {@code ImportReportMatchesPreviewTest} already proves the preview and the report serve the same
 * list. What that test cannot ask is whether the <em>sentence</em> on a row is true, because it
 * compares the list against itself — so a reason saying the opposite of what the run did passes it
 * every time.
 *
 * <p>Which is exactly what happened. {@code labelPerSprint} kept Azure DevOps' iteration values as
 * labels while the report told the reader {@code Iteration Path} had <em>"no equivalent in this
 * product"</em>, because the condition named Jira's spelling of the field and ADO reports the
 * source's own vocabulary (014 T5's open finding, closed in T8). D-h's whole purpose is that a
 * reader sees both what was lost and what survived it, so this is the test that holds it.
 */
@DisplayName("DroppedFields — the sentence each row will still be showing a year from now")
class DroppedFieldsTest {

    private static SourceProjectSnapshot snapshotOf(SourceField... fields) {
        return new SourceProjectSnapshot(
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(fields), 0);
    }

    private static SourceField field(String name) {
        return new SourceField(name, "picker", false, 1204);
    }

    @Test
    @DisplayName("both sources' sprint field says what was kept when labelPerSprint was taken")
    void bothSpellingsOfTheSprintFieldSayWhatSurvived() {
        SourceProjectSnapshot snapshot = snapshotOf(field("Sprint"), field("Iteration Path"));

        assertThat(DroppedFields.of(snapshot, true))
                .as("""
                    SourceField.name is the SOURCE's vocabulary — Jira writes Sprint and Azure \
                    DevOps writes Iteration Path — and both land on SourceIssue.sprintNames, so \
                    both are kept as labels by the same option and both rows must say so.""")
                .extracting(DroppedFieldView::name, DroppedFieldView::reason)
                .containsExactly(
                        tuple("Sprint", "releases replace sprints (D6); each sprint was kept as a "
                                + "label, which carries no dates, capacity or board"),
                        tuple("Iteration Path", "releases replace sprints (D6); each sprint was "
                                + "kept as a label, which carries no dates, capacity or board"));
    }

    @Test
    @DisplayName("the row stays with the option off, and says nothing was kept in its place")
    void theRowStaysWhenTheOptionWasNotTaken() {
        SourceProjectSnapshot snapshot = snapshotOf(field("Sprint"), field("Iteration Path"));

        assertThat(DroppedFields.of(snapshot, false))
                .as("D-h: an option that removed the row would make the list a function of what "
                        + "was chosen rather than of what was lost")
                .extracting(DroppedFieldView::reason)
                .containsOnly("releases replace sprints (D6); this run kept nothing in their "
                        + "place");
    }

    @Test
    @DisplayName("a custom field is named and reasoned as one, and labelPerSprint does not touch it")
    void aCustomFieldIsUnaffected() {
        SourceProjectSnapshot snapshot =
                snapshotOf(new SourceField("Squad", "picker", true, 137));

        assertThat(DroppedFields.of(snapshot, true))
                .extracting(DroppedFieldView::name, DroppedFieldView::reason)
                .containsExactly(tuple("Custom: \"Squad\"", "no custom fields in this product"));
    }
}
