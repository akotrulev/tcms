package com.tcms.auth;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * The one outbound HTTP client in this application, and the timeouts that bound it.
 *
 * <p>It exists as a bean rather than as a field built where it is used so that both callers —
 * discovery and the token exchange — are bounded by the same settings. A second client built
 * inline is how one of the two ends up with the JDK default, which is <em>no</em> read timeout at
 * all: a request thread would then wait indefinitely on an issuer that accepts the connection and
 * never answers.
 */
@Configuration
class OidcConfiguration {

    @Bean
    RestClient oidcRestClient(RestClient.Builder builder, OidcProperties properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.connectTimeout());
        factory.setReadTimeout(properties.readTimeout());
        return builder.requestFactory(factory).build();
    }
}
