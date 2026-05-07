package io.datahub.ownership.filter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class OwnershipFilterBuilder {

    private static final String OWNERS_FIELD = "owners";

    @Nonnull
    public List<Map<String, Object>> injectOwnershipFilter(
            @Nullable List<Map<String, Object>> existing,
            @Nonnull String actorUrn,
            @Nonnull Collection<String> groupUrns) {

        Map<String, Object> ownership = buildOwnershipFacet(actorUrn, groupUrns);

        if (existing == null || existing.isEmpty()) {
            Map<String, Object> single = new LinkedHashMap<>();
            single.put("and", new ArrayList<>(List.of(ownership)));
            List<Map<String, Object>> out = new ArrayList<>();
            out.add(single);
            return out;
        }

        List<Map<String, Object>> result = new ArrayList<>(existing.size());
        for (Map<String, Object> disjunct : existing) {
            List<Map<String, Object>> oldAnd =
                (List<Map<String, Object>>) disjunct.getOrDefault("and", List.of());
            List<Map<String, Object>> newAnd = new ArrayList<>(oldAnd.size() + 1);
            newAnd.addAll(oldAnd);
            newAnd.add(ownership);
            Map<String, Object> newDisjunct = new LinkedHashMap<>(disjunct);
            newDisjunct.put("and", newAnd);
            result.add(newDisjunct);
        }
        return result;
    }

    private Map<String, Object> buildOwnershipFacet(String actor, Collection<String> groups) {
        List<String> values = new ArrayList<>(1 + groups.size());
        values.add(actor);
        values.addAll(groups);
        Map<String, Object> facet = new LinkedHashMap<>();
        facet.put("field", OWNERS_FIELD);
        facet.put("condition", "EQUAL");
        facet.put("values", values);
        return facet;
    }
}
