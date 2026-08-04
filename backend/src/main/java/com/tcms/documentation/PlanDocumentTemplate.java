package com.tcms.documentation;

import java.util.ArrayList;
import java.util.List;

/**
 * The skeleton a test plan's document starts as — the five headed sections of a test plan, each with
 * a prompt saying what belongs under it.
 *
 * <h2>Prompts, not content</h2>
 *
 * <p>Every non-heading block here is a prompt the author is expected to replace. That is a
 * deliberate constraint on what this file may contain: a template that shipped example content would
 * produce documents asserting things about a product nobody has looked at, and a plan nobody has
 * touched must read as an <em>empty form</em> rather than as a filled-in one. The distinction
 * matters because a plan is a document people sign off.
 *
 * <h2>Seeded once, and there is no reset</h2>
 *
 * <p>{@link PlanDocumentService} applies this at creation and nowhere else. A "reset to template"
 * would overwrite whatever had been written under those headings, and the request that destroyed a
 * quarter's work would be indistinguishable from the one that set up an empty document.
 *
 * <h2>The scenario outline is the reason `level` exists on a list</h2>
 *
 * <p>The last section's prompts are indented bullets, because that is the shape a test plan's
 * scenarios take — an area, its checks, and the variations under each check. One bullet is one
 * block, so a case can be linked to the exact scenario it verifies rather than to the section
 * containing it.
 *
 * <h2>Test artefacts records identifiers and never secrets</h2>
 *
 * <p>Its prompt asks for the environment, the account, and <em>where the credential lives</em>. Test
 * plans in the wild list passwords in prose — the example this template was derived from lists three
 * — and this product does not store them. The prompt is where that is said, because a section with
 * no guidance is one somebody fills in the way they always have.
 */
final class PlanDocumentTemplate {

    private PlanDocumentTemplate() {
    }

    /** The document's title, given the plan's key and name. */
    static String titleFor(String planKey, String planName) {
        return planKey + " — " + planName;
    }

    /**
     * The template's blocks, in order.
     *
     * <p>A fresh list each call. The blocks are immutable records, but the list is handed to a
     * validator and then to a writer, and returning a shared constant would make this class's
     * contents depend on what the last caller did with them.
     */
    static List<DocBlockInput> blocks() {
        List<DocBlockInput> blocks = new ArrayList<>();

        heading(blocks, 1, "Business requirements");
        paragraph(blocks, "What the product under test does, in the words of the people who asked "
                + "for it. Describe the system in a sentence or two, then list its main "
                + "functionalities below — one bullet each.");
        bullet(blocks, 1, "A capability the product offers.");

        heading(blocks, 1, "Test artefacts");
        heading(blocks, 2, "Test environments");
        bullet(blocks, 1, "Environment name — its URL, and what build or branch it runs.");
        heading(blocks, 2, "Accounts");
        paragraph(blocks, "Who each account is and what it can reach. Record where the credential "
                + "lives — a vault entry, a shared secret store — and never the credential itself. "
                + "A password typed here is readable by everyone who can read this plan, survives "
                + "every export of it, and outlives the person who set it.");
        bullet(blocks, 1, "Account identifier — the role or team it stands for, and where its "
                + "secret is kept.");

        heading(blocks, 1, "Scope");
        paragraph(blocks, "What this plan undertakes to test. Sentences, not case keys — the suites "
                + "and cases that will actually be executed are the plan's own scope, set on the "
                + "plan itself, and this section is what a reader needs in order to agree that the "
                + "selection is the right one.");
        bullet(blocks, 1, "Something this plan will verify.");

        heading(blocks, 1, "Out of scope");
        paragraph(blocks, "What this plan deliberately does not cover, and — where it is not "
                + "obvious — why. An unstated exclusion reads as an oversight when the gap is "
                + "found later.");
        bullet(blocks, 1, "Something this plan will not verify.");

        heading(blocks, 1, "Test scenarios");
        paragraph(blocks, "The plan's scenarios, grouped by area. Indent a bullet to put it under "
                + "the one above. Each bullet is its own item, so a test case can be linked to the "
                + "exact scenario it verifies.");
        heading(blocks, 2, "Area");
        bullet(blocks, 1, "A check to perform in this area.");
        bullet(blocks, 2, "A variation of the check above.");
        bullet(blocks, 3, "A case within that variation.");

        return blocks;
    }

    private static void heading(List<DocBlockInput> blocks, int level, String text) {
        blocks.add(new DocBlockInput(null, DocBlockType.HEADING, level, text));
    }

    private static void paragraph(List<DocBlockInput> blocks, String text) {
        blocks.add(new DocBlockInput(null, DocBlockType.PARAGRAPH, null, text));
    }

    private static void bullet(List<DocBlockInput> blocks, int depth, String text) {
        blocks.add(new DocBlockInput(null, DocBlockType.LIST, depth, text));
    }
}
