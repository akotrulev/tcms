package com.tcms.documentation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Validates a page payload, and works out <strong>which blocks keep their ids</strong>.
 *
 * <p>This is the class the module exists around. {@code V1}'s comment on {@code doc_block.id} says
 * the id is immutable and never derived from content, because {@code doc_link} targets
 * {@code (page_id, block_id)} — and the obvious implementation of "save the page" is to delete every
 * block and insert the submitted ones. That implementation passes every test anybody would think to
 * write: the page reads back exactly as submitted, the text is right, the order is right. It also
 * re-issues every id, so every case that verified a paragraph on that page now verifies nothing, and
 * <em>nothing reports it</em> — the links are gone, so there is not even a stale one to find.
 *
 * <p>So identity is decided here, once, from one rule: <strong>a submitted block carrying an id is
 * the block with that id.</strong> Not "the block at that position", not "the block with matching
 * text" — both of those re-identify on edit, which is the failure with extra steps.
 *
 * <h2>The three cases, and why the third is a 400 rather than a create</h2>
 *
 * <ul>
 *   <li><strong>id present and on this page</strong> — rewritten in place. Text, type and depth may
 *       all change; the id does not. Fixing a typo in a heading is the motivating case and it must
 *       cost nothing.</li>
 *   <li><strong>id absent</strong> — a new block. The server issues the id, which is what makes
 *       "the client sent no id" and "this is new" the same fact rather than two that can disagree.</li>
 *   <li><strong>id present and NOT on this page</strong> — refused. Creating a block under the
 *       submitted id instead would mean a client that believes it edited block X has created block
 *       Y, and a link pointing at X now resolves to a block nobody is editing. Silently issuing a
 *       fresh id would be the same thing with the evidence removed.</li>
 * </ul>
 *
 * <p>A block <em>omitted</em> from the payload is deleted. That is the destructive case, and it is
 * the one {@link Reconciliation#removedIds()} exists to hand to the caller so the link check can run
 * before anything is written.
 *
 * <h2>Position is array order, and is never sent</h2>
 *
 * <p>{@code position} is assigned from the submitted order rather than read from the payload. Two
 * clients that disagree about the numbers — one 0-based, one 1-based, one leaving gaps after a
 * delete — produce a page whose blocks reorder themselves on save. Order is a property of the list,
 * and the list is the thing the client actually has.
 */
final class PageContent {

    /**
     * The longest block of text the page will accept.
     *
     * <p>A product rule, not a schema one: the column is JSONB and holds whatever it is given. The
     * cap exists because a block is the unit a case links to, and a 200 kB "paragraph" is somebody
     * pasting a whole document into one block — which produces a page with exactly one thing to link
     * to, defeating the traceability the blocks are for.
     */
    static final int MAX_TEXT = 20_000;

    /** The most blocks one page may hold. Beyond this it is a document set, not a page. */
    static final int MAX_BLOCKS = 500;

    /**
     * The most rows a {@code table} block may carry, header included.
     *
     * <p>Product rules again, and the two bounds have different reasons. A table wider than a dozen
     * columns does not fit any page this product renders and is a data extract rather than a
     * specification; past a hundred rows it is a data <em>set</em>, which belongs in an attachment
     * where it can be downloaded and diffed rather than in a block a case links to.
     */
    static final int MAX_TABLE_ROWS = 100;

    /** The most columns a {@code table} block may carry. See {@link #MAX_TABLE_ROWS}. */
    static final int MAX_TABLE_COLUMNS = 12;

    /**
     * The longest a single cell may be.
     *
     * <p>Deliberately far below {@link #MAX_TEXT} rather than a share of it: a cell longer than a
     * sentence is a paragraph, and a paragraph inside a table is the one thing on this page a case
     * <strong>cannot link to</strong> — {@code doc_link} targets a block, and a cell is not one.
     * The cap is what keeps a requirement from being written where nothing can point at it.
     */
    static final int MAX_CELL = 500;

    private PageContent() {
    }

    /** Sealed so the write service's mapping is exhaustive, like {@code GateResult}. */
    sealed interface Outcome {

        /** Field name, as spelled on {@code DocPageInput}, to its messages. */
        record Invalid(Map<String, List<String>> errors) implements Outcome {
        }

        record Valid(String title, UUID parentId, List<DocBlockInput> blocks) implements Outcome {
        }
    }

    /**
     * Validates a create.
     *
     * @param livePageIds the project's live page ids. A {@code parentId} outside this set is refused
     *                    rather than accepted: an unchecked parent nests the page under another
     *                    project's page, and since every read is scoped by {@code project_id} the
     *                    page would then appear in no tree at all — created successfully and
     *                    immediately invisible. The same defect {@code SuiteEdit} refuses.
     */
    static Outcome validateCreate(DocPageInput input, Set<UUID> livePageIds) {
        Map<String, List<String>> errors = new LinkedHashMap<>();

        String title = validTitle(input, errors);

        UUID parentId = input.parentId();
        if (parentId != null && !livePageIds.contains(parentId)) {
            errors.computeIfAbsent("parentId", key -> new ArrayList<>())
                    .add("That page is not in this project.");
        }

        // On a create every block is new, so an id cannot refer to anything: the page it would
        // belong to does not exist yet. Refused rather than ignored, for the reason the edit refuses
        // an unknown one — a client sending ids here has mistaken a create for an edit, and
        // accepting it would answer 201 to a request that meant to change something else.
        //
        // The empty attachment set is the same fact one field over: a page has no files until it
        // exists, so an image block cannot be created with the page. `onCreate` is what lets the
        // refusal say that rather than reporting a perfectly real attachment id as unknown.
        List<DocBlockInput> blocks = validBlocks(input.blocks(), Set.of(), Set.of(), true, errors);

        return errors.isEmpty()
                ? new Outcome.Valid(title, parentId, blocks)
                : new Outcome.Invalid(errors);
    }

    /**
     * Validates an edit.
     *
     * <p>A differing {@code parentId} is refused rather than ignored, for {@code SuiteEdit}'s
     * reason: reparenting rewrites every descendant's {@code path}, and dropping the field silently
     * lets a client show the page in its new place, reload, and find it back — with the server
     * having reported success.
     *
     * @param currentParentId the page's stored parent, null for a root page
     * @param existingBlockIds every block currently on this page
     * @param pageAttachmentIds every file currently attached to this page. An {@code image} block
     *                         naming anything outside this set is refused, for {@code parentId}'s
     *                         reason: an unchecked id would let a block point at another page's
     *                         file — or at nothing — and every read then fails to resolve it, so
     *                         the block is saved successfully and immediately broken.
     */
    static Outcome validateEdit(
            DocPageInput input,
            UUID currentParentId,
            Set<UUID> existingBlockIds,
            Set<UUID> pageAttachmentIds) {

        Map<String, List<String>> errors = new LinkedHashMap<>();

        String title = validTitle(input, errors);

        UUID parentId = input.parentId();
        if (parentId != null && !parentId.equals(currentParentId)) {
            errors.computeIfAbsent("parentId", key -> new ArrayList<>())
                    .add("Moving a page is not supported yet. Leave the parent unchanged.");
        }

        List<DocBlockInput> blocks =
                validBlocks(input.blocks(), existingBlockIds, pageAttachmentIds, false, errors);

        return errors.isEmpty()
                ? new Outcome.Valid(title, currentParentId, blocks)
                : new Outcome.Invalid(errors);
    }

    /** The one title rule, so create and edit cannot disagree about what a title is. */
    private static String validTitle(DocPageInput input, Map<String, List<String>> errors) {
        String title = input.title() == null ? null : input.title().trim();
        if (title == null || title.isEmpty()) {
            errors.computeIfAbsent("title", key -> new ArrayList<>()).add("Enter a title.");
            return null;
        }
        if (title.length() > DocPage.MAX_TITLE) {
            errors.computeIfAbsent("title", key -> new ArrayList<>())
                    .add("Keep the title under " + DocPage.MAX_TITLE + " characters.");
        }
        return title;
    }

    /**
     * Checks every submitted block, and returns them unchanged when they are all acceptable.
     *
     * <p>Errors are keyed {@code blocks[3].text} rather than {@code blocks}, so a form can land the
     * message on the block that caused it. A page being edited has many blocks and "one of your
     * blocks is wrong" is not a message anybody can act on.
     *
     * @param onCreate true when no id may be present at all, and when the page can have no files
     * @return null when {@code submitted} was null, which means "leave the content alone"
     */
    private static List<DocBlockInput> validBlocks(
            List<DocBlockInput> submitted,
            Set<UUID> existingBlockIds,
            Set<UUID> pageAttachmentIds,
            boolean onCreate,
            Map<String, List<String>> errors) {

        if (submitted == null) {
            return null;
        }
        if (submitted.size() > MAX_BLOCKS) {
            errors.computeIfAbsent("blocks", key -> new ArrayList<>())
                    .add("A page holds at most " + MAX_BLOCKS + " blocks. Split it into child "
                            + "pages.");
            return submitted;
        }

        // Tracks ids seen in THIS payload. The same id twice would otherwise be reconciled into two
        // rewrites of one row, so the second silently wins and one submitted block vanishes.
        Set<UUID> seen = new LinkedHashSet<>();

        for (int index = 0; index < submitted.size(); index++) {
            DocBlockInput block = submitted.get(index);
            String at = "blocks[" + index + "]";

            if (block == null) {
                errors.computeIfAbsent(at, key -> new ArrayList<>()).add("Block is missing.");
                continue;
            }

            if (block.blockType() == null) {
                // Reached both when the field is absent and when DocBlockType.parse rejected the
                // value, which is why that method returns null instead of throwing: the six valid
                // spellings are more useful here than a Jackson parse error.
                errors.computeIfAbsent(at + ".blockType", key -> new ArrayList<>())
                        .add("Choose a block type: heading, paragraph, list, code, table or "
                                + "image.");
            }

            if (block.text() == null) {
                errors.computeIfAbsent(at + ".text", key -> new ArrayList<>()).add("Enter text.");
            } else if (block.blockType() == DocBlockType.IMAGE && block.text().isBlank()) {
                // The ONE type where empty text is refused, and it is refused because the field
                // stops being the block's words and becomes its alt text. An image in a
                // specification is never decorative — it is the wireframe or the data sheet the
                // paragraphs describe — so a blank alt is not "no caption needed", it is the whole
                // block being unreadable to somebody, silently, in the one product surface whose
                // purpose is stating what the system must do.
                errors.computeIfAbsent(at + ".text", key -> new ArrayList<>())
                        .add("Describe what this image shows. A reader who cannot see it has only "
                                + "these words.");
            } else if (block.text().length() > MAX_TEXT) {
                errors.computeIfAbsent(at + ".text", key -> new ArrayList<>())
                        .add("Keep a block under " + MAX_TEXT + " characters. Split it into "
                                + "several blocks so cases can link to the part they verify.");
            }

            // THREE ARMS, NOT TWO — required on a heading, optional on a list, refused on the rest.
            //
            // The middle arm is §93's change. A test plan is mostly a nested scenario outline, three
            // levels deep on most of the handed-over example's pages, and the alternative to a depth
            // field is markers typed into the text ("1." / "a." / "i."). Those cannot be indented,
            // cannot be renumbered, and — the reason that settles it — cannot be LINKED: a doc_link
            // targets a block, so a scenario a case can verify has to be its own block rather than a
            // line inside one. One bullet per block, and this is the field that says how deep.
            if (block.blockType() == DocBlockType.HEADING) {
                if (block.level() == null) {
                    errors.computeIfAbsent(at + ".level", key -> new ArrayList<>())
                            .add("Choose a heading depth between 1 and 6.");
                } else if (block.level() < 1 || block.level() > 6) {
                    errors.computeIfAbsent(at + ".level", key -> new ArrayList<>())
                            .add("Heading depth is between 1 and 6.");
                }
            } else if (block.blockType() == DocBlockType.LIST) {
                // Optional, defaulting to 1 at storage — an unindented bullet is the common case and
                // every list block written before this field existed is one. The bound is checked
                // when it is sent, because a depth of 40 renders off the side of the page and a
                // negative one renders as depth 1 with the author's intent silently lost.
                if (block.level() != null && (block.level() < 1 || block.level() > 6)) {
                    errors.computeIfAbsent(at + ".level", key -> new ArrayList<>())
                            .add("Indent depth is between 1 and 6.");
                }
            } else if (block.level() != null) {
                // Refused rather than dropped: a level on a paragraph means the client believes it
                // is sending a heading, and storing the type it named while discarding the depth
                // renders that belief as a paragraph with nothing reported.
                errors.computeIfAbsent(at + ".level", key -> new ArrayList<>())
                        .add("Only a heading or a list bullet has a depth.");
            }

            // The same two-armed rule as `level`, twice more — required on the one type that has
            // the field, refused on the other five. Refused rather than dropped for `level`'s
            // reason, which is the only reason any of the three needs: rows on a paragraph mean
            // the client believes it is sending a table, and storing the type it named while
            // discarding the cells renders that belief as an empty paragraph with nothing reported.
            validRows(block, at, errors);
            validImage(block, at, pageAttachmentIds, onCreate, errors);

            UUID id = block.id();
            if (id == null) {
                continue;
            }
            if (onCreate) {
                errors.computeIfAbsent(at + ".id", key -> new ArrayList<>())
                        .add("A new page's blocks are created with it; leave the id out.");
            } else if (!existingBlockIds.contains(id)) {
                errors.computeIfAbsent(at + ".id", key -> new ArrayList<>())
                        .add("That block is not on this page. Leave the id out to add a new "
                                + "block.");
            } else if (!seen.add(id)) {
                errors.computeIfAbsent(at + ".id", key -> new ArrayList<>())
                        .add("This block appears twice in the page.");
            }
        }
        return submitted;
    }

    /**
     * A {@code table}'s cells, or the refusal of rows on a type that has none.
     *
     * <p>Four bounds and one shape rule, and the shape rule is the one worth stating. <strong>A
     * ragged table is refused rather than padded.</strong> Padding produces a table that renders
     * cleanly and is wrong in a way nothing can point at: the reader cannot tell an empty cell the
     * author typed from a cell the server invented, and the two mean opposite things in a
     * specification — "this combination is not applicable" against "somebody's row is truncated".
     * The refusal names the row, so the author fixes the one that is short.
     *
     * <p>A null cell is refused for the same reason one layer down. JSON permits it, an empty string
     * renders identically, and only one of them is something a person typed.
     */
    private static void validRows(
            DocBlockInput block, String at, Map<String, List<String>> errors) {

        List<List<String>> rows = block.rows();

        if (block.blockType() != DocBlockType.TABLE) {
            if (rows != null) {
                errors.computeIfAbsent(at + ".rows", key -> new ArrayList<>())
                        .add("Only a table has rows.");
            }
            return;
        }

        if (rows == null || rows.isEmpty()) {
            // An empty table is not an empty state, it is a block that renders as nothing — the
            // same thing an omitted block would be, except that this one occupies a position and
            // has an id something may already link to.
            errors.computeIfAbsent(at + ".rows", key -> new ArrayList<>())
                    .add("Add at least one row. The first row is the table's header.");
            return;
        }
        if (rows.size() > MAX_TABLE_ROWS) {
            errors.computeIfAbsent(at + ".rows", key -> new ArrayList<>())
                    .add("A table holds at most " + MAX_TABLE_ROWS + " rows. Attach the data as a "
                            + "file instead.");
            return;
        }

        // Row 0 decides the width, because row 0 is the header: the columns are named there, so it
        // is the row that says how many there are. Taking the widest row instead would let a stray
        // extra cell silently widen the table and leave the header short.
        List<String> header = rows.get(0);
        if (header == null || header.isEmpty()) {
            errors.computeIfAbsent(at + ".rows[0]", key -> new ArrayList<>())
                    .add("Name the columns in the first row.");
            return;
        }
        if (header.size() > MAX_TABLE_COLUMNS) {
            errors.computeIfAbsent(at + ".rows[0]", key -> new ArrayList<>())
                    .add("A table holds at most " + MAX_TABLE_COLUMNS + " columns. Split it, or "
                            + "attach the data as a file.");
            return;
        }

        for (int row = 0; row < rows.size(); row++) {
            String rowAt = at + ".rows[" + row + "]";
            List<String> cells = rows.get(row);

            if (cells == null || cells.size() != header.size()) {
                errors.computeIfAbsent(rowAt, key -> new ArrayList<>())
                        .add("Every row needs " + header.size() + " cells, to match the header.");
                continue;
            }
            for (int cell = 0; cell < cells.size(); cell++) {
                String value = cells.get(cell);
                if (value == null) {
                    errors.computeIfAbsent(rowAt + "[" + cell + "]", key -> new ArrayList<>())
                            .add("Leave the cell empty rather than sending nothing.");
                } else if (value.length() > MAX_CELL) {
                    errors.computeIfAbsent(rowAt + "[" + cell + "]", key -> new ArrayList<>())
                            .add("Keep a cell under " + MAX_CELL + " characters. Anything longer is "
                                    + "a paragraph, and a case cannot link to a cell.");
                }
            }
        }
    }

    /**
     * An {@code image}'s file, or the refusal of one on a type that has none.
     *
     * <p>The create arm exists so the refusal is <em>true</em>. A page has no attachments until it
     * exists, so on a create every id fails the membership test — and reporting a real, valid
     * attachment id as "not attached to this page" would send the author looking for a mistake they
     * did not make. The sequence is: create the page, attach the file, then reference it.
     */
    private static void validImage(
            DocBlockInput block,
            String at,
            Set<UUID> pageAttachmentIds,
            boolean onCreate,
            Map<String, List<String>> errors) {

        UUID attachmentId = block.attachmentId();

        if (block.blockType() != DocBlockType.IMAGE) {
            if (attachmentId != null) {
                errors.computeIfAbsent(at + ".attachmentId", key -> new ArrayList<>())
                        .add("Only an image block names a file.");
            }
            return;
        }

        if (onCreate) {
            errors.computeIfAbsent(at + ".attachmentId", key -> new ArrayList<>())
                    .add("A new page has no files yet. Create the page, attach the image, then add "
                            + "the block.");
            return;
        }
        if (attachmentId == null) {
            errors.computeIfAbsent(at + ".attachmentId", key -> new ArrayList<>())
                    .add("Choose one of this page's files.");
            return;
        }
        if (!pageAttachmentIds.contains(attachmentId)) {
            errors.computeIfAbsent(at + ".attachmentId", key -> new ArrayList<>())
                    .add("That file is not attached to this page. Attach it first, then reference "
                            + "it here.");
        }
    }

    /**
     * What the write has to do to turn {@code existing} into {@code submitted}.
     *
     * @param rewrites  existing blocks paired with their new content and new position
     * @param additions blocks with no id yet, in submitted order
     * @param removedIds existing blocks the payload no longer mentions — checked for links
     *                  <strong>before</strong> anything is written, because a 409 raised after the
     *                  rewrites have been flushed is a refusal the caller has to trust the rollback
     *                  to undo
     */
    record Reconciliation(
            List<Rewrite> rewrites, List<Addition> additions, List<UUID> removedIds) {

        record Rewrite(DocBlock block, int position, DocBlockType type, BlockContent content) {
        }

        record Addition(int position, DocBlockType type, BlockContent content) {
        }
    }

    /**
     * Matches the submitted blocks against the stored ones.
     *
     * <p>Pure, and takes both sides as arguments, so the identity rule is testable without a
     * database — which matters more here than usual, because the rule's failure mode is invisible in
     * the response. A test that submits an edit and asserts the returned text is correct passes
     * just as happily against the delete-and-recreate implementation.
     *
     * @param submitted already validated by {@link #validateEdit}, so every id present is known
     */
    static Reconciliation reconcile(List<DocBlock> existing, List<DocBlockInput> submitted) {
        Map<UUID, DocBlock> byId = new LinkedHashMap<>();
        for (DocBlock block : existing) {
            byId.put(block.getId(), block);
        }

        List<Reconciliation.Rewrite> rewrites = new ArrayList<>();
        List<Reconciliation.Addition> additions = new ArrayList<>();
        Set<UUID> kept = new LinkedHashSet<>();

        for (int position = 0; position < submitted.size(); position++) {
            DocBlockInput input = submitted.get(position);
            BlockContent content = BlockContent.of(input);

            if (input.id() != null) {
                kept.add(input.id());
                rewrites.add(new Reconciliation.Rewrite(
                        byId.get(input.id()), position, input.blockType(), content));
            } else {
                additions.add(new Reconciliation.Addition(position, input.blockType(), content));
            }
        }

        List<UUID> removed = new ArrayList<>();
        for (DocBlock block : existing) {
            if (!kept.contains(block.getId())) {
                removed.add(block.getId());
            }
        }

        return new Reconciliation(rewrites, additions, removed);
    }
}
