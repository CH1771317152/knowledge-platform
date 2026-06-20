package com.platform.config;

import org.apache.ibatis.annotations.Mapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration
@Profile("!test")
@MapperScan(basePackages = "com.platform", annotationClass = Mapper.class)
public class MyBatisConfig {
}
