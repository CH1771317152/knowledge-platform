package com.platform.content.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "platform.content.id")
public record ContentIdProperties(
        @Min(0) @Max(31) long workerId,
        @Min(0) @Max(31) long datacenterId
) {}
