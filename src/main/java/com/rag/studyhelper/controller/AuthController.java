package com.rag.studyhelper.controller;

import com.rag.studyhelper.config.AuthInterceptor;
import com.rag.studyhelper.context.UserContext;
import com.rag.studyhelper.model.User;
import com.rag.studyhelper.mapper.UserMapper;
import com.rag.studyhelper.service.AuthService;
import com.rag.studyhelper.utils.Results;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;
import java.util.HashMap;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * 认证接口：注册 / 登录 / 退出 / 当前用户信息。
 * <p>
 * register 和 login 在拦截器白名单里（必然未登录才能访问），
 * logout 和 me 需要有效 token。
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    @Autowired
    private AuthService authService;

    @Autowired
    private UserMapper userMapper;

    public static class RegisterRequest {
        @NotBlank(message = "用户名不能为空")
        @Size(max = 32, message = "用户名过长")
        public String username;
        @NotBlank(message = "密码不能为空")
        @Size(max = 64, message = "密码过长")
        public String password;
        public String displayName;
    }

    public static class LoginRequest {
        @NotBlank(message = "用户名不能为空")
        public String username;
        @NotBlank(message = "密码不能为空")
        public String password;
    }

    @PostMapping("/register")
    public Results<Map<String, Object>> register(@Valid @RequestBody RegisterRequest req) {
        String token = authService.register(req.username, req.password, req.displayName);
        return Results.success("注册成功", tokenPayload(token));
    }

    @PostMapping("/login")
    public Results<Map<String, Object>> login(@Valid @RequestBody LoginRequest req) {
        String token = authService.login(req.username, req.password);
        return Results.success("登录成功", tokenPayload(token));
    }

    @PostMapping("/logout")
    public Results<Void> logout(@RequestHeader(value = "Authorization", required = false) String authorization) {
        String token = authorization != null && authorization.startsWith("Bearer ")
                ? authorization.substring(7).trim() : null;
        authService.logout(token);
        return Results.success("已退出登录", null);
    }

    @GetMapping("/me")
    public Results<Map<String, Object>> me() {
        long userId = UserContext.requireUserId();
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new NoSuchElementException("用户不存在");
        }
        Map<String, Object> info = new HashMap<>();
        info.put("userId", user.getId());
        info.put("username", user.getUsername());
        info.put("displayName", user.getDisplayName());
        return Results.success(info);
    }

    private Map<String, Object> tokenPayload(String token) {
        Map<String, Object> data = new HashMap<>();
        data.put("token", token);
        data.put("tokenType", "Bearer");
        // 前端把它放进 Authorization 头；SSE EventSource 场景用 X-Auth-Token
        data.put("tokenHeader", AuthInterceptor.TOKEN_ATTRIBUTE);
        return data;
    }
}
