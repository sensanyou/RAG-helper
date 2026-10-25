package com.rag.studyhelper.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.rag.studyhelper.exception.BusinessException;
import com.rag.studyhelper.model.User;
import com.rag.studyhelper.mapper.UserMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.regex.Pattern;

/**
 * 注册 / 登录 / 退出 的业务编排。
 * <p>
 * <b>登录失败的报错故意模糊</b>："用户名或密码错误"而不是分开说
 * "用户不存在" / "密码不对"。分开说等于告诉攻击者"这个用户名是存在的"，
 * 他只需要再爆密码。这是账号系统的通用做法。
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    /** 用户名规则：3~32 位，字母/数字/下划线 */
    private static final Pattern USERNAME_PATTERN = Pattern.compile("^[A-Za-z0-9_]{3,32}$");

    private final UserMapper userMapper;
    private final PasswordService passwordService;
    private final TokenService tokenService;

    public AuthService(UserMapper userMapper, PasswordService passwordService, TokenService tokenService) {
        this.userMapper = userMapper;
        this.passwordService = passwordService;
        this.tokenService = tokenService;
    }

    public String register(String username, String password, String displayName) {
        validateUsername(username);
        validatePassword(password);

        Long exists = userMapper.selectCount(
                Wrappers.<User>lambdaQuery().eq(User::getUsername, username));
        if (exists != null && exists > 0) {
            throw BusinessException.conflict("用户名已被占用");
        }

        User user = new User();
        user.setUsername(username);
        // 数据库里永远没有明文密码——哈希在写库前完成
        user.setPasswordHash(passwordService.hash(password));
        user.setDisplayName(StringUtils.hasText(displayName) ? displayName : username);
        user.setStatus(1);
        userMapper.insert(user);
        log.info("新用户注册: userId={}, username={}", user.getId(), username);
        return tokenService.issue(user.getId());
    }

    public String login(String username, String password) {
        if (!StringUtils.hasText(username) || !StringUtils.hasText(password)) {
            throw BusinessException.badRequest("用户名和密码不能为空");
        }
        User user = userMapper.selectOne(
                Wrappers.<User>lambdaQuery().eq(User::getUsername, username));
        if (user == null || !passwordService.verify(password, user.getPasswordHash())) {
            throw BusinessException.unauthorized("用户名或密码错误");
        }
        if (user.getStatus() == null || user.getStatus() != 1) {
            throw BusinessException.forbidden("账号已被禁用");
        }

        // 平滑升级：老用户用旧迭代次数哈希的密码，验证通过后按当前强度重哈希
        if (passwordService.needsRehash(user.getPasswordHash())) {
            user.setPasswordHash(passwordService.hash(password));
            userMapper.updateById(user);
            log.info("密码哈希已升级: userId={}", user.getId());
        }

        return tokenService.issue(user.getId());
    }

    public void logout(String token) {
        tokenService.revoke(token);
    }

    private void validateUsername(String username) {
        if (username == null || !USERNAME_PATTERN.matcher(username).matches()) {
            throw BusinessException.badRequest("用户名需为 3~32 位字母、数字或下划线");
        }
    }

    private void validatePassword(String password) {
        if (password == null || password.length() < 6 || password.length() > 64) {
            throw BusinessException.badRequest("密码长度需为 6~64 位");
        }
    }
}
