package com.tcms.tracker.imports;

/**
 * The source system rejected the credential, refused the account, or did not answer.
 *
 * <p>{@code integration.tracker.TrackerUnavailableException} is the same idea and is unreachable
 * from here (D-a), so this is the copy — a small one, and unlike the URL guard it is not pinned to
 * the original, because there is nothing in it that could drift into a security hole.
 *
 * <p>One exception for every source-side failure, deliberately. The contract answers {@code 502}
 * with the source's own message as {@code detail}, shown only to the person whose credential it is;
 * telling "site down" apart from "no access to that project" would make the endpoint an oracle for
 * what exists in a Jira the viewer cannot read, which is D16's argument one module over.
 */
public class ImportSourceUnavailableException extends RuntimeException {

    public ImportSourceUnavailableException(String message) {
        super(message);
    }

    public ImportSourceUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
