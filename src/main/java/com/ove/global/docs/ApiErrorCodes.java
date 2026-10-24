package com.ove.global.docs;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.ove.global.exception.ErrorCode;

/**
 * Swagger 문서에 이 엔드포인트가 돌려줄 수 있는 에러 코드를 보여 준다.
 * 상태 코드별로 묶어 설명하고, 코드마다 공통 envelope 형태의 응답 예시를 만든다(OpenApiConfig의 OperationCustomizer).
 * 문서용 표시일 뿐이라 실제 동작에는 영향이 없다.
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface ApiErrorCodes {

    ErrorCode[] value();
}
