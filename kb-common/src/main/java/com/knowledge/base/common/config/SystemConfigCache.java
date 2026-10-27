package com.knowledge.base.common.config;

import jakarta.annotation.Resource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * @description: 系统配置Redis缓存，各服务直接从缓存读取更快
 * <p>所有系统配置（kb_system_config）统一存储在 Redis Hash 中，
 * 各微服务从缓存读取，避免跨库直连数据库。</p>
 *
 * <ul>
 *   <li>Redis Key: {@code kb:system:config}</li>
 *   <li>Hash Field: config_key</li>
 *   <li>Hash Value: config_value</li>
 * </ul>
 * @author: Winston
 * @date: 2026/10/25 13:49
 * @version: 1.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SystemConfigCache {

    /** Redis Hash 键名 */
    public static final String REDIS_HASH_KEY = "kb:system:config";

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 获取单个配置值
     * @param configKey 配置的键
     * @return 配置键所对应的值
     */
    public String getConfig(String configKey) {
        Object value = stringRedisTemplate.opsForHash().get(REDIS_HASH_KEY, configKey);
        return value != null ? value.toString() : null;
    }

    /**
     * 获取单个配置值（带默认值）
     *
     * @param configKey    配置键
     * @param defaultValue 默认值
     * @return 配置值，不存在时返回默认值
     */
    public String getConfig(String configKey, String defaultValue) {
        String value = getConfig(configKey);
        return value != null ? value : defaultValue;
    }

    /**
     * 获取所有配置（键值对）
     *
     * @return 所有配置的 Map
     */
    public Map<String, String> getAllConfigs() {
        Map<Object, Object> entries = stringRedisTemplate.opsForHash().entries(REDIS_HASH_KEY);
        Map<String, String> result = new HashMap<>();
        entries.forEach((k, v) -> result.put(k.toString(), v.toString()));
        return result;
    }

    /**
     * 写入单个配置到缓存
     * @param configKey 缓存key
     * @param value 值
     */
    public void setConfig(String configKey, String value) {
        stringRedisTemplate.opsForHash().put(REDIS_HASH_KEY, configKey, value);
        log.debug("配置已写入缓存：{} = {}", configKey, value);
    }
    /**
     * 从缓存中删除单个配置
     *
     * @param configKey 配置键
     */
    public void deleteConfig(String configKey) {
        stringRedisTemplate.opsForHash().delete(REDIS_HASH_KEY, configKey);
        log.debug("配置已从缓存删除：{}", configKey);
    }

    /**
     * 批量加载到Redis缓存中
     * @param configs
     */
    public void loadAll(Map<String, String> configs) {
        // 1.判断空
        if (configs == null || configs.isEmpty()) {
            log.warn("批量加载配置为空，跳过");
            return;
        }
        // 2.先清除原先内容再批量加载
        stringRedisTemplate.delete(REDIS_HASH_KEY);
        stringRedisTemplate.opsForHash().putAll(REDIS_HASH_KEY, configs);
        log.info("已批量加载 {} 条配置到 Redis 缓存", configs.size());
    }

    /**
     * 刷新单条配置（仅在缓存中存在时更新，用于防并发写覆盖）
     *
     * @param configKey   配置键
     * @param configValue 配置值
     */
    public void refreshConfig(String configKey, String configValue) {
        Boolean exists = stringRedisTemplate.opsForHash().hasKey(REDIS_HASH_KEY, configKey);
        if (Boolean.TRUE.equals(exists)) {
            setConfig(configKey, configValue);
        }
    }

}
