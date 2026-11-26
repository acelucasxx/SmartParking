package com.smartparking.user.web;

import com.smartparking.common.web.ApiResponse;
import com.smartparking.common.web.AppPrincipal;
import com.smartparking.user.AuthService;
import com.smartparking.user.AuthService.Profile;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 接口契约 {@code docs/api/openapi-app.yaml} 的 auth 分组 */
@RestController
@RequestMapping("/api/app")
public class AuthController {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private final AuthService auth;

    public AuthController(AuthService auth) {
        this.auth = auth;
    }

    public record CodeRequest(@NotBlank @Size(max = 128) String code) {}

    public record NicknameRequest(@NotBlank @Size(max = 32) String nickname) {}

    public record UserProfile(long id, String nickname, boolean phoneBound, String phoneMasked) {
        static UserProfile of(Profile p) {
            return new UserProfile(p.id(), p.nickname(), p.phoneBound(), p.phoneMasked());
        }
    }

    public record LoginResult(String token, OffsetDateTime expiresAt, UserProfile user) {}

    @PostMapping("/auth/login")
    public ApiResponse<LoginResult> login(@Valid @RequestBody CodeRequest request) {
        AuthService.LoginResult result = auth.login(request.code());
        OffsetDateTime expiresAt =
                result.session().expiresAt().atZone(ZONE).toOffsetDateTime().truncatedTo(ChronoUnit.SECONDS);
        return ApiResponse.ok(new LoginResult(result.session().token(), expiresAt, UserProfile.of(result.user())));
    }

    @PostMapping("/auth/phone")
    public ApiResponse<UserProfile> bindPhone(AppPrincipal principal, @Valid @RequestBody CodeRequest request) {
        return ApiResponse.ok(UserProfile.of(auth.bindPhone(principal.userId(), request.code())));
    }

    @PostMapping("/auth/logout")
    public ApiResponse<Void> logout(HttpServletRequest request) {
        auth.logout((String) request.getAttribute(AppAuthInterceptor.TOKEN_ATTRIBUTE));
        return ApiResponse.ok();
    }

    @GetMapping("/me")
    public ApiResponse<UserProfile> me(AppPrincipal principal) {
        return ApiResponse.ok(UserProfile.of(auth.me(principal.userId())));
    }

    @PatchMapping("/me")
    public ApiResponse<UserProfile> updateMe(AppPrincipal principal, @Valid @RequestBody NicknameRequest request) {
        return ApiResponse.ok(UserProfile.of(auth.updateNickname(principal.userId(), request.nickname())));
    }
}
