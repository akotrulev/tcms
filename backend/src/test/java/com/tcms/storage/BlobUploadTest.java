package com.tcms.storage;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The upload value object — pure, so no Spring context, no Postgres and no filesystem.
 * {@link FilesystemBlobStoreTest} covers the adapter that writes the bytes; this covers what the
 * record decides about them before it does.
 *
 * <p>Three of the four methods here are the only sanitization between a client-supplied string and
 * a response header or a rendered page, and until this class they were exercised only through the
 * attachment endpoint tests — which upload one well-behaved filename each. The traversal and
 * header-injection shapes had no test at all, and both are the kind that pass silently: a
 * {@code Content-Disposition} with a carriage return in it is a well-formed response.
 */
@DisplayName("BlobUpload — the filename reduction, the header escape, and the key")
class BlobUploadTest {

    private static final UUID COMPANY = UUID.fromString("00000000-0000-0000-0000-0000000000c0");
    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-0000000000e1");

    @ParameterizedTest
    @CsvSource({
        "report.xml,               report.xml",
        "reports/junit/report.xml, report.xml",
        "C:\\Users\\qa\\shot.png,  shot.png",
        "'  spaced.txt  ',         spaced.txt",
    })
    @DisplayName("a filename is reduced to its last segment, under either separator")
    void reducesToTheLastSegment(String raw, String expected) {
        // Both separators, because a Windows client sends backslashes and splitting on '/' alone
        // would keep ..\..\passwd intact — which is the whole point of the backslash row.
        assertThat(BlobUpload.sanitizeFilename(raw)).isEqualTo(expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {"../../../etc/passwd", "..\\..\\..\\windows\\system32\\config\\sam"})
    @DisplayName("a traversal name is reduced to the leaf it was hiding")
    void reducesTraversal(String raw) {
        // This never reached the filesystem — a storage key is built from key() out of
        // server-generated UUIDs — but the string is echoed in a header and rendered in the UI,
        // where it invites the next reader to treat it as a path.
        assertThat(BlobUpload.sanitizeFilename(raw)).doesNotContain("..", "/", "\\");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "reports/", "reports/   "})
    @DisplayName("nothing usable left falls back to a generic name, never to an empty one")
    void fallsBackWhenNothingIsLeft(String raw) {
        // Generic rather than "evidence": a case's unnamed attachment called "evidence" describes
        // the wrong thing to the one person who has no other clue what the file is.
        assertThat(BlobUpload.sanitizeFilename(raw)).isEqualTo("attachment");
    }

    @ParameterizedTest
    @CsvSource({
        "'quote\"name.txt',   quote_name.txt",
        "'back\\slash.txt',   back_slash.txt",
    })
    @DisplayName("a quote or a backslash cannot end the quoted string early")
    void escapesQuotingCharacters(String filename, String expected) {
        assertThat(BlobUpload.headerSafeFilename(filename)).isEqualTo(expected);
    }

    @Test
    @DisplayName("a carriage return or newline cannot inject a header")
    void escapesLineBreaks() {
        // THE assertion of this pair. A filename is the one client-supplied string that reaches a
        // response header, where a CRLF is a second header rather than an odd name.
        String injected = "ok.txt\r\nX-Injected: yes";

        assertThat(BlobUpload.headerSafeFilename(injected))
                .isEqualTo("ok.txt__X-Injected: yes")
                .doesNotContain("\r", "\n");
    }

    @Test
    @DisplayName("the checksum is SHA-256 of the bytes, hex-encoded lowercase")
    void checksumIsSha256() {
        // Known vectors rather than a round-trip through MessageDigest, which would restate the
        // implementation and pass if the algorithm name changed.
        var abc = new BlobUpload("a.txt", "text/plain", 3, "abc".getBytes(StandardCharsets.UTF_8));
        var empty = new BlobUpload("a.txt", "text/plain", 0, new byte[0]);

        assertThat(abc.checksum())
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
        assertThat(empty.checksum())
                .isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
    }

    @Test
    @DisplayName("a key is three server-generated segments and carries nothing the client sent")
    void keyIsAllServerGenerated() {
        String key = BlobUpload.key(COMPANY, OWNER);

        String[] segments = key.split("/");
        assertThat(segments).hasSize(3);
        assertThat(segments[0]).isEqualTo(COMPANY.toString());
        assertThat(segments[1]).isEqualTo(OWNER.toString());
        // The third is a fresh UUID; parsing it is the assertion that it is one.
        assertThat(UUID.fromString(segments[2])).isNotNull();
    }

    @Test
    @DisplayName("two files for the same owner get two keys")
    void keysAreDistinctPerFile() {
        // Without the per-file segment, a second upload against the same owner would overwrite the
        // first in the blob store while both attachment rows still pointed at bytes.
        assertThat(BlobUpload.key(COMPANY, OWNER)).isNotEqualTo(BlobUpload.key(COMPANY, OWNER));
    }
}
