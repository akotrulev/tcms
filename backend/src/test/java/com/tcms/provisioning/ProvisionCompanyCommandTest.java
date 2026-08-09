package com.tcms.provisioning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;

/**
 * Argument handling for {@code provision-company} — a plain unit test, no container.
 *
 * <p>Worth its own class because the CLI is the one surface an operator meets at 2am with
 * no type system to help them, and because every argument here lands in a field that is
 * immutable afterwards: the slug appears in every URL ever shared, and a project key
 * cannot be changed once cases carry it.
 */
@DisplayName("provision-company — argument handling")
class ProvisionCompanyCommandTest {

    @Test
    @DisplayName("parses the minimum set, with no first project")
    void parsesWithoutProject() {
        var request = ProvisionCompanyCommand.parse(args(
                "provision-company",
                "--slug=acme",
                "--name=Acme Corporation",
                "--admin-email=ops@acme.test",
                "--admin-name=A. Kotrulev"));

        assertThat(request.slug()).isEqualTo("acme");
        assertThat(request.displayName()).isEqualTo("Acme Corporation");
        assertThat(request.adminEmail()).isEqualTo("ops@acme.test");
        assertThat(request.adminName()).isEqualTo("A. Kotrulev");
        assertThat(request.firstProject()).isEmpty();
    }

    @Test
    @DisplayName("parses a first project when both of its arguments are given")
    void parsesWithProject() {
        var request = ProvisionCompanyCommand.parse(args(
                "provision-company",
                "--slug=acme",
                "--name=Acme Corporation",
                "--admin-email=ops@acme.test",
                "--admin-name=A. Kotrulev",
                "--project-key=OLB",
                "--project-name=Acme Online Banking"));

        assertThat(request.firstProject()).hasValueSatisfying(project -> {
            assertThat(project.key()).isEqualTo("OLB");
            assertThat(project.name()).isEqualTo("Acme Online Banking");
        });
    }

    /**
     * Half a project is worse than none: the missing half would have to be guessed —
     * a key derived from the name, or a name derived from the key — and neither is
     * changeable once cases carry it.
     */
    @Test
    @DisplayName("refuses one project argument without the other, in both directions")
    void refusesHalfAProject() {
        assertThatThrownBy(() -> ProvisionCompanyCommand.parse(args(
                "provision-company", "--slug=acme", "--name=Acme",
                "--admin-email=ops@acme.test", "--admin-name=Ops", "--project-key=OLB")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("go together");

        assertThatThrownBy(() -> ProvisionCompanyCommand.parse(args(
                "provision-company", "--slug=acme", "--name=Acme",
                "--admin-email=ops@acme.test", "--admin-name=Ops", "--project-name=Online Banking")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("go together");
    }

    @Test
    @DisplayName("names the missing argument rather than failing on a constraint later")
    void namesTheMissingArgument() {
        assertThatThrownBy(() -> ProvisionCompanyCommand.parse(args(
                "provision-company", "--slug=acme", "--name=Acme", "--admin-name=Ops")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("--admin-email");
    }

    /**
     * {@code --slug=a --slug=b} is a typo, not a list. Taking the first or the last would
     * provision one of two companies the operator meant, chosen by an implementation
     * detail they cannot see.
     */
    @Test
    @DisplayName("refuses a repeated argument rather than silently picking one")
    void refusesRepeatedArgument() {
        assertThatThrownBy(() -> ProvisionCompanyCommand.parse(args(
                "provision-company", "--slug=acme", "--slug=globex", "--name=Acme",
                "--admin-email=ops@acme.test", "--admin-name=Ops")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("more than once");
    }

    @Test
    @DisplayName("treats an empty value as absent, so --slug= is a usage error not a blank slug")
    void treatsEmptyValueAsMissing() {
        assertThatThrownBy(() -> ProvisionCompanyCommand.parse(args(
                "provision-company", "--slug=", "--name=Acme",
                "--admin-email=ops@acme.test", "--admin-name=Ops")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("--slug");
    }

    // "recognises its own invocation, and only its own" sat here until §153, over
    // ProvisionCompanyCommand.isRequestedBy — a method TcmsApplication had stopped calling.
    // It is OperatorCommandsTest now, over the guard that actually decides, and over all three
    // verbs rather than this one.

    private static DefaultApplicationArguments args(String... args) {
        return new DefaultApplicationArguments(args);
    }
}
