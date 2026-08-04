package com.tcms.reporting;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.springframework.stereotype.Component;

/**
 * {@link ReportDocument} → JSON. <strong>Not a debug convenience</strong>: slice-plan decision F makes
 * this artifact what the on-screen report view (F2) reads, so the screen and the export are the same
 * model rendered twice rather than two computations that agree until they do not.
 *
 * <p>Every job therefore writes two artifacts — the format that was asked for, and this one — which
 * is why {@code V27} widened {@code report_artifact.format}'s CHECK to admit {@code 'JSON'}.
 *
 * <h2>Its own mapper, and that is deliberate</h2>
 *
 * <p>Spring Boot has an {@link ObjectMapper} bean and injecting it would be one line shorter. It is
 * configured for the <em>web layer</em> — the shape of every REST response — and a stored artifact
 * is not a response: it is a file that outlives the deployment that wrote it, that a screen will
 * parse months later, and that §6e requires be reproducible. Sharing the web mapper would make a
 * property-naming or null-inclusion change in the API silently change the bytes of every future
 * report, and leave the ones already on disk in a shape nothing declares any more. The mapper here
 * is small, explicit, and owned by this file.
 *
 * <p>Pretty-printed for the same reason the Markdown is: an artifact a human can open in an editor
 * and diff against last week's is worth more than the bytes it costs, and {@link ReportModel} is a
 * summary rather than a data dump.
 *
 * <p>Nulls are <strong>written</strong>, not omitted. {@code Section.table} being explicitly
 * {@code null} tells a consumer "this section has no table"; an absent key tells it nothing it can
 * distinguish from a producer that did not know about tables. F2 has to branch on exactly that.
 *
 * <h2>The indenter is spelled out because Jackson's default is the platform's</h2>
 *
 * <p>{@code SerializationFeature.INDENT_OUTPUT} alone uses {@link DefaultIndenter}'s
 * {@code SYSTEM_LINEFEED_INSTANCE}, so the same model would serialise to different bytes on the
 * developer's macOS and the deployment's Linux. This project has already lost seven consecutive CI
 * runs to a difference that could not fail on macOS (§58), and here it would be worse than a red
 * build: two artifacts that differ only in line endings are two artifacts, and §6e's determinism
 * bullet is the promise that regenerating an unchanged report reproduces it.
 */
@Component
class JsonReportRenderer implements ReportRenderer {

    private final ObjectWriter writer = writer();

    private static ObjectWriter writer() {
        ObjectMapper mapper = JsonMapper.builder().build();
        DefaultIndenter indenter = new DefaultIndenter("  ", "\n");
        DefaultPrettyPrinter printer = new DefaultPrettyPrinter();
        printer.indentObjectsWith(indenter);
        printer.indentArraysWith(indenter);
        return mapper.writer(printer);
    }

    @Override
    public ReportFormat format() {
        return ReportFormat.JSON;
    }

    @Override
    public String contentType() {
        return "application/json; charset=utf-8";
    }

    @Override
    public String fileExtension() {
        return "json";
    }

    @Override
    public byte[] render(ReportDocument document) {
        try {
            return writer.writeValueAsBytes(document);
        } catch (JsonProcessingException e) {
            // Unreachable for a record of strings and longs, and rethrown rather than swallowed:
            // a job that stored an empty JSON artifact would leave the screen (F2) rendering an
            // empty report beside a Markdown export that is correct, with nothing to explain it.
            throw new IllegalStateException("Report model could not be serialised as JSON", e);
        }
    }
}
