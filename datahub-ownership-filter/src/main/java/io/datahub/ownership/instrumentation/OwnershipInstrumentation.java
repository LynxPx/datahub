package io.datahub.ownership.instrumentation;

import com.linkedin.common.urn.Urn;
import com.linkedin.datahub.graphql.QueryContext;
import graphql.execution.instrumentation.InstrumentationState;
import graphql.execution.instrumentation.SimplePerformantInstrumentation;
import graphql.execution.instrumentation.parameters.InstrumentationFieldFetchParameters;
import graphql.schema.DataFetcher;
import graphql.schema.DataFetchingEnvironment;
import graphql.schema.DataFetchingEnvironmentImpl;
import io.datahub.ownership.admin.AdminBypass;
import io.datahub.ownership.filter.OwnershipFilterBuilder;
import io.datahub.ownership.group.CachedGroupResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class OwnershipInstrumentation extends SimplePerformantInstrumentation {

    private static final Logger log = LoggerFactory.getLogger(OwnershipInstrumentation.class);

    /**
     * Navigational / structural entity types that are NOT ownership-gated. When a query is scoped
     * exclusively to these types we skip filter injection entirely, so domain, platform, glossary,
     * and tag cards / pickers / search results always render regardless of who owns what. Data
     * assets (datasets, dashboards, charts, jobs, ML entities, containers, …) are still filtered;
     * unowned ones become visible to everyone via the no-owners branch of the ownership predicate.
     */
    private static final Set<String> STRUCTURAL_ENTITY_TYPES = Set.of(
        "DOMAIN",
        "DATA_PLATFORM",
        "DATA_PLATFORM_INSTANCE",
        "GLOSSARY_TERM",
        "GLOSSARY_NODE",
        "TAG");

    private final OwnershipFilterBuilder filterBuilder;
    private final FieldArgumentMutators mutators;
    private final CachedGroupResolver groupResolver;
    private final AdminBypass adminBypass;

    public OwnershipInstrumentation(
            @Nonnull OwnershipFilterBuilder filterBuilder,
            @Nonnull FieldArgumentMutators mutators,
            @Nonnull CachedGroupResolver groupResolver,
            @Nonnull AdminBypass adminBypass) {
        this.filterBuilder = filterBuilder;
        this.mutators = mutators;
        this.groupResolver = groupResolver;
        this.adminBypass = adminBypass;
    }

    @Override
    public DataFetcher<?> instrumentDataFetcher(
            DataFetcher<?> dataFetcher,
            InstrumentationFieldFetchParameters parameters,
            @Nullable InstrumentationState state) {
        String fieldName = parameters.getExecutionStepInfo().getFieldDefinition().getName();
        if (!mutators.isOwnershipGated(fieldName)) {
            return dataFetcher;
        }
        return env -> intercept(dataFetcher, env, fieldName);
    }

    private Object intercept(DataFetcher<?> original, DataFetchingEnvironment env, String fieldName)
            throws Exception {
        QueryContext qc = env.getGraphQlContext().get(QueryContext.class);
        if (qc == null) {
            log.warn("OwnershipInstrumentation: no QueryContext on field {}; passing through", fieldName);
            return original.get(env);
        }

        Urn actor = Urn.createFromString(qc.getActorUrn());
        // Intentionally not caught: fail-closed. A transient group-service outage surfaces as
        // a GraphQL error rather than silently granting unfiltered access.
        List<Urn> groups = groupResolver.groupsFor(qc.getOperationContext(), actor);

        if (adminBypass.isAdmin(actor, groups)) {
            return original.get(env);
        }

        Object inputObj = env.getArgument("input");
        if (!(inputObj instanceof Map)) {
            log.warn("OwnershipInstrumentation: field {} has no Map 'input' arg; passing through", fieldName);
            return original.get(env);
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> input = new LinkedHashMap<>((Map<String, Object>) inputObj);

        // Navigational entity types (domains, platforms, glossary, tags) are not ownership-gated.
        // If the query targets only those, leave it untouched so the cards/results still render.
        if (isStructuralOnly(input)) {
            return original.get(env);
        }

        List<String> groupStrings = groups.stream().map(Urn::toString).toList();
        List<Map<String, Object>> ownershipFilter =
                filterBuilder.injectOwnershipFilter(actor.toString(), groupStrings);

        mutators.applyOwnershipFilter(fieldName, input, ownershipFilter);

        Map<String, Object> newArgs = new LinkedHashMap<>(env.getArguments());
        newArgs.put("input", input);

        DataFetchingEnvironment mutatedEnv = DataFetchingEnvironmentImpl
                .newDataFetchingEnvironment(env)
                .arguments(newArgs)
                .build();

        return original.get(mutatedEnv);
    }

    /**
     * Returns true when the request is scoped to entity types that are ALL structural/navigational
     * (so ownership filtering should be skipped). Reads {@code types} (a list, used by
     * searchAcrossEntities, aggregateAcrossEntities, autoCompleteForMultiple, …) and {@code type}
     * (a single value, used by autoComplete, browse, browseV2, search).
     *
     * <p>Returns false when no entity type is specified — an unscoped query spans data assets and
     * must be filtered.
     */
    private boolean isStructuralOnly(@Nonnull Map<String, Object> input) {
        List<String> types = new ArrayList<>();
        Object typesObj = input.get("types");
        if (typesObj instanceof List<?> list) {
            for (Object t : list) {
                if (t != null) types.add(String.valueOf(t));
            }
        }
        Object typeObj = input.get("type");
        if (typeObj != null) {
            types.add(String.valueOf(typeObj));
        }
        if (types.isEmpty()) {
            return false;
        }
        for (String t : types) {
            if (!STRUCTURAL_ENTITY_TYPES.contains(t)) {
                return false;
            }
        }
        return true;
    }
}
