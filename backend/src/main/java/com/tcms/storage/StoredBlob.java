package com.tcms.storage;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;

/**
 * Bytes on their way out, with the length the response needs in front of them.
 *
 * <p>A stream rather than a {@code byte[]}: a download must not require the whole file in heap, and
 * evidence is the one thing a caller uploads whose size the product does not choose. The size
 * travels alongside because {@code Content-Length} has to be written before the first byte of the
 * body, and asking the stream for it would consume the stream.
 *
 * <p>{@link Closeable} so a caller can use it in try-with-resources and the file handle is not left
 * to the garbage collector — this module's whole reason for existing is the one kind of resource
 * that is not a database connection, and therefore is not closed by anything else.
 */
public record StoredBlob(InputStream stream, long size) implements Closeable {

    @Override
    public void close() throws IOException {
        stream.close();
    }
}
