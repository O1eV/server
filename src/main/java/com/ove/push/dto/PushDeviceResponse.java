package com.ove.push.dto;

import com.ove.push.domain.DevicePlatform;
import com.ove.push.domain.PushDevice;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class PushDeviceResponse {

    private final Long deviceId;
    private final DevicePlatform platform;
    private final boolean alertEnabled;

    public static PushDeviceResponse from(PushDevice device) {
        return PushDeviceResponse.builder()
                .deviceId(device.getId())
                .platform(device.getPlatform())
                .alertEnabled(device.isAlertEnabled())
                .build();
    }
}
