package com.tcms.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/**
 * The four checks that decide whether a subject is proven — driven against a locally generated key
 * pair, so there is no issuer, no network and no JWKS endpoint in the loop.
 *
 * <p>What makes this worth having rather than a restatement of Spring's own validators is
 * {@link OidcIdTokenVerifier#validatorFor}: the decoder here is built over the <strong>same</strong>
 * validator chain production uses, so a check dropped from that method reddens a test here. A test
 * that assembled its own chain would assert that <em>some</em> correct configuration refuses these
 * tokens and say nothing about the one that ships.
 *
 * <p>Three of the four fail <em>open</em> when omitted — a token from another issuer, for another
 * client, or from another sign-in all verify perfectly well against a valid signature. Only expiry
 * fails closed on its own.
 */
@DisplayName("OidcIdTokenVerifier — signature, issuer, audience, nonce, expiry")
class OidcIdTokenVerifierTest {

    private static final String ISSUER = "https://idp.acme.test/realms/acme";
    private static final String CLIENT_ID = "tcms";
    private static final String NONCE = "n-0S6_WzA2Mj";
    private static final UUID PROVIDER = UUID.fromString("f1000000-0000-0000-0000-000000000003");

    private static RSAPublicKey publicKey;
    private static RSAPrivateKey privateKey;

    private final OidcIdTokenVerifier verifier = new OidcIdTokenVerifier();

    @BeforeAll
    static void generateKey() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair pair = generator.generateKeyPair();
        publicKey = (RSAPublicKey) pair.getPublic();
        privateKey = (RSAPrivateKey) pair.getPrivate();
    }

    @Test
    @DisplayName("a well-formed token for this client, this issuer and this nonce is accepted")
    void aTokenMintedForUsIsAccepted() throws Exception {
        Optional<Jwt> verified = verify(claims().build());

        assertThat(verified).isPresent();
        assertThat(verified.get().getSubject()).isEqualTo("sub-1");
    }

    @Test
    @DisplayName("a token from another issuer is rejected")
    void aTokenFromAnotherIssuerIsRejected() throws Exception {
        assertThat(verify(claims().issuer("https://idp.evil.test").build())).isEmpty();
    }

    @Test
    @DisplayName("a token for another client is rejected")
    void aTokenForAnotherClientIsRejected() throws Exception {
        assertThat(verify(claims().audience("some-other-relying-party").build())).isEmpty();
    }

    @Test
    @DisplayName("a token carrying another sign-in's nonce is rejected")
    void aReplayedNonceIsRejected() throws Exception {
        assertThat(verify(claims().claim("nonce", "a-different-attempts-nonce").build())).isEmpty();
    }

    @Test
    @DisplayName("an expired token is rejected")
    void anExpiredTokenIsRejected() throws Exception {
        Instant longAgo = Instant.now().minusSeconds(7200);
        assertThat(verify(claims()
                .issueTime(Date.from(longAgo))
                .expirationTime(Date.from(longAgo.plusSeconds(300)))
                .build()))
                .isEmpty();
    }

    @Test
    @DisplayName("a token with NO audience is rejected — the Cognito access-token trap")
    void aCognitoAccessTokenIsRejectedBecauseItCarriesNoAudience() throws Exception {
        // Cognito's ACCESS tokens carry no `aud` and are told apart by `token_use`. An audience
        // check written as "if an audience is present it must match" reads "no audience" as
        // "any audience", which accepts a token that was never meant for this relying party.
        assertThat(verify(claims()
                .audience((List<String>) null)
                .claim("token_use", "access")
                .build()))
                .isEmpty();
    }

    @Test
    @DisplayName("a token signed by a key the issuer does not publish is rejected")
    void aTokenSignedByAStrangerIsRejected() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair stranger = generator.generateKeyPair();

        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).type(JOSEObjectType.JWT).build(),
                claims().build());
        jwt.sign(new RSASSASigner(stranger.getPrivate()));

        assertThat(verifier.verifyWith(decoder(), expected(), jwt.serialize())).isEmpty();
    }

    @Test
    @DisplayName("something that is not a token at all is refused rather than thrown from")
    void aMalformedTokenIsRefused() {
        assertThat(verifier.verifyWith(decoder(), expected(), "not-a-jwt")).isEmpty();
        assertThat(verifier.verifyWith(decoder(), expected(), null)).isEmpty();
    }

    private Optional<Jwt> verify(JWTClaimsSet claims) throws Exception {
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).type(JOSEObjectType.JWT).build(), claims);
        jwt.sign(new RSASSASigner(privateKey));
        return verifier.verifyWith(decoder(), expected(), jwt.serialize());
    }

    private static JWTClaimsSet.Builder claims() {
        Instant now = Instant.now();
        return new JWTClaimsSet.Builder()
                .issuer(ISSUER)
                .subject("sub-1")
                .audience(CLIENT_ID)
                .claim("nonce", NONCE)
                .claim("email", "kim@acme.test")
                .claim("email_verified", true)
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(300)));
    }

    private static OidcIdTokenVerifier.Expected expected() {
        return new OidcIdTokenVerifier.Expected(
                PROVIDER, ISSUER, CLIENT_ID, "https://idp.acme.test/jwks", NONCE);
    }

    /** The production validator chain over a key this test holds instead of one it fetches. */
    private static JwtDecoder decoder() {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(publicKey).build();
        decoder.setJwtValidator(OidcIdTokenVerifier.validatorFor(ISSUER, CLIENT_ID));
        return decoder;
    }
}
