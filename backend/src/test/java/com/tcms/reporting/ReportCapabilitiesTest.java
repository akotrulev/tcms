package com.tcms.reporting;

import static org.assertj.core.api.Assertions.assertThat;

import com.tcms.contract.OpenApiContract;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The one copy {@link ReportCapabilitiesView} holds, held to the document it copies.
 *
 * <h2>Why there is a copy at all</h2>
 *
 * <p>The capability answer is "what this server can render <em>and</em> a caller may ask for", and
 * the second half is not derivable on the server. The renderer registry would offer {@code JSON} —
 * the serialized model written for every job, not a choice — and {@link ReportFormat} would offer
 * {@code CSV} and {@code XLSX}, two values {@code V1} named and nothing has ever produced. The set a
 * person may ask for is stated in exactly one place, the contract's {@code format} enum, so the
 * server holds a copy of it.
 *
 * <p>A copy nothing compares is the shape this repository has paid for repeatedly (§205's five
 * cited-but-absent files, §210's copy that named a role, §215's two matrix copies). This is the
 * comparison. It needs no database and no Spring context: two lists, one of them read off disk.
 *
 * <h2>It runs in a container-less test on purpose</h2>
 *
 * <p>Every other test in this package boots Postgres. This one would learn nothing from a database
 * and would cost forty seconds to say so — and a check that is expensive to run is a check somebody
 * eventually runs less often. {@code ../docs/specs} is already a declared input of the {@code test}
 * task (§185), so an edit to the contract re-runs it rather than answering from the Gradle cache.
 */
class ReportCapabilitiesTest {

    /**
     * Where the askable set lives in the document.
     *
     * <p>Matched on the request body's location rather than on the enum's members, so a rename of
     * the operation fails this test instead of silently matching a different enum with the same
     * three values.
     */
    private static final String GENERATE_FORMAT =
            ".paths./projects/{projectId}/reports/generate.post"
                    + ".requestBody.content.application/json.schema.properties.format";

    @Test
    @DisplayName("the server's askable set is the contract's generate enum, both ways")
    void theCopyIsTheContracts() {
        Map<String, List<String>> inline = OpenApiContract.load().inlineEnums();

        assertThat(inline)
                .as("the generate operation's `format` enum has moved — this location is how the "
                        + "server's copy is held to it, so a rename must be followed here")
                .containsKey(GENERATE_FORMAT);

        // Through `of` rather than by reading the constant: the filter is the behaviour, and a
        // supported-everything input is the only way to see the whole askable set through it.
        ReportCapabilitiesView everything =
                ReportCapabilitiesView.of(List.of(ReportFormat.values()));

        assertThat(everything.formats())
                .containsExactlyInAnyOrderElementsOf(inline.get(GENERATE_FORMAT));
    }

    @Test
    @DisplayName("a format with no renderer is not offered, whatever the contract says")
    void onlyWhatIsInstalled() {
        // The other half, and the reason the operation exists: the contract's set is what may be
        // asked for on SOME deployment, and this answer is what may be asked for on THIS one.
        ReportCapabilitiesView installed =
                ReportCapabilitiesView.of(List.of(ReportFormat.MD, ReportFormat.HTML,
                        ReportFormat.JSON));

        assertThat(installed.formats()).containsExactly("HTML", "MD");
    }
}
