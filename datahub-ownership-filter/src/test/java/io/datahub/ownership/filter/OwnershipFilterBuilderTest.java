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
    void buildsOwnershipOnlyFilter() {
        List<Map<String, Object>> result = new OwnershipFilterBuilder()
            .injectOwnershipFilter(alice, List.of(g1, g2));

        assertThat(result).hasSize(1);
        List<Map<String, Object>> ands = (List<Map<String, Object>>) result.get(0).get("and");
        assertThat(ands).hasSize(1);
        Map<String, Object> facet = ands.get(0);
        assertThat(facet.get("field")).isEqualTo("owners");
        assertThat(facet.get("condition")).isEqualTo("EQUAL");
        assertThat((List<String>) facet.get("values")).containsExactlyInAnyOrder(alice, g1, g2);
    }

    @Test
    void includesOnlyActorWhenNoGroups() {
        List<Map<String, Object>> result = new OwnershipFilterBuilder()
            .injectOwnershipFilter(alice, List.of());

        Map<String, Object> facet = ((List<Map<String, Object>>) result.get(0).get("and")).get(0);
        assertThat((List<String>) facet.get("values")).containsExactly(alice);
    }
}
