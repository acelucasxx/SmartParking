package com.smartparking.control.access;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.web.method.HandlerMethod;

/** 启动检查：设备类接口漏标权限注解、注解与路径不匹配都要被发现 */
class DeviceAccessGuardTest {

    @SuppressWarnings("unused")
    static class Endpoints {
        @DeviceAccess(AccessLevel.CONTROL)
        public void guarded() {}

        public void unguarded() {}

        @DeviceAccess(AccessLevel.VIEW)
        public void annotatedWithoutDevice() {}

        public void wantsGrant(DeviceGrant grant) {}
    }

    private static HandlerMethod method(String name, Class<?>... params) throws NoSuchMethodException {
        return new HandlerMethod(new Endpoints(), Endpoints.class.getMethod(name, params));
    }

    @Test
    void annotatedDeviceEndpointPasses() throws Exception {
        assertThat(DeviceAccessGuard.problems(Set.of("/api/app/devices/{deviceId}/commands"), method("guarded")))
                .isEmpty();
    }

    @Test
    void unannotatedDeviceEndpointIsReported() throws Exception {
        assertThat(DeviceAccessGuard.problems(Set.of("/api/app/devices/{deviceId}"), method("unguarded")))
                .singleElement()
                .asString()
                .contains("缺少 @DeviceAccess");
    }

    @Test
    void endpointsWithoutDeviceOrOutsideAppAreIgnored() throws Exception {
        assertThat(DeviceAccessGuard.problems(Set.of("/api/app/me", "/api/app/devices"), method("unguarded")))
                .isEmpty();
        // 后台接口有自己的 RBAC（BIZ-08），不走小程序的设备权限
        assertThat(DeviceAccessGuard.problems(Set.of("/api/admin/devices/{deviceId}"), method("unguarded")))
                .isEmpty();
    }

    @Test
    void annotationWithoutDeviceVariableIsReported() throws Exception {
        assertThat(DeviceAccessGuard.problems(Set.of("/api/app/devices/{id}"), method("annotatedWithoutDevice")))
                .singleElement()
                .asString()
                .contains("路径却没有 {deviceId}");
    }

    @Test
    void grantParameterWithoutAnnotationIsReported() throws Exception {
        assertThat(DeviceAccessGuard.problems(Set.of("/api/app/other"), method("wantsGrant", DeviceGrant.class)))
                .singleElement()
                .asString()
                .contains("DeviceGrant");
    }
}
