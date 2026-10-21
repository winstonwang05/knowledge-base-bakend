package com.knowledge.base.userauth.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.knowledge.base.userauth.entity.User;

/**
 * @description: 用户服务接口
 * @author: Winston
 * @date: 2026/10/21 14:18
 * @version: 1.0
 */
public interface UserService extends IService<User> {
    /**
     * 根据用户名查询用户
     * @param username 用户名
     * @return 返回用户信息
     */
    User getByUsername(String username);
}
