package com.knowledge.base.userauth.controller;

import com.knowledge.base.userauth.service.UserService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * @description: 用户控制层
 * @author: Winston
 * @date: 2026/10/21 14:33
 * @version: 1.0
 */
@Slf4j
@RestController
@RequestMapping("/users")
@Tag(name = "用户管理", description = "用户信息管理接口")
@RequiredArgsConstructor
public class UserController {
    @Resource
    private final UserService userService;
}
