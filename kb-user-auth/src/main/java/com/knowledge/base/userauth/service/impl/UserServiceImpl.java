package com.knowledge.base.userauth.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.knowledge.base.userauth.entity.User;
import com.knowledge.base.userauth.mapper.UserMapper;
import com.knowledge.base.userauth.service.UserService;
import org.springframework.stereotype.Service;

/**
 * @description: 用户服务业务层
 * @author: Winston
 * @date: 2026/10/21 14:20
 * @version: 1.0
 */
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements UserService {

    /**
     * 根据用户名获取用户信息
     * @param username 用户名
     * @return 返回用户信息
     */
    @Override
    public User getByUsername(String username) {
        LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(User::getUsername, username );
        return this.getOne(wrapper);
    }
}
