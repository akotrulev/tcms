package com.tcms.provisioning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;

/**
 * Argument handling for {@code export-company} — a plain unit test, no container (§150's lane).
 *
 * <p>Worth its own class for {@code provision-company}'s reason: the CLI is the one surface an
 * operator meets at 2am with no type system to help them. The stakes differ, though — nothing here
 * is written to the database, and the argument that can hurt is {@code --out}, which decides whether
 * a multi-gigabyte archive lands on a mounted volume or in a container's writable layer.
 */
@DisplayName("export-company — argument handling")
class ExportCompanyCommandTest {

    @Test
    @DisplayName("parses the two required arguments")
    void parsesBothArguments() {
        var parsed = ExportCompanyCommand.parse(args(
                "export-company", "--slug=acme", "--out=/var/backups/acme.zip"));

        assertThat(parsed.slug()).isEqualTo("acme");
        assertThat(parsed.out()).isEqualTo("/var/backups/acme.zip");
    }

    @Test
    @DisplayName("accepts a relative --out as readily as an absolute one")
    void acceptsARelativeOut() {
        // Resolved against the process's working directory by Path.of, which is the shell's own
        // rule. Refusing it here would be this command inventing a convention its siblings do not
        // have, and an operator exporting into the current directory is a normal thing to do.
        assertThat(ExportCompanyCommand.parse(args(
                "export-company", "--slug=acme", "--out=acme.zip")).out())
                .isEqualTo("acme.zip");
    }

    @Test
    @DisplayName("refuses a missing --slug")
    void refusesAMissingSlug() {
        assertThatThrownBy(() -> ExportCompanyCommand.parse(args(
                "export-company", "--out=/tmp/acme.zip")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("--slug");
    }

    @Test
    @DisplayName("refuses a missing --out")
    void refusesAMissingOut() {
        // No default, deliberately: a guessed path is a multi-gigabyte archive of client data
        // written somewhere the operator did not choose and may not find.
        assertThatThrownBy(() -> ExportCompanyCommand.parse(args(
                "export-company", "--slug=acme")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("--out");
    }

    @Test
    @DisplayName("treats a blank value as missing")
    void treatsBlankAsMissing() {
        assertThatThrownBy(() -> ExportCompanyCommand.parse(args(
                "export-company", "--slug=", "--out=/tmp/acme.zip")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("--slug");
    }

    @Test
    @DisplayName("refuses an argument given more than once")
    void refusesARepeatedArgument() {
        // Which one did they mean? Exporting the wrong company is not recoverable by re-running:
        // the archive has already been written and may already have been handed over.
        assertThatThrownBy(() -> ExportCompanyCommand.parse(args(
                "export-company", "--slug=acme", "--slug=other", "--out=/tmp/acme.zip")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("more than once");

        assertThatThrownBy(() -> ExportCompanyCommand.parse(args(
                "export-company", "--slug=acme", "--out=/tmp/a.zip", "--out=/tmp/b.zip")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("more than once");
    }

    @Test
    @DisplayName("the verb is registered in OperatorCommands.ALL")
    void theVerbIsRegistered() {
        // The load-bearing half of registration. A runner registered without this starts a servlet
        // container, writes the archive correctly and never exits — OperatorCommands' own header
        // calls that "the kind of defect that is found by an operator rather than by a test", and
        // this is the assertion that makes it not one.
        assertThat(OperatorCommands.ALL).contains(ExportCompanyCommand.COMMAND);
        assertThat(OperatorCommands.isRequestedBy(
                new String[] {"--spring.profiles.active=prod", ExportCompanyCommand.COMMAND}))
                .isTrue();
    }

    private static DefaultApplicationArguments args(String... args) {
        return new DefaultApplicationArguments(args);
    }
}
