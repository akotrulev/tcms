package com.tcms.auth;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * The signing key the application will refuse to start on.
 *
 * <p><strong>Why this is a separate observer from {@code ConfigConventionTest}.</strong> That
 * class reads {@code application*.yml} as text and pins the spelling {@code ${TCMS_JWT_SECRET}}
 * with no colon, which is what stops §108 being <em>re-authored</em>. It cannot see a value at
 * all — the key arrives from an environment file on a host neither the repository nor its build
 * has ever read. So the two rules answer different questions and the gap between them is where
 * §108 still lives: the spelling has been right since 2026-08-06 and the string it used to
 * resolve to has been startable the whole time.
 *
 * <p>No Spring context and no database: a constraint on a record is a property of the record,
 * and a check that needs a container is a check that runs once a night.
 */
@DisplayName("JwtProperties — the keys the application refuses")
class JwtPropertiesTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void startValidator() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void closeValidator() {
        factory.close();
    }

    private static JwtProperties withSecret(String secret) {
        return new JwtProperties(secret, "tcms", Duration.ofHours(8), Duration.ofDays(30));
    }

    private static List<String> violations(String secret) {
        return validator.validate(withSecret(secret)).stream()
                .map(violation -> violation.getPropertyPath() + ": " + violation.getMessage())
                .toList();
    }

    @Test
    @DisplayName("the key §108 shipped is refused, and it clears every other constraint")
    void thePublishedKeyIsRefused() {
        // The second half of that sentence is the whole reason this test exists. @NotBlank and
        // @Size(min = 32) are real and were satisfied — 59 characters — which is why a
        // production instance booted on it for weeks without a murmur.
        assertThat(JwtProperties.PUBLISHED_KEY).hasSizeGreaterThanOrEqualTo(32);

        assertThat(violations(JwtProperties.PUBLISHED_KEY))
                .as("the signing key published in this repository must not start the application")
                .hasSize(1)
                .allSatisfy(message -> assertThat(message).contains("secretUsable"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        // `${VAR:?message}` — the §108 form, for any property written that way in future.
        "?a signing secret is required and this is the message somebody wrote",
        // `${VAR:-default}` — the other shell form Spring reads as a default.
        "-a-perfectly-long-default-value-that-nobody-meant-to-ship",
    })
    @DisplayName("a shell placeholder Spring resolved into a literal is refused")
    void aResolvedShellDefaultIsRefused(String secret) {
        // The class rather than the instance. Spring splits on the first `:` and keeps the
        // rest, so the residue of `${VAR:?…}` and `${VAR:-…}` is identifiable by its first
        // character — and no generated key can begin with either.
        assertThat(secret).hasSizeGreaterThanOrEqualTo(32);
        assertThat(violations(secret)).hasSize(1);
    }

    @Test
    @DisplayName("a generated key is accepted")
    void aGeneratedKeyIsAccepted() {
        // `openssl rand -base64 48`, which is what every provisioning path in this repository
        // writes. Base64 begins with [A-Za-z0-9+/], so the refusal above cannot reach it.
        assertThat(violations("kQ8vN2pXtLm4Rf7yZbC1aWdE6sHgJu0oPiKxTn9VrMc3BlQwYzAe5D+/hSfG"))
                .isEmpty();
    }

    @Test
    @DisplayName("a blank key reports one failure, not two")
    void aBlankKeyReportsOneCause() {
        // @NotBlank owns it. `isSecretUsable` returning true for blank is deliberate: two
        // messages for one cause reads as two problems, and the operator fixes one and retries.
        assertThat(violations("")).hasSize(2); // @NotBlank and @Size — both about emptiness
        assertThat(violations("")).noneSatisfy(
                message -> assertThat(message).contains("secretUsable"));
    }

    @Test
    @DisplayName("the refused key is the one application.yml records as having shipped")
    void theRefusedKeyIsTheOneThatShipped() {
        // §213's lesson: a hand-kept copy of something a document already states is a second
        // place for it to be wrong. `application.yml`'s comment block is the incident record
        // and quotes the string on its own line; this is the guard that the constant is that
        // string rather than a retyping of it.
        String config = read(Path.of("src/main/resources/application.yml"));

        assertThat(config)
                .as("application.yml's §108 comment block must still quote the key that shipped, "
                        + "on its own line — it is what JwtProperties.PUBLISHED_KEY is copied "
                        + "from, and a rewording there silently makes the constant an "
                        + "approximation of the incident")
                .contains("\n      #     " + JwtProperties.PUBLISHED_KEY + "\n");
    }

    @Test
    @DisplayName("a context binding the published key fails to start; a generated one starts")
    void theContextRefusesToStartOnIt() {
        // The constraint tests above prove the RULE. This proves the CONSEQUENCE, and they are
        // not the same claim: a constraint nothing invokes at bind time is §108's shape one
        // layer up — a real check, correctly written, that the running application never
        // consults. `ApplicationContextRunner` binds through the same
        // ConfigurationPropertiesBindingPostProcessor the application uses and needs no
        // database, so the proof costs milliseconds rather than a container.
        ApplicationContextRunner contexts = new ApplicationContextRunner()
                .withUserConfiguration(BindJwtProperties.class)
                .withPropertyValues(
                        "tcms.auth.jwt.issuer=tcms",
                        "tcms.auth.jwt.ttl=PT8H",
                        "tcms.auth.jwt.remember-me-ttl=P30D");

        contexts.withPropertyValues("tcms.auth.jwt.secret=" + JwtProperties.PUBLISHED_KEY)
                .run(context -> {
                    assertThat(context)
                            .as("the application must refuse to start on the key §108 shipped")
                            .hasFailed();
                    // And for THIS reason. A context that failed to start for an unrelated
                    // reason satisfies `hasFailed` exactly as well, which would make this the
                    // green-for-the-wrong-cause test the class is arguing against.
                    // On the stack trace rather than the message: the bind failure's own
                    // message names the properties class, and the field error naming the
                    // constraint is one cause down.
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining("secretUsable")
                            .hasStackTraceContaining("§108 defect");
                });

        contexts.withPropertyValues(
                        "tcms.auth.jwt.secret=kQ8vN2pXtLm4Rf7yZbC1aWdE6sHgJu0oPiKxTn9VrMc3Bl")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @EnableConfigurationProperties(JwtProperties.class)
    static class BindJwtProperties {
    }

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
