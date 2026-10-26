package com.ove.push.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.ove.push.domain.PushDevice;

public interface PushDeviceRepository extends JpaRepository<PushDevice, Long> {

    Optional<PushDevice> findByPushToken(String pushToken);

    // 관심 종목을 가진 사용자 중 알림을 켠 기기만 찾을 때 사용
    List<PushDevice> findAllByUserIdInAndAlertEnabledTrue(Collection<UUID> userIds);

    // 회원탈퇴 때 기기 토큰을 남기지 않도록 한 번에 지우는 용도
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from PushDevice device where device.user.id = :userId")
    int deleteAllByUserId(@Param("userId") UUID userId);
}
