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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class OwnershipInstrumentation extends SimplePerformantInstrumentation {

    private static final Logger log = LoggerFactory.getLogger(OwnershipInstrumentation.class);

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
}
