package com.ove.global.docs;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Swagger 문서에서 "로그인 없이도 호출할 수 있고, Bearer 토큰을 보내면 로그인 상태로 처리된다"는 표시를 한다
 * (OpenAPI의 security: [{}, {bearerAuth: []}]). 표준 @SecurityRequirement로는 빈 요구사항을 표현할 수 없어서 따로 둔다.
 * 문서용 표시일 뿐이라 실제 접근 제어(SecurityConfig)에는 영향이 없다.
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface ApiOptionalAuth {
}
