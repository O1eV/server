package com.ove.global.config;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;

import com.ove.support.IntegrationTestSupport;

class OpenApiDocsTest extends IntegrationTestSupport {

    @Test
    void 문서는_인증_없이_조회되고_주요_경로와_스키마를_담는다() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/auth/login'].post").exists())
                .andExpect(jsonPath("$.paths['/api/auth/register'].post").exists())
                .andExpect(jsonPath("$.paths['/api/auth/refresh'].post").exists())
                .andExpect(jsonPath("$.paths['/api/auth/logout'].post").exists())
                .andExpect(jsonPath("$.paths['/api/users/me'].get").exists())
                .andExpect(jsonPath("$.paths['/api/keys'].post.responses['409'].content['application/json'].examples.API_KEY_LIMIT_EXCEEDED").exists())
                .andExpect(jsonPath("$.paths['/api/keys'].get").exists())
                .andExpect(jsonPath("$.paths['/api/keys/{keyId}'].delete.responses['404'].content['application/json'].examples.API_KEY_NOT_FOUND").exists())
                .andExpect(jsonPath("$.paths['/api/markets'].get").exists())
                .andExpect(jsonPath("$.paths['/api/markets/{symbol}/candles'].get").exists())
                .andExpect(jsonPath("$.components.schemas.ApiErrorResponse").exists())
                .andExpect(jsonPath("$.components.schemas.LoginRequest").exists());
    }

    @Test
    void 종목_검색의_검색어는_필수로_표시된다() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/markets/search'].get.parameters[?(@.name=='q')].required").value(true))
                .andExpect(jsonPath("$.paths['/api/markets/search'].get.parameters[?(@.name=='limit')].required").value(false));
    }

    @Test
    void 인증_요구사항이_엔드포인트별로_다르게_표시된다() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                // 로그인·회원가입은 인증 불필요
                .andExpect(jsonPath("$.paths['/api/auth/login'].post.security").isEmpty())
                .andExpect(jsonPath("$.paths['/api/auth/register'].post.security").isEmpty())
                // 재발급은 쿠키
                .andExpect(jsonPath("$.paths['/api/auth/refresh'].post.security[0].refreshTokenCookie").exists())
                // 보호된 API는 전역 Bearer 요구사항을 그대로 따른다(오퍼레이션에서 덮어쓰지 않음)
                .andExpect(jsonPath("$.security[0].bearerAuth").exists())
                .andExpect(jsonPath("$.paths['/api/users/me'].get.security").doesNotExist())
                // 시세 조회는 선택 인증: 빈 요구사항 + Bearer
                .andExpect(jsonPath("$.paths['/api/markets'].get.security", hasSize(2)))
                .andExpect(jsonPath("$.paths['/api/markets'].get.security[0]").isEmpty())
                .andExpect(jsonPath("$.paths['/api/markets'].get.security[1].bearerAuth").exists());
    }

    @Test
    void 에러_코드가_엔드포인트별_응답으로_문서화된다() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/auth/login'].post.responses['401']").exists())
                .andExpect(jsonPath("$.paths['/api/auth/login'].post.responses['401'].content['application/json'].examples.INVALID_CREDENTIALS").exists())
                .andExpect(jsonPath("$.paths['/api/markets/{symbol}/candles'].get.responses['404'].content['application/json'].examples.MARKET_NOT_FOUND").exists())
                .andExpect(jsonPath("$.components.schemas.ApiErrorResponse.properties.error.enum", hasItem("INVALID_CREDENTIALS")));
    }

    @Test
    void 제네릭_응답_봉투가_데이터_타입별_스키마로_나온다() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/auth/login'].post.responses['200'].content['application/json'].schema.$ref")
                        .value(org.hamcrest.Matchers.startsWith("#/components/schemas/ApiResponse")))
                .andExpect(jsonPath("$.paths['/api/auth/login'].post.responses['200'].headers['Set-Cookie']").exists());
    }
}
