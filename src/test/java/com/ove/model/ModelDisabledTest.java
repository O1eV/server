package com.ove.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.ove.auth.repository.TokenRedisRepository;
import com.ove.model.client.ModelClient;
import com.ove.model.service.SignalGenerationService;

/** 기본값(model.enabled=false)에서는 모델 연동 빈이 하나도 없다. */
@SpringBootTest
class ModelDisabledTest {

    @Autowired
    ApplicationContext context;
    @MockitoBean
    TokenRedisRepository tokens;

    @Test
    @DisplayName("꺼져 있으면 ModelClient·SignalGenerationService 빈이 없다")
    void noBeansByDefault() {
        assertThat(context.getBeansOfType(ModelClient.class)).isEmpty();
        assertThat(context.getBeansOfType(SignalGenerationService.class)).isEmpty();
    }
}
