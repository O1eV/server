package com.ove.push.dto;

import com.ove.push.domain.DevicePlatform;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class PushDeviceRequest {

    @NotNull
    private DevicePlatform platform;

    @NotBlank
    @Size(max = 512)
    private String pushToken;

    @NotNull
    private Boolean alertEnabled;
}
