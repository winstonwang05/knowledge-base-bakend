package com.knowledge.base.userauth.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.knowledge.base.userauth.entity.User;
import org.apache.ibatis.annotations.Mapper;

/**
 * @description: 用户Mapper接口
 * @author: Winston
 * @date: 2026/10/21 14:13
 * @version: 1.0
 */
@Mapper
public interface UserMapper extends BaseMapper<User> {
}
