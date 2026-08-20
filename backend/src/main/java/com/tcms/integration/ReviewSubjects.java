package com.tcms.integration;

import com.tcms.project.ReviewSubjectDirectory;
import com.tcms.project.ReviewSubjectType;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The artifact a review is about, by key and title.
 *
 * <p>{@link NotificationProjects}' shape for the second unresolvable id in a review payload, and
 * its reason: a message's reader is in a chat room with nothing open, and the value is resolved at
 * delivery rather than carried, because a title is edited and a frozen one would be announced by a
 * retry an hour later.
 *
 * <p>The index is built once at construction and fails there, which is
 * {@link NotificationDispatchService}'s arrangement for adapters: two beans claiming one subject
 * type is a coin toss over which table is read, and it is decidable before anything runs.
 */
@Component
class ReviewSubjects {

    private final Map<ReviewSubjectType, ReviewSubjectDirectory> byType;

    ReviewSubjects(List<ReviewSubjectDirectory> directories) {
        Map<ReviewSubjectType, ReviewSubjectDirectory> index =
                new EnumMap<>(ReviewSubjectType.class);
        for (ReviewSubjectDirectory directory : directories) {
            ReviewSubjectDirectory clash = index.put(directory.subjectType(), directory);
            if (clash != null) {
                throw new IllegalStateException(
                        "Two ReviewSubjectDirectory beans claim " + directory.subjectType() + ": "
                                + clash.getClass().getSimpleName() + " and "
                                + directory.getClass().getSimpleName());
            }
        }
        this.byType = Map.copyOf(index);
    }

    /**
     * @return the artifact, or empty when it has been deleted since the review was written — a
     *         review is history and outlives its subject
     * @throws IllegalStateException when no directory claims the subject type. A missing one is a
     *         deployment that cannot describe an artifact it is announcing, so it is a thrown error
     *         and therefore a retried outbox row an operator can see, rather than a message that
     *         quietly declines to say what it is about
     */
    Optional<ReviewSubjectDirectory.Subject> describe(
            ReviewSubjectType subjectType, UUID projectId, UUID subjectId) {

        ReviewSubjectDirectory directory = byType.get(subjectType);
        if (directory == null) {
            throw new IllegalStateException(
                    "No ReviewSubjectDirectory is registered for " + subjectType);
        }
        return directory.describe(projectId, subjectId);
    }
}
