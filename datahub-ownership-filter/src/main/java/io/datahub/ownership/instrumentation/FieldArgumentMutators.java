package io.datahub.ownership.instrumentation;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class FieldArgumentMutators {

    private static final Set<String> DISJUNCTIVE_FIELDS = Set.of(
        "searchAcrossEntities",
        "scrollAcrossEntities",
        "searchAcrossLineage",
        "scrollAcrossLineage",
        "browseV2",
        "aggregateAcrossEntities"
    );

    private static final Set<String> CONJUNCTIVE_FIELDS = Set.of(
        "autoComplete",
        "autoCompleteForMultiple",
        "browse",
        "search"
    );

    public boolean isOwnershipGated(@Nonnull String fieldName) {
        return DISJUNCTIVE_FIELDS.contains(fieldName) || CONJUNCTIVE_FIELDS.contains(fieldName);
    }

    /** Mutates {@code input} in place by injecting the ownership constraint into the appropriate filter slot. */
    public void applyOwnershipFilter(
            @Nonnull String fieldName,
            @Nonnull Map<String, Object> input,
            @Nonnull List<Map<String, Object>> ownershipFilter) {

        if (DISJUNCTIVE_FIELDS.contains(fieldName)) {
            applyToOrFilters(input, ownershipFilter);
        } else if (CONJUNCTIVE_FIELDS.contains(fieldName)) {
            applyToFlatFilters(input, ownershipFilter);
        }
    }

    @SuppressWarnings("unchecked")
    private void applyToOrFilters(Map<String, Object> input, List<Map<String, Object>> ownershipFilter) {
        List<Map<String, Object>> existing = (List<Map<String, Object>>) input.get("orFilters");
        if (existing == null || existing.isEmpty()) {
            input.put("orFilters", new ArrayList<>(ownershipFilter));
            return;
        }
        // ownershipFilter is one disjunct (single AndFilterInput) — append its conjuncts into each existing disjunct.
        List<Map<String, Object>> ownershipAnds = (List<Map<String, Object>>) ownershipFilter.get(0).get("and");
        List<Map<String, Object>> merged = new ArrayList<>(existing.size());
        for (Map<String, Object> disjunct : existing) {
            List<Map<String, Object>> oldAnd =
                (List<Map<String, Object>>) disjunct.getOrDefault("and", List.of());
            List<Map<String, Object>> newAnd = new ArrayList<>(oldAnd.size() + ownershipAnds.size());
            newAnd.addAll(oldAnd);
            newAnd.addAll(ownershipAnds);
            Map<String, Object> newDisjunct = new LinkedHashMap<>(disjunct);
            newDisjunct.put("and", newAnd);
            merged.add(newDisjunct);
        }
        input.put("orFilters", merged);
    }

    @SuppressWarnings("unchecked")
    private void applyToFlatFilters(Map<String, Object> input, List<Map<String, Object>> ownershipFilter) {
        List<Map<String, Object>> existing = (List<Map<String, Object>>) input.get("filters");
        List<Map<String, Object>> ownershipAnds = (List<Map<String, Object>>) ownershipFilter.get(0).get("and");
        List<Map<String, Object>> merged = new ArrayList<>(
            existing == null ? ownershipAnds.size() : existing.size() + ownershipAnds.size());
        if (existing != null) merged.addAll(existing);
        merged.addAll(ownershipAnds);
        input.put("filters", merged);
    }
}
