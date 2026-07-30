package com.tcms.auth;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.nio.charset.StandardCharsets;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/**
 * The signer and the verifier.
 *
 * <p>Split out of {@link SecurityConfiguration} and deliberately <strong>not</strong>
 * web-conditional. The filter chain needs a servlet stack; signing does not — and
 * {@link SessionTokens} is reachable from beans that exist in any context, so conditioning
 * these too takes the whole auth module down with the chain on a CLI run. That is not
 * hypothetical: it is the second half of the same defect, found the same way.
 *
 * <p><strong>The consequence, stated because it is a real cost:</strong> the operator CLI
 * therefore also requires {@code TCMS_JWT_SECRET}, even though it never signs anything. The
 * alternative was excluding the auth package from component scanning in non-web runs, which
 * trades one visible startup requirement for an invisible wiring rule. The CLI and the
 * server run from the same image with the same environment — compose and the runbook both
 * pass it — so this is a requirement an operator has already met.
 */
@Configuration
public class JwtConfiguration {

    @Bean
    public JwtEncoder jwtEncoder(JwtProperties properties) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(key(properties)));
    }

    /**
     * Validates signature, expiry <em>and issuer</em>.
     *
     * <p>The issuer check is not ceremony: {@code JwtValidators.createDefault()} checks
     * expiry alone, so a token signed with the same secret by anything else — a sibling
     * environment restored from the same config, a test fixture that leaked its key — would
     * otherwise be accepted here.
     */
    @Bean
    public JwtDecoder jwtDecoder(JwtProperties properties) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(key(properties))
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(properties.issuer()));
        return decoder;
    }

    private static SecretKeySpec key(JwtProperties properties) {
        // HmacSHA256 names the algorithm to the JCA; the token header says HS256. The
        // minimum length is enforced by JwtProperties, because a short key is padded rather
        // than refused and would look like it worked.
        return new SecretKeySpec(
                properties.secret().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }
}
