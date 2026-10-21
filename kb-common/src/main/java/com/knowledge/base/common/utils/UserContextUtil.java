package com.knowledge.base.common.utils;

import jakarta.servlet.http.HttpServletRequest;

/**
 * @description: 用户上下文工具类，获取用户信息功能
 * @author: Winston
 * @date: 2026/10/21 9:35
 * @version: 1.0
 */
public class UserContextUtil {

    private static final ThreadLocal<Long> USER_ID_HOLDER = new ThreadLocal<>();
    private static final ThreadLocal<String> USERNAME_HOLDER = new ThreadLocal<>();
    private static final ThreadLocal<String> TOKEN_HOLDER = new ThreadLocal<>();
    private static final ThreadLocal<String> AVATAR_HOLDER = new ThreadLocal<>();

    /**
     * 设置用户id到上下文中
     * @param userId 用户id
     */
    public static void setUserId(Long userId) {
        USER_ID_HOLDER.set(userId);
    }

    /**
     * 获取用户id信息
     * @return 返回用户id
     */
    public static Long getUserId() {
        return USER_ID_HOLDER.get();
    }
    /**
     * 设置当前用户名
     *
     * @param username 用户名
     */
    public static void setUsername(String username) {
        USERNAME_HOLDER.set(username);
    }

    /**
     * 获取当前用户名
     *
     * @return 用户名
     */
    public static String getUsername() {
        return USERNAME_HOLDER.get();
    }

    /**
     * 设置Token
     *
     * @param token Token
     */
    public static void setToken(String token) {
        TOKEN_HOLDER.set(token);
    }

    /**
     * 获取Token
     *
     * @return Token
     */
    public static String getToken() {
        return TOKEN_HOLDER.get();
    }

    /**
     * 设置当前用户头像
     *
     * @param avatar 头像URL
     */
    public static void setAvatar(String avatar) {
        AVATAR_HOLDER.set(avatar);
    }

    /**
     * 获取当前用户头像
     *
     * @return 头像URL
     */
    public static String getAvatar() {
        return AVATAR_HOLDER.get();
    }

    /**
     * 从请求中获取用户id
     * @param request 请求
     * @return 返回用户id
     */
    public static Long getUserIdFromRequest(HttpServletRequest request) {
        // 1.提取token
        String token = extractTokenFromRequest(request);
        if (token == null) {
            return null;
        }
        // 2.调用JwtToken工具类解析token
        // 3.需要从容器中拉取bean
        try {
            // 使用JwtTokenUtil解析token
            JwtTokenUtil jwtTokenUtil = SpringContextUtil.getBean(JwtTokenUtil.class);
            return jwtTokenUtil.getUserIdFromToken(token);
        } catch (Exception e) {
            return null;
        }
    }


    /**
     * 从请求中提取Token
     *
     * @param request HttpServletRequest
     * @return Token
     */
    public static String extractTokenFromRequest(HttpServletRequest request) {
        String bearerToken = request.getHeader("Authorization");
        if (bearerToken != null && bearerToken.startsWith("Bearer ")) {
            return bearerToken.substring(7);
        }
        return null;
    }

    /**
     * 清除当前用户上下文
     */
    public static void clear() {
        USER_ID_HOLDER.remove();
        USERNAME_HOLDER.remove();
        TOKEN_HOLDER.remove();
        AVATAR_HOLDER.remove();
    }

    /**
     * 检查用户是否已登录
     *
     * @return 是否已登录
     */
    public static boolean isLoggedIn() {
        return getUserId() != null;
    }

    /**
     * 从请求头 X-User-Id 中获取用户ID（由网关注入）
     *
     * <p>优先从 ThreadLocal 获取已登录用户，降级后从请求头解析</p>
     *
     * @param request HttpServletRequest
     * @return 用户ID，未获取到时返回 1L（默认用户）
     */
    public static Long getUserIdFromHeader(HttpServletRequest request) {
        Long threadLocalUserId = getUserId();
        if (threadLocalUserId != null) {
            return threadLocalUserId;
        }
        String userId = request.getHeader("X-User-Id");
        if (userId != null) {
            return Long.parseLong(userId);
        }
        return 1L;
    }

}
