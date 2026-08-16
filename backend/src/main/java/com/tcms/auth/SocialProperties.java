package com.tcms.auth;

import com.tcms.identity.SocialProviderKey;
import java.util.Arrays;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The operator's social sign-in configuration — instance-level by design (the 2026-08-16 plan's
 * decision 3): GitHub, GitLab and Google are authorities for people, not companies, so their
 * relying-party credentials belong to the deployment, not to any {@code identity_provider} row.
 *
 * <p>Absent means off. There is no {@code enabled} flag to disagree with the credentials: a
 * provider is a live sign-in route exactly when both halves are present, and the
 * {@code /auth/social/providers} endpoint answers from this and nothing else.
 */
@ConfigurationProperties(prefix = "tcms.auth.social")
public record SocialProperties(Client github, Client gitlab, Client google, String gitlabBaseUrl) {

    public record Client(String clientId, String clientSecret) {

        boolean configured() {
            return present(clientId) && present(clientSecret);
        }
    }

    public SocialProperties {
        if (gitlabBaseUrl == null || gitlabBaseUrl.isBlank()) {
            gitlabBaseUrl = "https://gitlab.com";
        }
        gitlabBaseUrl = gitlabBaseUrl.replaceAll("/+$", "");
    }

    public boolean configured(SocialProviderKey provider) {
        Client client = clientFor(provider);
        return client != null && client.configured();
    }

    public Client clientFor(SocialProviderKey provider) {
        return switch (provider) {
            case GITHUB -> github;
            case GITLAB -> gitlab;
            case GOOGLE -> google;
        };
    }

    public List<SocialProviderKey> configuredProviders() {
        return Arrays.stream(SocialProviderKey.values()).filter(this::configured).toList();
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }
}
