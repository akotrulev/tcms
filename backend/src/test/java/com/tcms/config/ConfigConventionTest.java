package com.tcms.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Convention checks over the Spring configuration files themselves — the
 * {@code SchemaConventionTest} of {@code application*.yml}.
 *
 * <p><strong>Why this exists.</strong> On 2026-08-06 the production instance was found to be
 * signing every session token with a string committed to this repository. The line responsible
 * was, from §18 until then:
 *
 * <pre>{@code
 *   secret: ${TCMS_JWT_SECRET:?a JWT signing secret of at least 32 characters is required}
 * }</pre>
 *
 * <p>{@code ${VAR:?message}} is <em>POSIX-shell and Docker-Compose</em> syntax for "fail if
 * unset". <strong>Spring has no such form.</strong> Spring splits on the first {@code :} and
 * takes everything after it as the default value, so with the environment variable absent the
 * effective HS256 key was the literal 59-character string
 * {@code ?a JWT signing secret of at least 32 characters is required} — long enough to clear
 * {@code @NotBlank} and {@code @Size(min = 32)} on {@link com.tcms.auth.JwtProperties}, so the
 * application started without a murmur and nothing anywhere failed.
 *
 * <p><strong>Nothing could have caught it.</strong> The comment above the line stated the
 * correct intention ("no default, deliberately"). The bean validation was real and was
 * satisfied. Every integration test passed because {@code IntegrationTestBase} supplies
 * {@code tcms.auth.jwt.secret} through {@code @DynamicPropertySource}, which replaces the
 * placeholder outright — so the suite has never once exercised the expression that shipped.
 * That combination is the working agreement's own rule in its purest form:
 * <strong>a catalogue-wide invariant needs a computed check, not a careful reading.</strong>
 *
 * <p>This test boots no Spring context and needs no database — every rule is a property of the
 * source text, so it runs in milliseconds. That matters: the check that is cheap is the check
 * that survives.
 */
@DisplayName("Config conventions — application*.yml checked against itself")
class ConfigConventionTest {

    /** Every placeholder in the file, with its variable name and whatever follows the colon. */
    private static final Pattern PLACEHOLDER =
            Pattern.compile("\\$\\{([A-Za-z0-9_.]+)(:([^}]*))?}");

    private static List<Path> configFiles() {
        Path resources = Path.of("src/main/resources");
        try (Stream<Path> files = Files.walk(resources)) {
            return files.filter(p -> p.getFileName().toString().matches("application.*\\.ya?ml"))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Strips comments so a rule cannot fire on prose. {@code application.yml} legitimately
     * <em>quotes</em> shell syntax when explaining what compose writes — see the mail block's
     * {@code SPRING_MAIL_HOST: ${SMTP_HOST:-}} — and a check that flagged a comment would be
     * turned off within a week.
     */
    private static String codeOf(Path file) {
        try {
            return Files.readAllLines(file, StandardCharsets.UTF_8).stream()
                    .map(line -> {
                        int hash = line.indexOf('#');
                        return hash < 0 ? line : line.substring(0, hash);
                    })
                    .reduce("", (a, b) -> a + "\n" + b);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    @DisplayName("no placeholder uses shell-only syntax that Spring reads as a default value")
    void noShellPlaceholderSyntax() {
        List<String> offences = new ArrayList<>();

        for (Path file : configFiles()) {
            Matcher m = PLACEHOLDER.matcher(codeOf(file));
            while (m.find()) {
                String variable = m.group(1);
                String defaultValue = m.group(3);
                if (defaultValue == null) continue; // `${VAR}` — required. The correct spelling.

                // `${VAR:?msg}` (shell: error if unset) and `${VAR:-x}` (shell: default to x).
                // Spring understands neither; it takes `?msg` and `-x` as literal defaults.
                if (defaultValue.startsWith("?") || defaultValue.startsWith("-")) {
                    offences.add(("%s: ${%s:%s} — shell syntax. Spring reads the default as the "
                            + "literal '%s'. For a REQUIRED value write ${%s} with no colon; an "
                            + "unresolvable placeholder throws at bind time.")
                            .formatted(file, variable, defaultValue, defaultValue, variable));
                }
            }
        }

        assertThat(offences)
                .describedAs("""
                        A placeholder is using shell/Compose syntax that Spring does not implement.

                        This is how the production JWT signing key became a string committed to \
                        this repository (2026-08-06): `${TCMS_JWT_SECRET:?...}` looks like a hard \
                        requirement and is in fact a default. The value was long enough to satisfy \
                        @Size(min = 32), so it started cleanly and signed real sessions.

                        `:?` and `:-` are meaningful in compose.yaml and compose.prod.yaml. They \
                        are meaningless here.""")
                .isEmpty();
    }

    @Test
    @DisplayName("the JWT signing key is required, with no default of any kind")
    void jwtSecretHasNoDefault() {
        Path applicationYml = Path.of("src/main/resources/application.yml");
        String code = codeOf(applicationYml);

        assertThat(code)
                .describedAs("""
                        tcms.auth.jwt.secret must be spelled `${TCMS_JWT_SECRET}` — no colon, no \
                        default, so an unset variable stops the context instead of signing \
                        sessions with whatever the default happens to be.

                        Pinned separately from the general rule above because this is the one \
                        property where a default is not a misconfiguration but a forged session \
                        for anyone who can read this repository, and because it has already \
                        happened once.""")
                .contains("secret: ${TCMS_JWT_SECRET}");
    }

    @Test
    @DisplayName("the secret is never given a default under any profile")
    void noProfileReintroducesAJwtDefault() {
        List<String> offences = new ArrayList<>();

        for (Path file : configFiles()) {
            Matcher m = PLACEHOLDER.matcher(codeOf(file));
            while (m.find()) {
                if (m.group(1).equals("TCMS_JWT_SECRET") && m.group(3) != null) {
                    offences.add("%s: ${TCMS_JWT_SECRET:%s}".formatted(file, m.group(3)));
                }
            }
        }

        assertThat(offences)
                .describedAs("A profile-specific file gave TCMS_JWT_SECRET a default. Deployments "
                        + "run with SPRING_PROFILES_ACTIVE=prod, so a default there is a default "
                        + "in production.")
                .isEmpty();
    }
}
