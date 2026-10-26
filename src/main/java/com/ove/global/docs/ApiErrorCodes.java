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
 *
 * <p>[Docs 인터페이스 공통 규칙] 컨트롤러 메서드 파라미터의 검증 어노테이션(@Min, @Max, @NotBlank 등)은
 * 구현 클래스(컨트롤러)에 붙이지 말고 Docs 인터페이스 쪽 파라미터에만 붙인다.
 * 인터페이스를 구현한 메서드가 파라미터 제약을 새로 정의하면 Hibernate Validator가 HV000151
 * ("오버라이드한 메서드는 파라미터 제약을 추가·변경할 수 없다")로 거부해서, 정상 값을 보내도 그 API가 전부 500이 된다.
 * 인터페이스 쪽에만 붙인 제약은 구현 메서드에도 그대로 적용된다. 제약 위반은 HandlerMethodValidationException으로 올라오므로
 * 400 INVALID_REQUEST로 바꾸는 @ExceptionHandler가 GlobalExceptionHandler에 있어야 한다(없으면 500).
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface ApiErrorCodes {

    ErrorCode[] value();
}
