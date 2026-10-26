package com.ove.global.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import com.ove.support.IntegrationTestSupport;

// 운영 등에서 환경변수로 끌 수 있는지 확인한다
@TestPropertySource(properties = {"springdoc.api-docs.enabled=false", "springdoc.swagger-ui.enabled=false"})
class OpenApiDocsDisabledTest extends IntegrationTestSupport {

    @Test
    void 설정으로_끄면_문서_경로가_열리지_않는다() throws Exception {
        mockMvc.perform(get("/v3/api-docs")).andExpect(status().isNotFound());
    }
}
