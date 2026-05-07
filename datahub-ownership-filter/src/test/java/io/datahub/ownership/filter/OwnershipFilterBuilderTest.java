package io.datahub.ownership.filter;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OwnershipFilterBuilderTest {

    private final String alice = "urn:li:corpuser:alice";
    private final String g1 = "urn:li:corpGroup:g1";
    private final String g2 = "urn:li:corpGroup:g2";

    @Test
    void buildsOwnershipOnlyFilterFromNull() {
        List<Map<String, Object>> result = new OwnershipFilterBuilder()
            .injectOwnershipFilter(null, alice, List.of(g1, g2));

        assertThat(result).hasSize(1);
        List<Map<String, Object>> ands = (List<Map<String, Object>>) result.get(0).get("and");
        assertThat(ands).hasSize(1);
        Map<String, Object> facet = ands.get(0);
        assertThat(facet.get("field")).isEqualTo("owners");
        assertThat(facet.get("condition")).isEqualTo("EQUAL");
        assertThat((List<String>) facet.get("values")).containsExactlyInAnyOrder(alice, g1, g2);
    }

    @Test
    void andsOwnershipIntoEveryDisjunct() {
        Map<String, Object> existing1 = Map.of("and", List.of(
            Map.of("field", "platform", "condition", "EQUAL", "values", List.of("snowflake"))));
        Map<String, Object> existing2 = Map.of("and", List.of(
            Map.of("field", "origin", "condition", "EQUAL", "values", List.of("PROD"))));

        List<Map<String, Object>> result = new OwnershipFilterBuilder()
            .injectOwnershipFilter(List.of(existing1, existing2), alice, List.of(g1));

        assertThat(result).hasSize(2);
        for (Map<String, Object> disjunct : result) {
            List<Map<String, Object>> ands = (List<Map<String, Object>>) disjunct.get("and");
            assertThat(ands).hasSize(2);
            assertThat(ands.get(1).get("field")).isEqualTo("owners");
        }
    }

    @Test
    void includesOnlyActorWhenNoGroups() {
        List<Map<String, Object>> result = new OwnershipFilterBuilder()
            .injectOwnershipFilter(null, alice, List.of());

        Map<String, Object> facet = ((List<Map<String, Object>>) result.get(0).get("and")).get(0);
        assertThat((List<String>) facet.get("values")).containsExactly(alice);
    }
}
