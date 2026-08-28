package com.tcms.integration.tracker;

/**
 * How a {@code tracker_identity} authenticates. One member, and the second one is designed for.
 *
 * <p>004 D3: a PAT is the only mechanism on all three providers that is <strong>entirely
 * user-side</strong> — no app registration, no Entra tenant configuration, no tracker-admin action,
 * and no public redirect URI on a self-hosted instance. OAuth on every one of the three needs
 * somebody other than the person connecting to act first, which for a product shipped as one image
 * turns "try the integration" into a provisioning project.
 *
 * <p>{@code V73}'s {@code CHECK (auth_method = 'PAT')} is the database half. Relaxing it is the
 * OAuth slice's first line; the row already carries {@code refresh_token}, {@code scopes} and
 * {@code expires_at} so that slice is a second value here rather than a table redesign.
 */
public enum TrackerAuthMethod {
    PAT
}
