package com.ove.global.security;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * application.yaml의 cors.* 설정. 쿠키(credentials)를 보내는 프론트 origin 목록.
 * 쿠키를 쓰는 요청이라 "*"는 쓸 수 없고 origin을 정확히 나열해야 한다.
 * 기본값에 로컬 Swagger UI(http://localhost:8080)를 넣어 둔 이유는 application.yaml의 cors 주석에 있다.
 */
@ConfigurationProperties(prefix = "cors")
public record CorsProperties(
        @DefaultValue({"http://localhost:5173", "http://localhost:8080"}) List<String> allowedOrigins
) {
}
