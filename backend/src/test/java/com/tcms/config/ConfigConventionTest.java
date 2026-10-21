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
import org.junit.jupiter.api.Disabled;
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
 * <p><strong>What this class cannot see, and where the other half lives (§216).</strong> Every
 * rule here is a property of the <em>source text</em>, so together they stop §108 being
 * re-authored and say nothing about the value a host actually supplies — which is the state a
 * restored backup or a pasted value puts an instance in, and the string in question clears
 * {@code @NotBlank} and {@code @Size} at 59 characters. {@link com.tcms.auth.JwtProperties}
 * refuses it at bind time now, and {@code JwtPropertiesTest} is the observer for that half.
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

    // ---------------------------------------------------------------------------------------
    // The blob roots, checked against the artifacts that have to provision them.
    //
    // WHY THESE EXIST. `application.yml` defaults three filesystem roots under /var/lib/tcms.
    // Nothing creates them: /var/lib is root-owned in the runtime base image and the process
    // runs as UID 10001, so the first Files.createDirectories under it throws
    // AccessDeniedException — at the first *write*, which is the first evidence upload, the
    // first report download or the first CI ingestion, long after the container reported
    // healthy. A live pass found it on 2026-08-06 (§110 F10) and a second live pass found the
    // identical stack trace on 2026-08-12, six days later.
    //
    // The reason it survived the first finding is the reason these rules are computed rather
    // than a line in a runbook. §110 fixed `deploy/compose.prod.yaml` and wrote "production is
    // unaffected" — true of the artifact it was looking at, and the artifact it was not looking
    // at is the one this repo's own setup instructions tell every reader to run. Then §160 added
    // a THIRD root, and the Hetzner runbook's `chown` still names two, so that root has been
    // root-owned on the live host since — the same defect, arriving through the same gap,
    // against a fix that had already been applied once.
    //
    // So the population is derived from application.yml and the rules ask each provisioning
    // artifact about every member of it. A fourth store fails the build until it is provisioned,
    // which is the one property neither prose nor a careful reading has managed to hold.
    // ---------------------------------------------------------------------------------------

    /** A configured filesystem root: the environment variable, and the path it defaults to. */
    private record StorageRoot(String variable, String path) {}

    /**
     * {@code root: ${VAR:/some/path}} — the shape every blob store's property has, and the only
     * shape in these files that names a directory the deployment must exist before a write.
     */
    private static final Pattern ROOT_PROPERTY =
            Pattern.compile("(?m)^\\s*root:\\s*\\$\\{([A-Za-z0-9_]+):([^}]+)}");

    private static List<StorageRoot> storageRoots() {
        List<StorageRoot> roots = new ArrayList<>();
        Matcher m = ROOT_PROPERTY.matcher(codeOf(Path.of("src/main/resources/application.yml")));
        while (m.find()) {
            roots.add(new StorageRoot(m.group(1), m.group(2).trim()));
        }

        // The self-corpus guard, and it is not defensive padding. §154's rule ran green over a
        // halved population because it matched its own argument; §156's first draft hid two
        // members because a fixture swallowed a file. A text rule that matches nothing passes
        // every check it makes, so the population is asserted before it is used.
        assertThat(roots)
                .describedAs("ROOT_PROPERTY matched %d roots in application.yml. The product has "
                        + "three blob stores (evidence, reports, raw-reports), so fewer than three "
                        + "means this pattern has stopped seeing the file rather than that a root "
                        + "was removed — and every rule below would then pass over nothing.",
                        roots.size())
                .hasSizeGreaterThanOrEqualTo(3);
        return roots;
    }

    /** Whole-line {@code #} comments only — a Dockerfile has no trailing-comment syntax. */
    private static String dockerfileCode() {
        try {
            return Files.readAllLines(Path.of("Dockerfile"), StandardCharsets.UTF_8).stream()
                    .filter(line -> !line.stripLeading().startsWith("#"))
                    .reduce("", (a, b) -> a + "\n" + b);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    @DisplayName("every blob root is created and owned by the runtime user in the image")
    void everyStorageRootIsCreatedInTheImage() {
        String dockerfile = dockerfileCode();
        List<String> missing = storageRoots().stream()
                .map(StorageRoot::path)
                .filter(path -> !dockerfile.contains(path))
                .toList();

        assertThat(missing)
                .describedAs("""
                        backend/Dockerfile does not create %s.

                        The image is the one artifact every deployment shares, which is why the \
                        fix lives there: a per-deployment fix was applied once already (§110, \
                        compose.prod.yaml) and the defect re-fired six days later through the \
                        deployment that fix did not touch.

                        It is also what makes the root compose.yaml's named volume come up owned \
                        by 10001 — Docker seeds a fresh named volume from the image's content at \
                        that path, ownership included. Delete the mkdir and the volume is created \
                        root-owned and the AccessDeniedException returns through the mount.

                        Comments are stripped before this check. A PRECAUTION, not a measured \
                        save — verified 2026-08-13 by deleting the RUN and leaving the comment \
                        block, and the rule failed, because that prose names the paths by brace \
                        expansion rather than in full. Kept because the next edit to that comment \
                        need only spell one of them out for the rule to start passing over its \
                        own explanation, which is the shape §162 deleted two exemptions for.""",
                        missing)
                .isEmpty();
    }

    /**
     * Env-var-shaped so that {@code ${spring.datasource.url}} — a property reference, not an
     * environment variable — is not read as something Compose owes the container.
     */
    private static final Pattern ENV_VAR_NAME = Pattern.compile("[A-Z][A-Z0-9_]*");

    @Test
    @Disabled("deploy/compose.prod.yaml is not part of the public copy of this repository")
    @DisplayName("every required placeholder is a variable compose.prod.yaml actually passes")
    void everyRequiredPlaceholderIsSuppliedInProduction() {
        String prod = codeOf(Path.of("../deploy/compose.prod.yaml"));
        List<String> missing = new ArrayList<>();

        for (Path file : configFiles()) {
            Matcher m = PLACEHOLDER.matcher(codeOf(file));
            while (m.find()) {
                String name = m.group(1);
                // group(2) is the `:default` tail. Present means the app has a fallback and
                // production supplying nothing is a decision rather than an outage.
                if (m.group(2) != null || !ENV_VAR_NAME.matcher(name).matches()) {
                    continue;
                }
                if (!prod.contains(name)) {
                    missing.add("%s (%s)".formatted(name, file.getFileName()));
                }
            }
        }

        assertThat(missing)
                .describedAs(
                        """
                        deploy/compose.prod.yaml passes none of: %s.

                        A `${VAR}` with NO COLON is the §108 shape and means the application has \
                        no default and must not boot without a real value. That guarantee is only \
                        half a guarantee: it binds the APP, and nothing binds the DEPLOYMENT. If \
                        compose never mentions the variable, the container starts without it and \
                        the instance is down — which is what happened on 2026-08-15, when \
                        TCMS_IDENTITY_SECRET_KEY and TCMS_INTEGRATION_SECRET_KEY reached \
                        application.yml and never reached compose.prod.yaml. Both boxes refused \
                        to start, lab and production, on the same dispatch.

                        THE DEPLOY'S OWN VALIDATION CANNOT SEE THIS. `docker compose config` \
                        checks that the box's env file satisfies the variables COMPOSE NAMES; a \
                        variable compose never names is not a gap it can have an opinion about. \
                        That step is green in exactly this failure.

                        AND THE APP'S OWN GUARANTEE IS WEAKER THAN ITS COMMENT CLAIMS. \
                        application.yml says an unresolvable placeholder "throws at bind time". \
                        True for @Value; FALSE for @ConfigurationProperties, whose Binder resolves \
                        with ignoreUnresolvablePlaceholders and passes the literal text \
                        `${TCMS_IDENTITY_SECRET_KEY}` through as the value. The 2026-08-15 boot \
                        failed with `Illegal base64 character 24` — 0x24 is `$`, the first \
                        character of the un-substituted placeholder — and it failed ONLY because \
                        that record validates Base64. A plain String property with no validation \
                        would have taken the literal placeholder as its value and started clean, \
                        which is §108 happening a second time past §108's own fix.""",
                        missing)
                .isEmpty();
    }

    @Test
    @Disabled("deploy/compose.prod.yaml is not part of the public copy of this repository")
    @DisplayName("every blob root is bind-mounted in production, at the path the app writes to")
    void everyStorageRootIsMountedInProduction() {
        String prod = codeOf(Path.of("../deploy/compose.prod.yaml"));
        List<String> offences = new ArrayList<>();

        for (StorageRoot root : storageRoots()) {
            if (!prod.contains(root.path() + ":" + root.path())) {
                offences.add("no bind mount `%s:%s`".formatted(root.path(), root.path()));
            }
            if (!prod.contains(root.variable() + ": " + root.path())) {
                offences.add("`%s` is not set to `%s`".formatted(root.variable(), root.path()));
            }
        }

        assertThat(offences)
                .describedAs("""
                        deploy/compose.prod.yaml: %s.

                        Both halves are the same decision (§107): the application's path and the \
                        mount target must be ONE string. Set the variable without the mount and \
                        the bytes land in the container's writable layer, where `docker compose \
                        pull && up -d` destroys them; mount without setting the variable and the \
                        app writes somewhere else entirely and the mount sits empty.

                        Note what this rule CANNOT check. A bind mount takes the host directory's \
                        ownership and ignores the image's outright, so the image's mkdir buys \
                        production nothing — §7 of the Hetzner runbook (`mkdir` + `chown -R \
                        10001:10001 /var/lib/tcms`) is the step that makes the VM work, and it is \
                        prose on a host no test can read. That is where the third root (§160) is \
                        still root-owned today.""", offences)
                .isEmpty();
    }

    @Test
    @DisplayName("the local stack persists every blob root under one mounted directory")
    void everyStorageRootIsPersistedLocally() {
        String compose = codeOf(Path.of("../compose.yaml"));

        // Derived, not asserted as a literal: whatever compose mounts must be an ancestor of
        // every root the application writes to, so a fourth root outside it fails here.
        String mounted = "/var/lib/tcms";
        List<String> outside = storageRoots().stream()
                .map(StorageRoot::path)
                .filter(path -> !path.equals(mounted) && !path.startsWith(mounted + "/"))
                .toList();

        assertThat(compose)
                .describedAs("""
                        The root compose.yaml has no volume mounted at %s.

                        This is the artifact §110 did not fix and the second live pass then hit: \
                        `docker compose up --build`, the command this repository's own CLAUDE.md \
                        gives as the way to run the stack, produced a backend that 500s on the \
                        first evidence upload, the first report download and the first CI \
                        ingestion.

                        A named volume rather than nothing, for the reason pgdata is one: bytes \
                        that survive `down` and are dropped by `down -v`, which is what the \
                        documented reset command already promises.""", mounted)
                .contains(":" + mounted);

        assertThat(outside)
                .describedAs("Root(s) %s are configured outside %s, so the local stack's volume "
                        + "does not persist them.", outside, mounted)
                .isEmpty();
    }
}
