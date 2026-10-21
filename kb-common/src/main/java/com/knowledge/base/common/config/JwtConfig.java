package com.knowledge.base.common.config;

import io.jsonwebtoken.security.Keys;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;

/**
 * @description: JWT配置类，提供JWT Token的配置属性，包括密钥、过期时间和发行者信息
 * @author: Winston
 * @date: 2026/10/21 9:41
 * @version: 1.0
 */
@Data
@Component
@ConfigurationProperties(prefix = "jwt")
public class JwtConfig {

    /**
     * JWT密钥
     */
    private String secret = "knowledge-base-secret-key-for-jwt-token-generation-must-be-long-enough";
    /**
     * 访问令牌过期时间（秒）
     */
    /**
     * 访问令牌过期时间（秒）
     */
    private Long expiration = 7200L;

    /**
     * 刷新令牌过期时间（秒）
     */
    private Long refreshExpiration = 604800L;

    /**
     * 发行者
     */
    private String issuer = "knowledge-base";

    /**
     * 获取密钥对象
     *
     * @return 密钥对象
     */
    public SecretKey secretKey() {
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }
}
