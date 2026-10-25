package com.rag.studyhelper.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web MVC 配置：注册认证拦截器 + 白名单。
 * <p>
 * <b>白名单原则：deny by default。</b>
 * 默认拒绝一切 /api/** 请求，只显式放行认证入口本身。
 * 以后每加一个"必须公开"的接口，都要来这里加白名单——
 * 这个"麻烦"是刻意的，加白名单前必须想清楚"它凭什么公开"。
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    @Autowired
    private AuthInterceptor authInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authInterceptor)
                .addPathPatterns("/api/**")
                .excludePathPatterns(
                        "/api/auth/register",   // 注册：必然未登录
                        "/api/auth/login"       // 登录：必然未登录
                );
    }
}
