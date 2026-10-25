package com.rag.studyhelper.config;

import com.rag.studyhelper.context.UserContext;
import com.rag.studyhelper.exception.BusinessException;
import com.rag.studyhelper.service.TokenService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * 认证拦截器：除白名单外，所有 /api/** 请求都必须携带有效 token。
 * <p>
 * <b>为什么用拦截器而不是在每个 Controller 方法里手动校验？</b>
 * 手动校验靠"人记得写"，新加的接口忘写就是裸奔——
 * 安全策略必须默认生效（deny by default），白名单只放行明确公开的路径。
 * <p>
 * <b>token 从哪来？</b>
 * {@code Authorization: Bearer <token>} 请求头（标准做法），
 * 兼容 {@code X-Auth-Token} 头（SSE 的 EventSource 不支持自定义请求头时的备用通道）。
 * <p>
 * <b>afterCompletion 里必须清 UserContext。</b>
 * Tomcat 的线程是复用的，不清理的话，下一个请求能读到上一个用户的身份，
 * 这是比内存泄漏严重得多的"串号"事故。
 * afterCompletion 在请求<b>成功、异常、超时</b>三种情况下都会执行，
 * 是唯一安全的清理点。
 */
@Component
public class AuthInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(AuthInterceptor.class);

    public static final String TOKEN_ATTRIBUTE = "auth.token";

    @Autowired
    private TokenService tokenService;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // 放行预检请求（浏览器跨域时先发的 OPTIONS 不带自定义头）
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }

        String token = extractToken(request);
        Long userId = tokenService.resolve(token);
        if (userId == null) {
            // 统一 401，绝不区分"token 缺失 / token 过期 / token 无效"，
            // 避免给攻击者提供探测信息
            throw BusinessException.unauthorized("未登录或会话已过期");
        }

        UserContext.set(userId);
        request.setAttribute(TOKEN_ATTRIBUTE, token);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        UserContext.set(null);
        UserContext.clear();
    }

    private String extractToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            return header.substring(7).trim();
        }
        return request.getHeader("X-Auth-Token");
    }
}
