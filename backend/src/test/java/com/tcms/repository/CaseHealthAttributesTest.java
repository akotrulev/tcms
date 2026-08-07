package com.tcms.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Every {@link HealthSignal} constant names a real boolean field on {@link CaseHealth}.
 *
 * <h2>This class is named in {@code HealthSignal}'s javadoc and did not exist</h2>
 *
 * <p>That header has said <em>"{@code CaseHealthAttributesTest} asserts every constant resolves"</em>
 * since §47, and nothing did. It is §100's finding in its purest form — <strong>a comment asserting
 * a fact about another file is not a check</strong> — and the reason it went unnoticed for so long
 * is the same reason it matters: the failure it describes is invisible until somebody filters.
 *
 * <p>{@link HealthSignal#attribute()} is a <strong>string</strong>, because its consumers
 * ({@code CaseSpecifications}, {@code CaseSearch}) build Criteria paths and Criteria takes a name.
 * A typo in it compiles, passes {@code -Werror}, passes {@code ddl-auto: validate} — the column is
 * fine, only the Java attribute name is wrong — and then throws
 * {@code IllegalArgumentException: Unable to locate Attribute} at runtime on the one code path that
 * filters by that signal, while the chip, the facet count and the dashboard tile all keep rendering
 * from {@link CaseHealth#signals()}, which is a {@code switch} and cannot be wrong.
 *
 * <p>So the symptom is one broken filter among nine working ones, in a feature nobody exercises
 * every day. M3 slice 5 added the eleventh constant and the tenth attribute, which is what made
 * writing this cheaper than reading it again.
 *
 * <h2>No database and no context</h2>
 *
 * <p>Reflection over the declared fields rather than the JPA metamodel: the question is whether the
 * <em>name</em> resolves, the metamodel is populated from these same fields, and a test that boots
 * Postgres to ask a question answerable from a class object is three minutes of the suite's budget
 * for nothing. {@code ReportParserTest} made the same call in §117.
 */
@DisplayName("HealthSignal — every constant names a real CaseHealth field")
class CaseHealthAttributesTest {

    @Test
    @DisplayName("each attribute resolves to a boolean field on CaseHealth")
    void everyAttributeResolves() {
        for (HealthSignal signal : HealthSignal.values()) {
            Field field = fieldNamed(signal.attribute());

            assertThat(field)
                    .as("%s names attribute '%s', which CaseHealth does not declare — every "
                            + "Criteria filter on this signal throws at runtime and nothing else "
                            + "does", signal, signal.attribute())
                    .isNotNull();
            assertThat(field.getType())
                    .as("%s's attribute must be a boolean; a Criteria isTrue() over anything else "
                            + "fails only when somebody filters", signal)
                    .isEqualTo(boolean.class);
        }
    }

    @Test
    @DisplayName("no two signals share a column")
    void attributesAreDistinct() {
        // Two constants pointing at one field is the failure mode a copy-paste produces, and it is
        // silent in both directions: the filter selects one set of cases while the chips on those
        // cases claim the other signal. HealthSignal's own header names exactly this.
        List<String> attributes =
                Arrays.stream(HealthSignal.values()).map(HealthSignal::attribute).toList();

        assertThat(attributes).doesNotHaveDuplicates();
    }

    private static Field fieldNamed(String name) {
        for (Field field : CaseHealth.class.getDeclaredFields()) {
            if (field.getName().equals(name)) {
                return field;
            }
        }
        return null;
    }
}
