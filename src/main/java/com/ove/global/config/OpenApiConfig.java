package com.ove.global.config;

import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.method.HandlerMethod;

import com.ove.global.docs.ApiErrorCodes;
import com.ove.global.docs.ApiOptionalAuth;
import com.ove.global.exception.ErrorCode;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.examples.Example;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.tags.Tag;
import org.springdoc.core.customizers.OperationCustomizer;

/**
 * Swagger UI(OpenAPI 문서) 공통 설정: 소개글, 인증 방식(Bearer 토큰 / refresh 쿠키), 태그, 공통 에러 응답 스키마,
 * 그리고 컨트롤러 문서 인터페이스의 @ApiErrorCodes·@ApiOptionalAuth를 문서에 반영하는 커스터마이저.
 */
@Configuration
public class OpenApiConfig {

    public static final String BEARER_SCHEME = "bearerAuth";
    public static final String REFRESH_COOKIE_SCHEME = "refreshTokenCookie";
    private static final String ERROR_SCHEMA = "ApiErrorResponse";
    private static final String JSON = "application/json";

    @Bean
    public OpenAPI openAPI() {
        return new OpenAPI()
                .info(new Info().title("OvE API").version("v1").description("""
                        AI 의사결정 기반 실시간 투자 보조 서비스(OvE)의 백엔드 API입니다. 실제 거래는 하지 않고 모의투자만 제공합니다.

                        **공통 응답 형식** — 모든 응답은 `{statusCode, timestamp, path, message, data, error}` 형태입니다.
                        성공이면 `error`가 null, 실패면 `data`가 null이고 `error`에 `UPPER_SNAKE_CASE` 에러 코드가 들어갑니다.
                        시각은 모두 ISO-8601 UTC입니다.

                        **인증** — 로그인 후 받은 `accessToken`을 `Authorization: Bearer {accessToken}` 헤더로 보냅니다(만료 30분).
                        Swagger UI 오른쪽 위 **Authorize** 버튼에 토큰을 넣으면 요청마다 붙습니다.

                        **refresh 쿠키** — `refreshToken`은 응답 본문이 아니라 `HttpOnly` 쿠키로만 내려갑니다
                        (`Secure`, `SameSite=Lax`, `Path=/api/auth`, 로그인 시 `rememberMe=true`면 14일 유지).
                        브라우저가 `Set-Cookie`를 자동으로 저장하고 `/api/auth/**` 요청에만 자동으로 실어 보내므로,
                        Swagger UI에서 **로그인 → 재발급·로그아웃** 순서로 호출하면 쿠키가 알아서 전달됩니다.
                        단 쿠키는 JS로 읽거나 직접 넣을 수 없고, Swagger UI와 API가 같은 출처(origin)일 때만 동작합니다.
                        `Secure` 쿠키라서 `http://localhost` 외의 http 주소에서는 저장되지 않을 수 있습니다.
                        `refresh`·`logout`은 `Origin` 헤더가 허용 목록에 없으면 `403 FORBIDDEN`입니다.
                        """))
                .tags(List.of(
                        new Tag().name("Auth").description("회원가입, 로그인, 토큰 재발급, 로그아웃"),
                        new Tag().name("Users").description("내 정보"),
                        new Tag().name("Markets").description("종목 목록·검색·캔들 조회 (현재는 업비트 코인 3종목)")))
                .components(new Components()
                        .addSecuritySchemes(BEARER_SCHEME, new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("로그인으로 받은 accessToken (만료 30분)"))
                        .addSecuritySchemes(REFRESH_COOKIE_SCHEME, new SecurityScheme()
                                .type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.COOKIE)
                                .name("refreshToken")
                                .description("로그인 응답의 Set-Cookie로 내려오는 HttpOnly refresh 쿠키. 브라우저가 자동으로 보내며 "
                                        + "Swagger UI에서 값을 직접 넣을 수는 없습니다."))
                        .addSchemas(ERROR_SCHEMA, errorSchema()))
                // 기본은 Bearer 필요. 로그인 없이 호출하는 엔드포인트는 컨트롤러 문서 인터페이스에서 @SecurityRequirements로 풀어 준다.
                .addSecurityItem(new SecurityRequirement().addList(BEARER_SCHEME));
    }

    /**
     * @ApiErrorCodes를 읽어 상태 코드별 에러 응답(설명 + 코드별 예시)을 붙이고,
     * 컨트롤러가 produces를 지정하지 않아 생기는 응답 미디어 타입 '*&#47;*'을 application/json으로 바꾼다.
     */
    @Bean
    public OperationCustomizer apiErrorCodeCustomizer() {
        return (operation, handlerMethod) -> {
            normalizeMediaTypes(operation);
            if (handlerMethod.hasMethodAnnotation(ApiOptionalAuth.class)) {
                // 보안 요구사항 목록의 빈 항목({})은 "인증 없이도 가능"이라는 뜻이다
                operation.setSecurity(List.of(new SecurityRequirement(), new SecurityRequirement().addList(BEARER_SCHEME)));
            }
            ApiErrorCodes errorCodes = handlerMethod.getMethodAnnotation(ApiErrorCodes.class);
            if (errorCodes != null) {
                addErrorResponses(operation, errorCodes.value(), resolvePath(handlerMethod));
            }
            return operation;
        };
    }

    private Schema<?> errorSchema() {
        Schema<?> error = new StringSchema()
                .description("에러 코드(UPPER_SNAKE_CASE)")
                ._enum(Arrays.stream(ErrorCode.values()).map(Enum::name).toList());
        return new ObjectSchema()
                .description("실패 응답. 성공 응답과 같은 envelope이고 data는 항상 null입니다.")
                .addProperty("statusCode", new IntegerSchema().format("int32").description("HTTP 상태 코드").example(400))
                .addProperty("timestamp", new StringSchema().format("date-time").description("응답 시각(UTC)"))
                .addProperty("path", new StringSchema().description("요청 경로"))
                .addProperty("message", new StringSchema().description("사람이 읽을 수 있는 설명(한국어)"))
                .addProperty("data", new Schema<>().types(new java.util.LinkedHashSet<>(List.of("null")))
                        .description("실패 시 항상 null"))
                .addProperty("error", error);
    }

    private void normalizeMediaTypes(Operation operation) {
        if (operation.getResponses() == null) {
            return;
        }
        for (ApiResponse response : operation.getResponses().values()) {
            Content content = response.getContent();
            if (content != null && content.containsKey("*/*") && !content.containsKey(JSON)) {
                MediaType any = content.remove("*/*");
                content.addMediaType(JSON, any);
            }
        }
    }

    private void addErrorResponses(Operation operation, ErrorCode[] codes, String path) {
        if (operation.getResponses() == null) {
            operation.setResponses(new ApiResponses());
        }
        // 상태 코드별로 묶는다(같은 401이라도 TOKEN_EXPIRED / UNAUTHORIZED처럼 여러 코드가 있을 수 있다)
        Map<Integer, List<ErrorCode>> byStatus = new TreeMap<>();
        for (ErrorCode code : codes) {
            byStatus.computeIfAbsent(code.getStatus().value(), key -> new java.util.ArrayList<>()).add(code);
        }
        byStatus.forEach((status, group) -> {
            StringBuilder description = new StringBuilder();
            Content content = new Content();
            MediaType mediaType = new MediaType().schema(new Schema<>().$ref("#/components/schemas/" + ERROR_SCHEMA));
            for (ErrorCode code : group) {
                description.append("- `").append(code.name()).append("` — ").append(code.getMessage()).append('\n');
                mediaType.addExamples(code.name(), new Example().summary(code.name()).value(errorExample(code, path)));
            }
            content.addMediaType(JSON, mediaType);
            operation.getResponses().addApiResponse(String.valueOf(status),
                    new ApiResponse().description(description.toString().trim()).content(content));
        });
    }

    private Map<String, Object> errorExample(ErrorCode code, String path) {
        Map<String, Object> example = new LinkedHashMap<>();
        example.put("statusCode", code.getStatus().value());
        example.put("timestamp", Instant.parse("2026-10-24T03:21:45.123Z").toString());
        example.put("path", path);
        example.put("message", code.getMessage());
        example.put("data", null);
        example.put("error", code.name());
        return example;
    }

    // 클래스와 메서드의 @RequestMapping(@PostMapping 등 포함) 경로를 이어서 예시의 path로 쓴다
    private String resolvePath(HandlerMethod handlerMethod) {
        String base = firstPath(AnnotatedElementUtils.findMergedAnnotation(handlerMethod.getBeanType(),
                RequestMapping.class));
        String sub = firstPath(AnnotatedElementUtils.findMergedAnnotation(handlerMethod.getMethod(),
                RequestMapping.class));
        return base + sub;
    }

    private String firstPath(RequestMapping mapping) {
        if (mapping == null || mapping.path().length == 0) {
            return "";
        }
        return mapping.path()[0];
    }
}
