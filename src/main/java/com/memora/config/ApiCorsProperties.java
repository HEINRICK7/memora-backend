package com.memora.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "memora.api.cors")
public record ApiCorsProperties(String allowedOrigins) {
}
