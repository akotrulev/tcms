package com.tcms.tracker;

import com.tcms.tenancy.CompanyContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The <strong>only</strong> writer of {@code issue_doc_link}.
 *
 * <p>{@link IssueFactProjection}'s sibling and the same rule for the same reason: one writer, in
 * this module, fed by an event. The difference between them is what they mirror — that one projects
 * this module's own rows for downstream readers, and this one mirrors another module's rows for this
 * module's own screen — so the trap is the opposite way round and just as quiet. A second writer here
 * would be a claim about {@code documentation}'s table that nothing could reconcile.
 *
 * <p>The transaction boundary lives here rather than on {@link IssueDocLinkFacts} so that the two
 * statements — the delete and the insert — commit together. Under MVCC a concurrent request reads
 * the previous snapshot whole, so no reader ever sees the mirror empty.
 */
@Service
public class DocLinkProjection {

    private static final Logger log = LoggerFactory.getLogger(DocLinkProjection.class);

    private final IssueDocLinkFacts facts;

    DocLinkProjection(IssueDocLinkFacts facts) {
        this.facts = facts;
    }

    /**
     * Rebuilds the open company's mirror from current facts.
     *
     * <p>{@code CompanyContext.require()} rather than a parameter, for the reason every read in this
     * codebase gives: the company comes from the session the handler re-established it from, and a
     * parameter would let the caller choose whose rows to rewrite.
     *
     * @return how many links were mirrored
     */
    @Transactional
    public int refresh() {
        CompanyContext.require();

        int links = facts.refreshIssueDocLinks();
        log.debug("Mirrored {} issue documentation links", links);
        return links;
    }
}
