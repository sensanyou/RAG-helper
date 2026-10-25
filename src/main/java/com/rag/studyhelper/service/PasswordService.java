package com.rag.studyhelper.service;

import com.rag.studyhelper.exception.BusinessException;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 密码哈希服务（PBKDF2）。
 * <p>
 * <b>为什么绝不能存明文 / MD5 / SHA-256？</b>
 * 数据库一旦泄露（拖库），攻击者手里就有全部用户的"密码"：
 * <ul>
 *   <li>明文：直接登录；</li>
 *   <li>MD5 / SHA-256：密码哈希是确定性的，攻击者拿彩虹表（海量"常见密码→哈希"对照表）
 *       反查，弱密码秒破。</li>
 * </ul>
 * PBKDF2 是<b>慢哈希 + 随机盐</b>：
 * <ul>
 *   <li>盐（salt）每个用户独立随机生成 → 同样的密码哈希结果也不同，
 *       彩虹表完全失效，攻击者只能对每个用户单独爆破；</li>
 *   <li>迭代次数（这里 12 万次）→ 单次验证要几百毫秒，
 *       爆破一个弱密码的计算成本被放大十万倍。</li>
 * </ul>
 * <p>
 * <b>为什么用 PBKDF2 而不是 BCrypt？</b>
 * BCrypt 更"行业标准"，但它来自 Spring Security 的 spring-security-crypto 包。
 * 本项目 JDK8 + 离线环境拉不了新依赖，而 PBKDF2 是 JDK 内置的
 * （{@link SecretKeyFactory}），零依赖、够安全（NIST 推荐）。
 * 代价是参数（盐、迭代次数）要自己管理——这就是为什么存储格式里带它们。
 * <p>
 * <b>存储格式</b>：{@code pbkdf2:iterations:saltHex:hashHex}
 * 自包含、可升级：未来提高迭代次数后，老用户登录时发现次数低于当前配置，
 * 用旧参数验证成功后立即用新参数重新哈希落库（平滑升级）。
 */
@Service
public class PasswordService {

    /** PBKDF2 迭代次数。越高越慢越安全，12 万次在普通服务器约 100~200ms */
    private static final int ITERATIONS = 120_000;
    /** 盐长度（字节）。128 bit 足够防止盐碰撞 */
    private static final int SALT_BYTES = 16;
    /** 哈希长度（字节）。256 bit */
    private static final int KEY_BITS = 256;
    /** 底层伪随机函数，JDK 全平台支持 */
    private static final String ALGORITHM = "PBKDF2WithHmacSHA256";

    private static final Pattern STORED_FORMAT =
            Pattern.compile("^pbkdf2:(\\d+):([0-9a-f]+):([0-9a-f]+)$");

    private final SecureRandom random = new SecureRandom();

    /**
     * 哈希明文密码，返回可入库的自包含格式串。
     */
    public String hash(String rawPassword) {
        byte[] salt = new byte[SALT_BYTES];
        random.nextBytes(salt);
        byte[] hash = pbkdf2(rawPassword, salt, ITERATIONS);
        return String.format("pbkdf2:%d:%s:%s",
                ITERATIONS, toHex(salt), toHex(hash));
    }

    /**
     * 校验明文密码是否与库里的哈希匹配。
     * <p>
     * <b>必须用常量时间比较</b>（逐字节比完再下结论），不能 用 String.equals：
     * equals 在第一个不同字节就返回，攻击者可以通过测量响应时间
     * 逐字节猜出正确哈希（时序攻击）。{@code MessageDigest.isEqual} 是常量时间的。
     */
    public boolean verify(String rawPassword, String stored) {
        Matcher m = STORED_FORMAT.matcher(stored == null ? "" : stored);
        if (!m.matches()) {
            // 格式不合法（比如历史脏数据）按"不匹配"处理，绝不抛异常泄露格式细节
            return false;
        }
        int iterations = Integer.parseInt(m.group(1));
        byte[] salt = fromHex(m.group(2));
        byte[] expected = fromHex(m.group(3));
        byte[] actual = pbkdf2(rawPassword, salt, iterations);
        return MessageDigest.isEqual(expected, actual);
    }

    /**
     * 当前配置的迭代次数是否比存储串里的新。
     * 登录成功后如果返回 true，调用方应重新哈希并落库（平滑升级强度）。
     */
    public boolean needsRehash(String stored) {
        Matcher m = STORED_FORMAT.matcher(stored == null ? "" : stored);
        return !m.matches() || Integer.parseInt(m.group(1)) < ITERATIONS;
    }

    private byte[] pbkdf2(String rawPassword, byte[] salt, int iterations) {
        try {
            PBEKeySpec spec = new PBEKeySpec(rawPassword.toCharArray(), salt, iterations, KEY_BITS);
            return SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).getEncoded();
        } catch (Exception e) {
            throw BusinessException.internal("密码处理失败");
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private static byte[] fromHex(String hex) {
        byte[] bytes = new byte[hex.length() / 2];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return bytes;
    }
}
