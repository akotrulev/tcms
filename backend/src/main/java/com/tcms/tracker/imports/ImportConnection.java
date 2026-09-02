package com.tcms.tracker.imports;

/**
 * One connection to a source system, for one operation — and <strong>the only carrier the
 * credential ever has</strong>.
 *
 * <h2>Nothing persists this, by decision (D-a, review R14)</h2>
 *
 * <p>{@code import_run} has no secret column. Reusing {@code integration.tracker}'s per-person
 * credential is a forbidden module crossing; encrypting a copy here would mean a second AES-GCM key
 * user in a second module, which {@code SecretCipher}'s own header names as "how a rotation misses
 * one". So the token is typed per run, carried in the request body, held for the life of the
 * operation and written nowhere — which is what {@code import-connect.html} already promises in the
 * form's own help text.
 *
 * <p>What that costs, stated rather than discovered: the token is sent twice (preview, then run),
 * a browser reload mid-wizard means re-typing it, and a server restart mid-run cannot resume it.
 *
 * <h2>There is no {@code toString}, and that is the point of the header on this record</h2>
 *
 * <p>A record's generated {@code toString} interpolates every component, so the realistic leak is
 * not a database column — it is a log line, an exception message or a {@code Problem.detail} that
 * happened to include an object somebody assumed was safe. {@code TrackerCredential} makes the same
 * argument one module over. The override below is what makes an accidental interpolation harmless.
 *
 * @param provider     which source system, and therefore which auth header
 * @param siteUrl      {@code https://northwind.atlassian.net}. Refused by {@link ImportUrlGuard}
 *                     before it is dialled if it is not HTTPS or resolves into a private range.
 * @param accountEmail required for {@link ImportProvider#JIRA_CLOUD}, ignored otherwise
 * @param apiToken     read-only is enough. Held for this operation and written nowhere.
 */
public record ImportConnection(
        ImportProvider provider, String siteUrl, String accountEmail, String apiToken) {

    /**
     * The site and the provider, and never the token — see the class header.
     *
     * <p>{@code accountEmail} is out too. It is not a secret, but it is somebody's address, and the
     * value of this override is that a reader never has to work out which components were safe.
     */
    @Override
    public String toString() {
        return "ImportConnection[provider=" + provider + ", siteUrl=" + siteUrl + "]";
    }
}
