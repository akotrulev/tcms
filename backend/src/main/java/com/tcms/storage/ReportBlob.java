package com.tcms.storage;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;

/**
 * Report bytes on their way out, with the length the response needs in front of them.
 *
 * <p>A stream rather than a {@code byte[]}: a download must not require the whole file in heap. A
 * report is smaller than a screenshot today, and "today" is the part that stops being true — an
 * aggregate release report over a year of executions is exactly the artifact whose size the product
 * does not choose either.
 *
 * <p>The size travels alongside because {@code Content-Length} has to be written before the first
 * byte of the body, and asking the stream for it would consume the stream.
 *
 * <p>{@link Closeable} so a caller can use it in try-with-resources and the file handle is not left
 * to the garbage collector.
 *
 * <p><strong>{@link StoredBlob} is this record under another name</strong>, and since §125 they are
 * in the same package with nothing keeping them apart — {@link ReportStore}'s header has the
 * history. Unlike the two ports, this pair has no argument for staying two: a blob is bytes and a
 * length, there is no wrong one to be handed, so the second phase of that slice deletes this record
 * rather than keeping a distinction that only the file names carry.
 */
public record ReportBlob(InputStream stream, long size) implements Closeable {

    @Override
    public void close() throws IOException {
        stream.close();
    }
}
