package io.datahub.ownership.instrumentation;

import com.linkedin.common.urn.Urn;
import com.linkedin.datahub.graphql.QueryContext;
import graphql.execution.ExecutionStepInfo;
import graphql.execution.instrumentation.InstrumentationState;
import graphql.execution.instrumentation.parameters.InstrumentationFieldFetchParameters;
import graphql.schema.DataFetcher;
import graphql.schema.DataFetchingEnvironment;
import graphql.schema.DataFetchingEnvironmentImpl;
import graphql.schema.GraphQLFieldDefinition;
import io.datahubproject.metadata.context.OperationContext;
import io.datahub.ownership.admin.AdminBypass;
import io.datahub.ownership.filter.OwnershipFilterBuilder;
import io.datahub.ownership.group.CachedGroupResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class OwnershipInstrumentationTest {

    private final Urn alice = urn("urn:li:corpuser:alice");
    private final Urn bob = urn("urn:li:corpuser:bob");
    private final Urn datahub = urn("urn:li:corpuser:datahub");
    private final Urn g1 = urn("urn:li:corpGroup:g1");

    private CachedGroupResolver groups;
    private AdminBypass admins;
    private OwnershipInstrumentation instrumentation;

    static Urn urn(String s) {
        try {
            return Urn.createFromString(s);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        groups = mock(CachedGroupResolver.class);
        admins = new AdminBypass(Set.of(datahub), Set.of());
        instrumentation = new OwnershipInstrumentation(
                new OwnershipFilterBuilder(), new FieldArgumentMutators(), groups, admins);

        when(groups.groupsFor(any(), eq(alice))).thenReturn(List.of(g1));
        when(groups.groupsFor(any(), eq(bob))).thenReturn(List.of());
        when(groups.groupsFor(any(), eq(datahub))).thenReturn(List.of());
    }

    @Test
    void wrapsGatedFieldFetchersOnly() {
        DataFetcher<?> raw = env -> "result";
        DataFetcher<?> wrappedSearch = instrumentation.instrumentDataFetcher(raw,
                fieldFetchParams("searchAcrossEntities"), null);
        DataFetcher<?> wrappedDataset = instrumentation.instrumentDataFetcher(raw,
                fieldFetchParams("dataset"), null);

        assertThat(wrappedSearch).isNotSameAs(raw);
        assertThat(wrappedDataset).isSameAs(raw);
    }

    @Test
    void differentActorsProduceDifferentMutations() throws Exception {
        DataFetcher<?> capturingFetcher = env -> {
            Map<String, Object> input = (Map<String, Object>) env.getArgument("input");
            return new LinkedHashMap<>(input);
        };

        DataFetcher<?> wrapped = instrumentation.instrumentDataFetcher(capturingFetcher,
                fieldFetchParams("searchAcrossEntities"), null);

        Map<String, Object> aliceArgs = (Map<String, Object>) wrapped.get(envFor(alice, baseInput()));
        Map<String, Object> bobArgs = (Map<String, Object>) wrapped.get(envFor(bob, baseInput()));

        List<Map<String, Object>> aliceOr = (List<Map<String, Object>>) aliceArgs.get("orFilters");
        List<Map<String, Object>> bobOr = (List<Map<String, Object>>) bobArgs.get("orFilters");

        List<String> aliceVals = (List<String>)
                ((List<Map<String, Object>>) aliceOr.get(0).get("and")).get(0).get("values");
        List<String> bobVals = (List<String>)
                ((List<Map<String, Object>>) bobOr.get(0).get("and")).get(0).get("values");

        assertThat(aliceVals).containsExactlyInAnyOrder(alice.toString(), g1.toString());
        assertThat(bobVals).containsExactly(bob.toString());
    }

    @Test
    void adminBypassesFilterInjection() throws Exception {
        AtomicReference<Map<String, Object>> seen = new AtomicReference<>();
        DataFetcher<?> capturing = env -> {
            seen.set(new LinkedHashMap<>((Map<String, Object>) env.getArgument("input")));
            return null;
        };

        DataFetcher<?> wrapped = instrumentation.instrumentDataFetcher(capturing,
                fieldFetchParams("searchAcrossEntities"), null);

        wrapped.get(envFor(datahub, baseInput()));

        assertThat(seen.get().get("orFilters")).isNull();
    }

    @Test
    void propagatesGroupResolverException() throws Exception {
        RuntimeException boom = new RuntimeException("group service unavailable");
        CachedGroupResolver failingGroups = mock(CachedGroupResolver.class);
        when(failingGroups.groupsFor(any(), any())).thenThrow(boom);

        OwnershipInstrumentation inst = new OwnershipInstrumentation(
                new OwnershipFilterBuilder(), new FieldArgumentMutators(), failingGroups, admins);

        DataFetcher<?> wrapped = inst.instrumentDataFetcher(
                env -> "should not be called",
                fieldFetchParams("searchAcrossEntities"),
                null);

        // Fail-closed: group-service outage propagates as an exception rather than
        // silently allowing unfiltered access.
        assertThatThrownBy(() -> wrapped.get(envFor(alice, baseInput())))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("group service unavailable");
    }

    // ----- Helpers -----

    private InstrumentationFieldFetchParameters fieldFetchParams(String fieldName) {
        InstrumentationFieldFetchParameters params = mock(InstrumentationFieldFetchParameters.class);
        ExecutionStepInfo executionStepInfo = mock(ExecutionStepInfo.class);
        GraphQLFieldDefinition fieldDef = mock(GraphQLFieldDefinition.class);
        when(fieldDef.getName()).thenReturn(fieldName);
        when(executionStepInfo.getFieldDefinition()).thenReturn(fieldDef);
        when(params.getExecutionStepInfo()).thenReturn(executionStepInfo);
        return params;
    }

    private Map<String, Object> baseInput() {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("query", "*");
        return input;
    }

    private DataFetchingEnvironment envFor(Urn actor, Map<String, Object> input) {
        OperationContext opCtx = mock(OperationContext.class);
        QueryContext qc = mock(QueryContext.class);
        when(qc.getOperationContext()).thenReturn(opCtx);
        when(qc.getActorUrn()).thenReturn(actor.toString());

        var graphQlContext = graphql.GraphQLContext.newContext()
                .put(QueryContext.class, qc)
                .build();

        return DataFetchingEnvironmentImpl.newDataFetchingEnvironment()
                .arguments(Map.of("input", input))
                .graphQLContext(graphQlContext)
                .build();
    }
}
