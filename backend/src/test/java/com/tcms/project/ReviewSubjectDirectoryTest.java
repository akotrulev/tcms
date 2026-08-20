package com.tcms.project;

import static org.assertj.core.api.Assertions.assertThat;

import com.tcms.IntegrationTestBase;
import com.tcms.tenancy.CompanyContext;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The review subject port has one bean per {@link ReviewSubjectType}, and each answers about its
 * own module's table.
 *
 * <h2>The first arm is the one that matters, and it is computed rather than written out</h2>
 *
 * <p>A fourth subject type is added to the enum by somebody working on reviews, in a module that
 * cannot see any of the three implementations — {@code project} declares {@code {api, tenancy}}, so
 * nothing about that edit fails to compile and nothing in this package can even name the classes
 * that would have to change. This is what fails, at the moment the member is added, naming it.
 *
 * <p>What it does <strong>not</strong> assert is the resolution itself against seeded rows: that
 * needs a case, a suite and a plan, and the place all three already exist beside a review is
 * {@code com.tcms.integration.ReviewNotificationTest}, which drives the port through a real
 * delivery. Seeding them a second time here would be a second fixture for one question.
 *
 * <p>Ids are unique to this class — {@code bb…}.
 */
@DisplayName("The review subject port — every subject type has exactly one directory")
class ReviewSubjectDirectoryTest extends IntegrationTestBase {

    private static final UUID COMPANY = UUID.fromString("bb000000-0000-4000-8000-00000000000a");

    @Autowired
    private List<ReviewSubjectDirectory> directories;

    @Test
    @DisplayName("every subject type has exactly one directory, and no directory is orphaned")
    void theIndexIsTotal() {
        Map<ReviewSubjectType, List<ReviewSubjectDirectory>> byType = directories.stream()
                .collect(Collectors.groupingBy(ReviewSubjectDirectory::subjectType));

        assertThat(byType.keySet())
                .as("""
                        A ReviewSubjectType with no directory behind it is a review notification \
                        that can only name a UUID, and nothing about adding the member fails to \
                        compile. Add a directory in the module that owns the table.""")
                .isEqualTo(EnumSet.allOf(ReviewSubjectType.class));

        assertThat(byType.values().stream().map(List::size).collect(Collectors.toSet()))
                .as("two beans claiming one subject type is a coin toss over which table is read")
                .isEqualTo(Set.of(1));
    }

    @Test
    @DisplayName("a subject that does not resolve yields nothing rather than an invented row")
    void anUnknownSubjectIsEmpty() {
        for (ReviewSubjectType type : ReviewSubjectType.values()) {
            ReviewSubjectDirectory directory = directories.stream()
                    .filter(candidate -> candidate.subjectType() == type)
                    .findFirst()
                    .orElseThrow();

            Optional<ReviewSubjectDirectory.Subject> found;
            try (var ignored = CompanyContext.open(COMPANY)) {
                found = directory.describe(UUID.randomUUID(), UUID.randomUUID());
            }

            assertThat(found)
                    .as("a review is history and outlives its subject, so %s must answer 'no such "
                            + "artifact' rather than throwing — the handler that asks is "
                            + "delivering a notice about something that has since been deleted",
                            type)
                    .isEmpty();
        }
    }
}
