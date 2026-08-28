package com.tcms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tcms.provisioning.OperatorCommands;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;

/**
 * Argument handling and exit codes for {@code migrate-gherkin-source} — a plain unit test, no
 * container (§150's lane).
 *
 * <p>Its own class for {@code export-company}'s reason: the CLI is the one surface an operator
 * meets at 2am with no type system to help them. What is at stake here is narrower than that
 * command's {@code --out} and sharper in one place — <strong>{@code --dry-run}</strong>. It is the
 * flag an operator reaches for precisely because they are not sure, so a spelling that is silently
 * ignored writes a version row against every Gherkin case in a client's repository.
 *
 * <p>It is in the composition root because the command is — {@link MigrateGherkinSourceCommand}'s
 * header says why, and the one visible consequence here is the verb assertion at the bottom, which
 * goes through the public {@code isRequestedBy} rather than the package-private set.
 *
 * <p>The exit codes are asserted here rather than through {@code run}, which ends in
 * {@code System.exit} and cannot be driven in-process. {@code MigrateGherkinSourceTest} drives
 * {@link MigrateGherkinSourceCommand#resolve} against a real database; this holds the mapping from
 * its answer to the number a script reads.
 */
@DisplayName("migrate-gherkin-source — argument handling and exit codes")
class MigrateGherkinSourceCommandTest {

    @Test
    @DisplayName("parses the two required arguments, with the flag off by default")
    void parsesBothArguments() {
        var parsed = MigrateGherkinSourceCommand.parse(args(
                "migrate-gherkin-source", "--slug=acme", "--actor=ops@acme.test"));

        assertThat(parsed.slug()).isEqualTo("acme");
        assertThat(parsed.actor()).isEqualTo("ops@acme.test");
        assertThat(parsed.dryRun())
                .as("a command that migrated by default would be one nobody could rehearse")
                .isFalse();
    }

    @Test
    @DisplayName("--dry-run is a flag, and its presence is the whole question")
    void readsTheDryRunFlag() {
        assertThat(MigrateGherkinSourceCommand.parse(args(
                "migrate-gherkin-source", "--slug=acme", "--actor=ops@acme.test", "--dry-run"))
                .dryRun())
                .isTrue();
    }

    @Test
    @DisplayName("refuses --dry-run=false rather than honouring it")
    void refusesAValueOnTheFlag() {
        // The one refusal in this class that is not about a missing argument, and the one that
        // would otherwise be silent: `--dry-run=false` reads to its author as "do not do it", and
        // a flag whose value is ignored means the opposite. Refusing is the only answer that
        // cannot migrate a repository somebody was only rehearsing against.
        assertThatThrownBy(() -> MigrateGherkinSourceCommand.parse(args(
                "migrate-gherkin-source", "--slug=acme", "--actor=ops@acme.test",
                "--dry-run=false")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("--dry-run");
    }

    @Test
    @DisplayName("refuses a missing --slug")
    void refusesAMissingSlug() {
        assertThatThrownBy(() -> MigrateGherkinSourceCommand.parse(args(
                "migrate-gherkin-source", "--actor=ops@acme.test")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("--slug");
    }

    @Test
    @DisplayName("refuses a missing --actor")
    void refusesAMissingActor() {
        // No default, deliberately: every version this writes carries an author, and defaulting it
        // to nobody would put a null in test_case_version.created_by for a change somebody made.
        assertThatThrownBy(() -> MigrateGherkinSourceCommand.parse(args(
                "migrate-gherkin-source", "--slug=acme")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("--actor");
    }

    @Test
    @DisplayName("treats a blank value as missing")
    void treatsBlankAsMissing() {
        assertThatThrownBy(() -> MigrateGherkinSourceCommand.parse(args(
                "migrate-gherkin-source", "--slug=", "--actor=ops@acme.test")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("--slug");
    }

    @Test
    @DisplayName("refuses an argument given more than once")
    void refusesARepeatedArgument() {
        // Which one did they mean? Migrating the wrong company is not undone by re-running: the
        // rows and the version rows are committed, and only a human reading the report would ever
        // notice they belong to a client nobody asked about.
        assertThatThrownBy(() -> MigrateGherkinSourceCommand.parse(args(
                "migrate-gherkin-source", "--slug=acme", "--slug=other",
                "--actor=ops@acme.test")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("more than once");
    }

    @Test
    @DisplayName("an unresolvable actor and an ungranted one both cost exit 2; a bad slug costs 1")
    void theExitCodes() {
        // A script's whole interface. The two usage errors are things the operator typed or has to
        // arrange; the missing company is export-company's answer to the same question, and keeping
        // them apart is what lets a wrapper tell a typo from a grant somebody has to make.
        assertThat(MigrateGherkinSourceCommand.exitCodeOf(
                new MigrateGherkinSourceCommand.Resolution.NoSuchActor("x"))).isEqualTo(2);
        assertThat(MigrateGherkinSourceCommand.exitCodeOf(
                new MigrateGherkinSourceCommand.Resolution.NotAMember("x"))).isEqualTo(2);
        assertThat(MigrateGherkinSourceCommand.exitCodeOf(
                new MigrateGherkinSourceCommand.Resolution.NoSuchCompany("x"))).isEqualTo(1);
        assertThat(MigrateGherkinSourceCommand.exitCodeOf(
                new MigrateGherkinSourceCommand.Resolution.Ready(UUID.randomUUID(),
                        UUID.randomUUID()))).isEqualTo(0);
    }

    @Test
    @DisplayName("the verb is registered in OperatorCommands.ALL")
    void theVerbIsRegistered() {
        // The load-bearing half of registration. A runner registered without this starts a servlet
        // container, migrates the company correctly and never exits — OperatorCommands' own header
        // calls that "the kind of defect that is found by an operator rather than by a test", and
        // this is the assertion that makes it not one.
        // Through isRequestedBy rather than through OperatorCommands.ALL — the set is
        // package-private in `provisioning` and this class is in the composition root, which is
        // the same boundary the command itself sits on. Nothing is lost: `isRequestedBy` IS the
        // question TcmsApplication asks, and `OperatorCommandsTest.everyVerb` derives its cases
        // from the set, so the fifth verb is covered there by the day it is added.
        assertThat(MigrateGherkinSourceCommand.COMMAND)
                .isEqualTo(OperatorCommands.MIGRATE_GHERKIN_SOURCE);
        assertThat(OperatorCommands.isRequestedBy(
                new String[] {"--spring.profiles.active=prod", MigrateGherkinSourceCommand.COMMAND}))
                .isTrue();
    }

    private static DefaultApplicationArguments args(String... args) {
        return new DefaultApplicationArguments(args);
    }
}
