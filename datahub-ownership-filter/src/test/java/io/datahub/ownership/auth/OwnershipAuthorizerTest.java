package io.datahub.ownership.auth;

import com.datahub.authorization.AuthorizationRequest;
import com.datahub.authorization.AuthorizationResult;
import com.datahub.authorization.EntitySpec;
import io.datahub.ownership.admin.AdminBypass;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class OwnershipAuthorizerTest {

    @Test
    void deniesNonOwnerEntityRead() {
        OwnershipAuthorizer auth = new OwnershipAuthorizer();
        auth.setOwnershipResolver(urn -> Set.of("urn:li:corpuser:owner"));
        auth.setAdminBypass(new AdminBypass(Set.of(), Set.of()));
        auth.setGroupsResolver(actor -> List.of());

        AuthorizationRequest req = new AuthorizationRequest(
            "urn:li:corpuser:alice",
            "VIEW_ENTITY_PAGE",
            Optional.of(new EntitySpec("dataset", "urn:li:dataset:(urn:li:dataPlatform:mysql,a.b,PROD)")),
            Collections.emptyList());

        assertThat(auth.authorize(req).getType()).isEqualTo(AuthorizationResult.Type.DENY);
    }

    @Test
    void allowsOwnerEntityRead() {
        OwnershipAuthorizer auth = new OwnershipAuthorizer();
        auth.setOwnershipResolver(urn -> Set.of("urn:li:corpuser:alice"));
        auth.setAdminBypass(new AdminBypass(Set.of(), Set.of()));
        auth.setGroupsResolver(actor -> List.of());

        AuthorizationRequest req = new AuthorizationRequest(
            "urn:li:corpuser:alice",
            "VIEW_ENTITY_PAGE",
            Optional.of(new EntitySpec("dataset", "urn:li:dataset:(urn:li:dataPlatform:mysql,a.b,PROD)")),
            Collections.emptyList());

        assertThat(auth.authorize(req).getType()).isEqualTo(AuthorizationResult.Type.ALLOW);
    }

    @Test
    void allowsNonOwnershipGatedPrivileges() {
        OwnershipAuthorizer auth = new OwnershipAuthorizer();
        auth.setOwnershipResolver(urn -> Set.of());
        auth.setAdminBypass(new AdminBypass(Set.of(), Set.of()));
        auth.setGroupsResolver(actor -> List.of());

        AuthorizationRequest req = new AuthorizationRequest(
            "urn:li:corpuser:alice",
            "MANAGE_USERS_AND_GROUPS",
            Optional.empty(),
            Collections.emptyList());

        assertThat(auth.authorize(req).getType()).isNotEqualTo(AuthorizationResult.Type.DENY);
    }
}
