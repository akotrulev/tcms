package com.tcms.repository;

import com.tcms.project.Cardinality;
import com.tcms.project.ClassificationDimensionView;
import com.tcms.project.ClassificationValueView;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The classification a project offers, indexed for the two questions a write path asks of it:
 * <em>may this case carry that value</em>, and <em>which axis does it sit on</em>.
 *
 * <h2>Built from the served vocabulary, not from a query of its own</h2>
 *
 * <p>The input is exactly what {@code GET /projects/&#123;id&#125;/classification} returns and what
 * {@link CaseRenderer#vocabularies} already resolves for every render — company-level dimensions
 * unioned with this project's, each carrying its <strong>live</strong> values. So a write validates
 * against the same list the editor was shown, and there is no second definition of "available here"
 * that could drift from the one the client picked from.
 *
 * <p>That is also the only reason this can be a pure value with no repository: the caller holds the
 * vocabulary because it needs it to render the response anyway, so validating costs no query.
 *
 * <h2>What being absent from here means</h2>
 *
 * <p>Three different mistakes land in the same place, and all three must be a field error rather
 * than a constraint violation:
 *
 * <ul>
 *   <li>a value <strong>retired</strong> since the editor loaded — soft-deleted, so the foreign key
 *       still accepts it;</li>
 *   <li>a value belonging to a <strong>sibling project's</strong> Feature dimension — same company,
 *       so it passes the FK, passes RLS, and passes the V2 trigger's company and dimension checks,
 *       and is stored. Every later read of the case then resolves a classification through a
 *       project the reader holds no grant on. The same argument {@code suiteId} makes, and the one
 *       §64 had to make for {@code sharedStepId} after it had been persisted unchecked since M1 —
 *       this is the third column in the family, and the last one on this request;</li>
 *   <li>a value that <strong>names nothing at all</strong> — which the FK does refuse, at the cost
 *       of a 500 where a message on the picker belongs.</li>
 * </ul>
 *
 * <p>They are deliberately not distinguished in the message. Telling a caller that an id they may
 * not use exists is the same disclosure {@code 404-never-403} exists to avoid (plan §6t), and the
 * caller's next action — pick a value this project offers — is identical in all three cases.
 */
record ClassificationVocabulary(
        Map<UUID, UUID> dimensionByValue,
        Set<UUID> singleCardinalityDimensions,
        Map<UUID, String> dimensionNames) {

    /** An empty vocabulary — every submitted value is refused, and no value is submittable. */
    static ClassificationVocabulary none() {
        return new ClassificationVocabulary(Map.of(), Set.of(), Map.of());
    }

    static ClassificationVocabulary of(List<ClassificationDimensionView> dimensions) {
        Map<UUID, UUID> dimensionByValue = new HashMap<>();
        Set<UUID> single = new HashSet<>();
        Map<UUID, String> names = new HashMap<>();

        for (ClassificationDimensionView dimension : dimensions) {
            names.put(dimension.id(), dimension.name());
            if (dimension.cardinality() == Cardinality.SINGLE) {
                single.add(dimension.id());
            }
            for (ClassificationValueView value : dimension.values()) {
                dimensionByValue.put(value.id(), dimension.id());
            }
        }
        return new ClassificationVocabulary(dimensionByValue, single, names);
    }

    /** The axis a value sits on, or null when this project does not offer the value at all. */
    UUID dimensionOf(UUID valueId) {
        return dimensionByValue.get(valueId);
    }

    boolean allowsOnlyOneValue(UUID dimensionId) {
        return singleCardinalityDimensions.contains(dimensionId);
    }

    /**
     * The dimension's display name, for the message that refuses a second value on it.
     *
     * <p>The name rather than the key: it is what the control is labelled with, and a client may
     * have renamed it (the two locked dimensions are locked against deletion, not renaming), so the
     * key would name something the reader cannot see on their own screen.
     */
    String nameOf(UUID dimensionId) {
        return dimensionNames.getOrDefault(dimensionId, "this dimension");
    }

    /**
     * Every value this project currently offers, across all its dimensions.
     *
     * <p>This is the <strong>bound of a reconcile</strong>, which is the subtle half of the write
     * path — see {@link CaseClassifications}. An assignment whose value is not in here is one the
     * caller was never served and therefore could not have resubmitted, so its absence from a
     * payload carries no instruction.
     */
    Set<UUID> liveValueIds() {
        return dimensionByValue.keySet();
    }
}
