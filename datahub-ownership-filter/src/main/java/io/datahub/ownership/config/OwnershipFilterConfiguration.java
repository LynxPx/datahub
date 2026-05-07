package io.datahub.ownership.config;

import com.datahub.authentication.group.GroupService;
import com.linkedin.common.urn.Urn;
import graphql.GraphQL;
import graphql.execution.instrumentation.ChainedInstrumentation;
import graphql.execution.instrumentation.Instrumentation;
import io.datahub.ownership.admin.AdminBypass;
import io.datahub.ownership.filter.OwnershipFilterBuilder;
import io.datahub.ownership.group.CachedGroupResolver;
import io.datahub.ownership.instrumentation.FieldArgumentMutators;
import io.datahub.ownership.instrumentation.OwnershipInstrumentation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

@Configuration
public class OwnershipFilterConfiguration {

    private static final Logger log = LoggerFactory.getLogger(OwnershipFilterConfiguration.class);

    // ===== Helper beans =====

    @Bean
    public OwnershipFilterBuilder ownershipFilterBuilder() {
        return new OwnershipFilterBuilder();
    }

    @Bean
    public FieldArgumentMutators fieldArgumentMutators() {
        return new FieldArgumentMutators();
    }

    @Bean
    public CachedGroupResolver cachedGroupResolver(GroupService groupService) {
        return new CachedGroupResolver(groupService);
    }

    @Bean
    public AdminBypass adminBypass(
            @Value("${ownership.filter.adminUserUrns:urn:li:corpuser:datahub}") String adminUsers,
            @Value("${ownership.filter.adminGroupUrns:urn:li:corpGroup:admins}") String adminGroups) {
        return new AdminBypass(parseUrns(adminUsers), parseUrns(adminGroups));
    }

    private Set<Urn> parseUrns(String csv) {
        if (csv == null || csv.isBlank()) return Set.of();
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(s -> {
                    try {
                        return Urn.createFromString(s);
                    } catch (Exception e) {
                        throw new RuntimeException("Invalid URN in ownership filter config: " + s, e);
                    }
                })
                .collect(Collectors.toCollection(HashSet::new));
    }

    @Bean
    public OwnershipInstrumentation ownershipInstrumentation(
            OwnershipFilterBuilder builder,
            FieldArgumentMutators mutators,
            CachedGroupResolver groups,
            AdminBypass admins) {
        return new OwnershipInstrumentation(builder, mutators, groups, admins);
    }

    @Bean
    public WrapStatus wrapStatus() {
        return new WrapStatus();
    }

    public static class WrapStatus {
        private final AtomicBoolean success = new AtomicBoolean(false);

        public boolean isSuccess() {
            return success.get();
        }

        public void markSuccess() {
            success.set(true);
        }
    }

    // ===== The single intercept =====

    @Bean
    public StartupValidator startupValidator(WrapStatus wrapStatus) {
        return new StartupValidator(wrapStatus);
    }

    @Bean
    public static BeanPostProcessor graphQLEngineWrapper(
            org.springframework.beans.factory.ObjectProvider<OwnershipInstrumentation> ownershipInstrumentationProvider,
            org.springframework.beans.factory.ObjectProvider<WrapStatus> wrapStatusProvider) {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (!"graphQLEngine".equals(beanName)) return bean;
                try {
                    // Lazy-resolve our beans only when the graphQLEngine bean is actually being
                    // post-processed. This prevents eager creation of our deps (and the entire
                    // bean graph behind them) before infrastructure beans like metrics are ready.
                    OwnershipInstrumentation ownershipInstrumentation = ownershipInstrumentationProvider.getObject();
                    WrapStatus wrapStatus = wrapStatusProvider.getObject();

                    // Read the existing GraphQL via the public getter — no reflection needed.
                    GraphQL original = invokeGetGraphQL(bean);
                    Instrumentation existing = original.getInstrumentation();
                    Instrumentation chained = new ChainedInstrumentation(
                            List.of(existing, ownershipInstrumentation));
                    GraphQL replacement = original.transform(b -> b.instrumentation(chained));

                    // Write back via reflection — no setter exists on GraphQLEngine.
                    Field graphQLField = findGraphQLField(bean.getClass());
                    graphQLField.setAccessible(true);
                    graphQLField.set(bean, replacement);

                    wrapStatus.markSuccess();
                    log.info("OwnershipInstrumentation installed on graphQLEngine bean");
                } catch (Exception e) {
                    log.error("Failed to install OwnershipInstrumentation — search will NOT be filtered", e);
                    throw new IllegalStateException(
                            "OwnershipInstrumentation install failed; refusing to start with broken security", e);
                }
                return bean;
            }

            private GraphQL invokeGetGraphQL(Object engine) throws Exception {
                return (GraphQL) engine.getClass().getMethod("getGraphQL").invoke(engine);
            }

            private Field findGraphQLField(Class<?> cls) throws NoSuchFieldException {
                Class<?> c = cls;
                while (c != null && c != Object.class) {
                    for (Field f : c.getDeclaredFields()) {
                        if (GraphQL.class.isAssignableFrom(f.getType())) return f;
                    }
                    c = c.getSuperclass();
                }
                throw new NoSuchFieldException("No graphql.GraphQL field found on " + cls.getName()
                        + " or any of its superclasses");
            }
        };
    }
}
