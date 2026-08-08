package com.tcms.identity;

import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * How precisely {@code api_token.last_used_at} is kept.
 *
 * <p>A property rather than a constant because the right value depends on the deployment's write
 * budget and on nothing in the domain — and because a deployment that genuinely wants per-request
 * accuracy should be able to ask for it (with {@code PT0S}) rather than patch the entity.
 *
 * @param lastUsedResolution how stale the recorded value may be before an authenticated request is
 *                           worth an {@code UPDATE}. The default is an hour, and the reason it is
 *                           not a second is that a CI pipeline authenticates once per test job:
 *                           the naive writer is one row update per request, every request, all
 *                           contending on the busiest token's single row. What reads this column
 *                           is an administrator asking <em>"has anybody used this credential"</em>
 *                           before revoking it, and that question cannot tell an hour from a
 *                           second.
 */
@ConfigurationProperties(prefix = "tcms.api-token")
@Validated
public record ApiTokenProperties(@NotNull Duration lastUsedResolution) {
}
