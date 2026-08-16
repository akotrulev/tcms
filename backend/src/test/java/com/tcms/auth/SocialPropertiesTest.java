package com.tcms.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.tcms.identity.SocialProviderKey;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SocialPropertiesTest {

    @Test
    @DisplayName("a provider is configured only when both client id and secret are present")
    void configuredNeedsBothHalves() {
        SocialProperties properties = new SocialProperties(
                new SocialProperties.Client("id", "secret"),
                new SocialProperties.Client("id", null),
                null,
                "https://gitlab.com");

        assertThat(properties.configured(SocialProviderKey.GITHUB)).isTrue();
        assertThat(properties.configured(SocialProviderKey.GITLAB)).isFalse();
        assertThat(properties.configured(SocialProviderKey.GOOGLE)).isFalse();
        assertThat(properties.configuredProviders())
                .containsExactly(SocialProviderKey.GITHUB);
    }

    @Test
    @DisplayName("blank strings count as absent — an empty env var must not half-enable a provider")
    void blankIsAbsent() {
        SocialProperties properties = new SocialProperties(
                new SocialProperties.Client("", " "), null, null, "https://gitlab.com");
        assertThat(properties.configuredProviders()).isEmpty();
    }

    @Test
    @DisplayName("the GitLab base URL defaults to gitlab.com and loses any trailing slash")
    void gitlabBaseUrlDefaults() {
        assertThat(new SocialProperties(null, null, null, null).gitlabBaseUrl())
                .isEqualTo("https://gitlab.com");
        assertThat(new SocialProperties(null, null, null, "").gitlabBaseUrl())
                .isEqualTo("https://gitlab.com");
        assertThat(new SocialProperties(null, null, null, "https://git.example.com/")
                .gitlabBaseUrl()).isEqualTo("https://git.example.com");
    }
}
