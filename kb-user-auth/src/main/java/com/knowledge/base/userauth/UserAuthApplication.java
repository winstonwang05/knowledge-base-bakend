package com.knowledge.base.userauth;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@MapperScan("com.knowledge.base.userauth.mapper")
@ComponentScan(basePackages = {"com.knowledge.base.userauth", "com.knowledge.base.common"})
public class UserAuthApplication {

    public static void main(String[] args) {
        SpringApplication.run(UserAuthApplication.class, args);
        System.out.println("========================================");
        System.out.println("用户权限服务启动成功！");
        System.out.println("Swagger文档地址: http://localhost:8081/api/auth/doc.html");
        System.out.println("========================================");
    }
}