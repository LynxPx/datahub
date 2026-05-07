package io.datahub.ownership.auth;

import com.datahub.authorization.AuthorizationRequest;
import com.datahub.authorization.AuthorizationResult;
import com.datahub.authorization.AuthorizerContext;
import com.datahub.plugins.auth.authorization.Authorizer;
import com.linkedin.common.urn.Urn;
import io.datahub.ownership.admin.AdminBypass;

import javax.annotation.Nonnull;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

public class OwnershipAuthorizer implements Authorizer {

    private static final Set<String> DEFAULT_GATED_PRIVILEGES = Set.of(
        "VIEW_ENTITY_PAGE", "GET_ENTITY", "VIEW_DATASET_USAGE", "VIEW_DATASET_PROFILE"
    );

    private Function<String, Set<String>> ownershipResolver;
    private Function<Urn, List<Urn>> groupsResolver;
    private AdminBypass adminBypass;
    private Set<String> gatedPrivileges = DEFAULT_GATED_PRIVILEGES;

    @Override
    public void init(@Nonnull Map<String, Object> authorizerConfig, @Nonnull AuthorizerContext ctx) {
        // Wired in Task 10. Setters allow tests to drive behavior in the meantime.
    }

    void setOwnershipResolver(Function<String, Set<String>> r) {
        this.ownershipResolver = r;
    }

    void setGroupsResolver(Function<Urn, List<Urn>> r) {
        this.groupsResolver = r;
    }

    void setAdminBypass(AdminBypass b) {
        this.adminBypass = b;
    }

    void setGatedPrivileges(Set<String> p) {
        this.gatedPrivileges = p;
    }

    @Override
    public AuthorizationResult authorize(@Nonnull AuthorizationRequest request) {
        if (!gatedPrivileges.contains(request.getPrivilege())) {
            return new AuthorizationResult(request, AuthorizationResult.Type.ALLOW, "Not ownership-gated");
        }
        if (request.getResourceSpec().isEmpty()) {
            return new AuthorizationResult(request, AuthorizationResult.Type.ALLOW, "No resource");
        }

        Urn actor;
        try {
            actor = Urn.createFromString(request.getActorUrn());
        } catch (Exception e) {
            return new AuthorizationResult(request, AuthorizationResult.Type.DENY, "Bad actor URN");
        }

        List<Urn> groups = groupsResolver.apply(actor);
        if (adminBypass.isAdmin(actor, groups)) {
            return new AuthorizationResult(request, AuthorizationResult.Type.ALLOW, "Admin bypass");
        }

        String entityUrn = request.getResourceSpec().get().getEntity();
        Set<String> owners = ownershipResolver.apply(entityUrn);
        if (owners.contains(actor.toString())) {
            return new AuthorizationResult(request, AuthorizationResult.Type.ALLOW, "Direct owner");
        }
        for (Urn g : groups) {
            if (owners.contains(g.toString())) {
                return new AuthorizationResult(request, AuthorizationResult.Type.ALLOW, "Group owner");
            }
        }
        return new AuthorizationResult(request, AuthorizationResult.Type.DENY, "Not owner");
    }
}
