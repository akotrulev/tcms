package com.tcms.storage;

import java.util.List;

/**
 * What keys exist under a prefix — <strong>reconciliation, and nothing else</strong>.
 *
 * <h2>Why this is a fourth type and not a fifth method on the three ports</h2>
 *
 * <p>{@link AttachmentStore}'s header refuses exactly that: <em>"An implementation that could be
 * asked 'list this company's files' would be one refactor away from being the place that decides,
 * and the decision would then exist in two places."</em> That argument is about the three ports the
 * product's read and write paths hold — every caller of those is answering "may this person reach
 * these bytes", and a listing method sitting beside {@code open} is an invitation to answer it from
 * the filesystem instead of from the database.
 *
 * <p>So the capability lives on a type those callers do not hold. Nothing in a request path injects
 * this. Its only caller is the operator export, whose question is not reachability but
 * <strong>reconciliation</strong> — which files under a company's prefix are named by no exported
 * row. That question cannot be asked of {@link AttachmentStore#exists}, because it runs the other
 * way round: from the bytes to the rows.
 *
 * <p><strong>This must never become an authorization input.</strong> A key returned here says
 * nothing about who may read it; the row that names it does. If a request path ever needs this
 * interface, that is the signal that a decision has moved to the wrong side of the boundary.
 */
public interface BlobInventory {

    /**
     * Every key under {@code prefix} in {@code root}, sorted, so two runs produce the same order.
     *
     * <p>Empty when the prefix names nothing — an absent directory is not an error, because a
     * company that has never uploaded anything is a normal company.
     *
     * @param prefix an opaque key prefix, on the same terms as every other key in this package: the
     *     caller knows what it means, the store does not
     */
    List<String> keysUnder(BlobRoot root, String prefix);
}
