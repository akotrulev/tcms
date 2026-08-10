package com.tcms.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The one class in the product that turns a caller's bytes into a file, so its refusals are the ones
 * a traversal attempt would meet.
 *
 * <p>No Spring context and no database, deliberately: this is a function from a key to a path, and
 * exercising it through an endpoint would prove the endpoint works without ever saying which half
 * refused — which is the half that matters here.
 *
 * <h2>It was {@code FilesystemEvidenceStoreTest}, and it was the only test either adapter had</h2>
 *
 * <p>{@code FilesystemReportStore} carried the identical body and the identical traversal check,
 * written twice, and <strong>nothing had ever asserted that the second copy refuses anything</strong>
 * — invisible until §125 put the two files in one package. Both facts are now closed by the same
 * three tests, because there is one body; and the two adapters get a delegation test each, because
 * "one body" is a claim about wiring that a copy-paste adapter would silently falsify.
 */
class FilesystemBlobStoreTest {

    @TempDir
    Path root;

    @Test
    @DisplayName("bytes written under a key come back byte-identical, and delete removes them")
    void roundTrips() throws IOException {
        FilesystemBlobStore store = new FilesystemBlobStore(root.toString(), "evidence");
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
        FilesystemBlobStore store = new FilesystemBlobStore(root.toString(), "evidence");

        // Unreachable through the API today: keys are server-generated UUIDs and nothing a client
        // sends reaches this argument. Asserted anyway, because the guarantee currently lives in
        // how EvidenceService and ReportJobRunner BUILD their keys, and a guarantee that lives only
        // in the caller is one refactor from being absent — with no test that would notice.
        assertThatThrownBy(() -> store.put("../escaped", new byte[] {1}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("outside");

        assertThatThrownBy(() -> store.open("../escaped"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a key nothing was written under is absent rather than an empty file")
    void missingKeyIsAbsent() {
        FilesystemBlobStore store = new FilesystemBlobStore(root.toString(), "evidence");
        String key = UUID.randomUUID().toString();

        assertThat(store.exists(key)).isFalse();
        assertThat(store.open(key)).isEmpty();

        // Idempotent: the delete path runs after the rows are gone, so a second attempt at the same
        // evidence must not answer 500 for bytes that are already absent.
        store.delete(key);
    }

    @Test
    @DisplayName("the kind names which root failed — the one thing that must not collapse")
    void theMessageNamesTheKind() {
        // The two roots are configured separately because they have different retention and
        // different loss consequences (FilesystemReportStore's header). An operator reading a
        // failure has to know which of them is the one that is full or gone, and a shared body is
        // exactly where that distinction would quietly disappear into "could not store bytes".
        assertThatThrownBy(() -> new FilesystemBlobStore(root.toString(), "evidence").open(".."))
                .hasMessageContaining("evidence");
        assertThatThrownBy(() -> new FilesystemBlobStore(root.toString(), "report").open(".."))
                .hasMessageContaining("report");
    }

    @Test
    @DisplayName("copy duplicates the bytes and leaves the source alone — §129's clone")
    void copiesBytes() throws IOException {
        FilesystemBlobStore store = new FilesystemBlobStore(root.toString(), "evidence");
        String from = UUID.randomUUID() + "/" + UUID.randomUUID();
        String to = UUID.randomUUID() + "/" + UUID.randomUUID();
        byte[] bytes = "the spec".getBytes(StandardCharsets.UTF_8);

        store.put(from, bytes);
        assertThat(store.copy(from, to)).isTrue();

        try (StoredBlob copy = store.open(to).orElseThrow()) {
            assertThat(copy.stream().readAllBytes()).isEqualTo(bytes);
        }

        // The whole reason a clone copies rather than shares a key: deleting either must not take
        // the other's bytes. A reference copy is indistinguishable from this until somebody deletes.
        store.delete(to);
        assertThat(store.exists(from)).isTrue();
    }

    @Test
    @DisplayName("copying a key that names nothing reports false rather than throwing")
    void copyingAnAbsentKeyIsNotAnError() {
        FilesystemBlobStore store = new FilesystemBlobStore(root.toString(), "evidence");
        String to = UUID.randomUUID().toString();

        // The orphan the rows-first ordering produces on purpose. The caller skips the row instead
        // of creating a second one that also 404s, so this must be an answer and not an exception.
        assertThat(store.copy(UUID.randomUUID().toString(), to)).isFalse();
        assertThat(store.exists(to))
                .as("and nothing was written at the destination on the way to saying no")
                .isFalse();
    }

    @Test
    @DisplayName("copy refuses a traversal on EITHER key — including the destination")
    void copyGuardsBothEnds() {
        FilesystemBlobStore store = new FilesystemBlobStore(root.toString(), "evidence");
        String key = UUID.randomUUID().toString();
        store.put(key, new byte[] {1});

        assertThatThrownBy(() -> store.copy("../escaped", key))
                .isInstanceOf(IllegalArgumentException.class);
        // The half a reader checking only "is the source one of ours" would miss, and the dangerous
        // one: this is the end that WRITES.
        assertThatThrownBy(() -> store.copy(key, "../escaped"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("evidence");
    }

    /**
     * The two adapters are four delegating methods each, and delegation is the kind of thing that is
     * right by inspection and wrong by omission. Driving both through their own port proves the
     * wiring rather than the body — including that each one is rooted where its own property says,
     * which is the failure that would put evidence under the reports root.
     */
    @Nested
    @DisplayName("the adapters delegate to a store rooted at their own property")
    class Adapters {

        @Test
        @DisplayName("evidence")
        void evidenceAdapterRoundTripsAndRefusesTraversal() throws IOException {
            AttachmentStore store = new FilesystemAttachmentStore(root.toString());
            String key = UUID.randomUUID().toString();

            store.put(key, "bytes".getBytes(StandardCharsets.UTF_8));
            assertThat(store.exists(key)).isTrue();
            try (StoredBlob blob = store.open(key).orElseThrow()) {
                assertThat(blob.stream().readAllBytes())
                        .isEqualTo("bytes".getBytes(StandardCharsets.UTF_8));
            }
            store.delete(key);
            assertThat(store.exists(key)).isFalse();

            assertThatThrownBy(() -> store.put("../escaped", new byte[] {1}))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("evidence");

            // The fifth method, and the one only this port has — delegation proven on the adapter
            // that actually exposes it.
            String target = UUID.randomUUID().toString();
            store.put(key, "bytes".getBytes(StandardCharsets.UTF_8));
            assertThat(store.copy(key, target)).isTrue();
            assertThat(store.exists(target)).isTrue();
        }

        @Test
        @DisplayName("reports — never asserted before §125")
        void reportAdapterRoundTripsAndRefusesTraversal() throws IOException {
            ReportStore store = new FilesystemReportStore(root.toString());
            String key = UUID.randomUUID().toString();

            store.put(key, "bytes".getBytes(StandardCharsets.UTF_8));
            assertThat(store.exists(key)).isTrue();
            try (StoredBlob blob = store.open(key).orElseThrow()) {
                assertThat(blob.stream().readAllBytes())
                        .isEqualTo("bytes".getBytes(StandardCharsets.UTF_8));
            }
            store.delete(key);
            assertThat(store.exists(key)).isFalse();

            assertThatThrownBy(() -> store.put("../escaped", new byte[] {1}))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("report");
        }

        @Test
        @DisplayName("raw reports — the third root, §160")
        void rawReportAdapterRoundTripsAndRefusesTraversal() throws IOException {
            RawReportStore store = new FilesystemRawReportStore(root.toString());
            String key = UUID.randomUUID().toString();

            store.put(key, "bytes".getBytes(StandardCharsets.UTF_8));
            assertThat(store.exists(key)).isTrue();
            try (StoredBlob blob = store.open(key).orElseThrow()) {
                assertThat(blob.stream().readAllBytes())
                        .isEqualTo("bytes".getBytes(StandardCharsets.UTF_8));
            }
            store.delete(key);
            assertThat(store.exists(key)).isFalse();

            // "raw report", not "report" — the two roots hold opposite things (one regenerable, one
            // irreproducible) and an operator reading a failure has to be told which is gone.
            //
            // NOTE THE ASYMMETRY, because it is a trap in the sibling test above and not in this
            // one: "report" is a substring of "raw report", so the reports case would still pass if
            // its adapter were handed this label, while this case cannot pass under the sibling's.
            // The one-directional check is the best a substring assertion can do here; what pins
            // the other direction is that the two adapters read two different property keys.
            assertThatThrownBy(() -> store.put("../escaped", new byte[] {1}))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("raw report");
        }
    }
}
