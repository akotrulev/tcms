package com.tcms.integration.tracker;

/**
 * The tracker did not answer, refused the credential, or refused the request.
 *
 * <h2>One exception for four conditions, on purpose</h2>
 *
 * <p>004 D16: <em>not connected</em>, <em>token expired</em>, <em>no access to this issue</em> and
 * <em>tracker down</em> render identically. Only the call to action differs, and only on the
 * viewer's own account page. A hierarchy of exceptions here would be four facts a caller could
 * branch on, and the first caller to branch on them turns the preview into an oracle for what
 * exists in a tracker the viewer cannot read.
 *
 * <p>{@link #getMessage()} is the tracker's own words <strong>after {@code SecretRedaction}</strong>
 * — an HTTP client's exception routinely quotes the URL it was calling, and for these calls the
 * credential travels in a header rather than the URL, but a redirect target or a proxy's echo can
 * carry one anyway. It is written to {@code tracker_identity.last_error} and to
 * {@code tracker_issue_creation.error}, and it is served to the person whose credential it is; the
 * search and preview endpoints answer a state and no message at all.
 */
public class TrackerUnavailableException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public TrackerUnavailableException(String message) {
        super(message);
    }

    public TrackerUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
