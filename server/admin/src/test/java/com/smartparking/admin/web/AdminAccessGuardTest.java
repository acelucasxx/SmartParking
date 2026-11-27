package com.smartparking.admin.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartparking.common.web.AdminPrincipal;
import com.smartparking.common.web.AdminRole;
import com.smartparking.common.web.AdminRoles;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.web.method.HandlerMethod;

/** 启动检查：后台接口漏标角色、角色为空、在后台之外声明 AdminPrincipal 都要被发现 */
class AdminAccessGuardTest {

    @SuppressWarnings("unused")
    static class Endpoints {
        @AdminRoles(AdminRole.OPS)
        public void annotated() {}

        public void bare() {}

        @AdminRoles({})
        public void empty() {}

        public void wantsAdmin(AdminPrincipal admin) {}
    }

    @AdminRoles(AdminRole.SUPPORT)
    @SuppressWarnings("unused")
    static class AnnotatedType {
        public void inherits() {}
    }

    private static HandlerMethod method(Object bean, String name, Class<?>... params) throws Exception {
        return new HandlerMethod(bean, bean.getClass().getMethod(name, params));
    }

    @Test
    void annotatedMethodOrTypePasses() throws Exception {
        assertThat(AdminAccessGuard.problems(Set.of("/api/admin/devices"), method(new Endpoints(), "annotated")))
                .isEmpty();
        HandlerMethod inherits = method(new AnnotatedType(), "inherits");
        assertThat(AdminAccessGuard.problems(Set.of("/api/admin/users"), inherits))
                .isEmpty();
        assertThat(AdminAccessGuard.roles(inherits).value()).containsExactly(AdminRole.SUPPORT);
    }

    @Test
    void missingOrEmptyRolesAreReported() throws Exception {
        assertThat(AdminAccessGuard.problems(Set.of("/api/admin/devices"), method(new Endpoints(), "bare")))
                .singleElement()
                .asString()
                .contains("缺少 @AdminRoles");
        assertThat(AdminAccessGuard.problems(Set.of("/api/admin/devices"), method(new Endpoints(), "empty")))
                .hasSize(1);
    }

    @Test
    void loginAndNonAdminPathsAreExempt() throws Exception {
        assertThat(AdminAccessGuard.problems(Set.of("/api/admin/auth/login"), method(new Endpoints(), "bare")))
                .isEmpty();
        assertThat(AdminAccessGuard.problems(
                        Set.of("/api/app/me", "/internal/emqx/authn"), method(new Endpoints(), "bare")))
                .isEmpty();
    }

    @Test
    void adminPrincipalOutsideAdminPathsIsReported() throws Exception {
        assertThat(AdminAccessGuard.problems(
                        Set.of("/api/app/x"), method(new Endpoints(), "wantsAdmin", AdminPrincipal.class)))
                .singleElement()
                .asString()
                .contains("AdminPrincipal");
    }
}
