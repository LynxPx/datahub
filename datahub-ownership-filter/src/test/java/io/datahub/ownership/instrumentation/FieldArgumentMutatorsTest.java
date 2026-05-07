package io.datahub.ownership.instrumentation;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class FieldArgumentMutatorsTest {

    private final FieldArgumentMutators mutators = new FieldArgumentMutators();

    @Test
    void registryCoversExpectedFields() {
        assertThat(mutators.isOwnershipGated("searchAcrossEntities")).isTrue();
        assertThat(mutators.isOwnershipGated("scrollAcrossEntities")).isTrue();
        assertThat(mutators.isOwnershipGated("searchAcrossLineage")).isTrue();
        assertThat(mutators.isOwnershipGated("scrollAcrossLineage")).isTrue();
        assertThat(mutators.isOwnershipGated("autoComplete")).isTrue();
        assertThat(mutators.isOwnershipGated("autoCompleteForMultiple")).isTrue();
        assertThat(mutators.isOwnershipGated("browse")).isTrue();
        assertThat(mutators.isOwnershipGated("browseV2")).isTrue();
        assertThat(mutators.isOwnershipGated("aggregateAcrossEntities")).isTrue();
        assertThat(mutators.isOwnershipGated("search")).isTrue();
        assertThat(mutators.isOwnershipGated("dataset")).isFalse();
        assertThat(mutators.isOwnershipGated("me")).isFalse();
    }

    @Test
    void mutatesOrFiltersForDisjunctiveField() {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("query", "*");
        List<Map<String, Object>> ownershipFilter = List.of(
            Map.of("and", List.of(Map.of("field", "owners", "condition", "EQUAL",
                "values", List.of("urn:li:corpuser:alice")))));

        mutators.applyOwnershipFilter("searchAcrossEntities", input, ownershipFilter);

        assertThat(input.get("orFilters")).isEqualTo(ownershipFilter);
    }

    @Test
    void mergesIntoExistingOrFilters() {
        Map<String, Object> input = new LinkedHashMap<>();
        List<Map<String, Object>> existing = new java.util.ArrayList<>();
        existing.add(Map.of("and", new java.util.ArrayList<>(List.of(
            Map.of("field", "platform", "condition", "EQUAL", "values", List.of("snowflake"))))));
        input.put("orFilters", existing);

        List<Map<String, Object>> ownershipFilter = List.of(
            Map.of("and", List.of(Map.of("field", "owners", "condition", "EQUAL",
                "values", List.of("urn:li:corpuser:alice")))));

        mutators.applyOwnershipFilter("searchAcrossEntities", input, ownershipFilter);

        // Existing disjunct gets ownership AND'd in
        List<Map<String, Object>> resultOr = (List<Map<String, Object>>) input.get("orFilters");
        assertThat(resultOr).hasSize(1);
        List<Map<String, Object>> ands = (List<Map<String, Object>>) resultOr.get(0).get("and");
        assertThat(ands).hasSize(2);
        assertThat(ands.get(1).get("field")).isEqualTo("owners");
    }

    @Test
    void mutatesFiltersForConjunctiveField() {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("query", "abc");

        List<Map<String, Object>> ownershipFilter = List.of(
            Map.of("and", List.of(Map.of("field", "owners", "condition", "EQUAL",
                "values", List.of("urn:li:corpuser:alice")))));

        mutators.applyOwnershipFilter("autoComplete", input, ownershipFilter);

        List<Map<String, Object>> filters = (List<Map<String, Object>>) input.get("filters");
        assertThat(filters).hasSize(1);
        assertThat(filters.get(0).get("field")).isEqualTo("owners");
    }
}
