package com.tcms.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The adapter is the only class in the product that turns a caller's bytes into a file, so its
 * refusals are the ones a traversal attempt would meet.
 *
 * <p>No Spring context and no database, deliberately: this is a function from a key to a path, and
 * exercising it through the endpoint would prove the endpoint works without ever saying which half
 * refused — which is the half that matters here.
 *
 * <p>It moved here from {@code com.tcms.execution} with its subject in §125. <strong>It was the only
 * test either filesystem adapter had</strong> — {@code FilesystemReportStore} has the identical
 * traversal guard, written twice, and nothing has ever asserted that the second copy refuses
 * anything. That is the argument for the second phase of that slice in one sentence, and it is a
 * finding of the move rather than of a review: the two files were in different packages, so nothing
 * put them side by side until they were.
 */
class FilesystemEvidenceStoreTest {

    @TempDir
    Path root;

    @Test
    @DisplayName("bytes written under a key come back byte-identical, and delete removes them")
    void roundTrips() throws IOException {
        FilesystemEvidenceStore store = new FilesystemEvidenceStore(root.toString());
        String key = UUID.randomUUID() + "/" + UUID.randomUUID() + "/" + UUID.randomUUID();
        byte[] bytes = "a screenshot".getBytes(StandardCharsets.UTF_8);

        store.put(key, bytes);

        assertThat(store.exists(key)).isTrue();
        try (StoredBlob blob = store.open(key).orElseThrow()) {
            assertThat(blob.size()).isEqualTo(bytes.length);
            assertThat(blob.stream().readAllBytes()).isEqualTo(bytes);
        }

        store.delete(key);
        assertThat(store.exists(key)).isFalse();
    }

    @Test
    @DisplayName("a key climbing out of the storage root is refused rather than written outside it")
    void refusesTraversal() {
        FilesystemEvidenceStore store = new FilesystemEvidenceStore(root.toString());

        // Unreachable through the API today: keys are three server-generated UUIDs and nothing a
        // client sends reaches this argument. Asserted anyway, because the guarantee currently
        // lives in how EvidenceService BUILDS the key, and a guarantee that lives only in the
        // caller is one refactor from being absent — with no test that would notice.
        assertThatThrownBy(() -> store.put("../escaped", new byte[] {1}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("outside");

        assertThatThrownBy(() -> store.open("../escaped"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a key nothing was written under is absent rather than an empty file")
    void missingKeyIsAbsent() {
        FilesystemEvidenceStore store = new FilesystemEvidenceStore(root.toString());
        String key = UUID.randomUUID().toString();

        assertThat(store.exists(key)).isFalse();
        assertThat(store.open(key)).isEmpty();

        // Idempotent: the delete path runs after the rows are gone, so a second attempt at the
        // same evidence must not answer 500 for bytes that are already absent.
        store.delete(key);
    }
}
