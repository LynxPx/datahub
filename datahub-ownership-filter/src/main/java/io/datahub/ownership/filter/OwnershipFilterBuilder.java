package io.datahub.ownership.filter;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class OwnershipFilterBuilder {

    private static final String OWNERS_FIELD = "owners";

    @Nonnull
    public List<Map<String, Object>> injectOwnershipFilter(
            @Nonnull String actorUrn,
            @Nonnull Collection<String> groupUrns) {

        Map<String, Object> ownership = buildOwnershipFacet(actorUrn, groupUrns);
        Map<String, Object> single = new LinkedHashMap<>();
        single.put("and", new ArrayList<>(List.of(ownership)));
        List<Map<String, Object>> out = new ArrayList<>();
        out.add(single);
        return out;
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
