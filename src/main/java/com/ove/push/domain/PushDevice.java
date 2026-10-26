package com.ove.push.domain;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import com.ove.user.domain.User;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 사용자 앱에서 푸시를 받을 기기와 토큰.
 * 푸시 토큰은 기기마다 바뀔 수 있어서 (사용자, 토큰)이 아니라 토큰 자체를 유일하게 둔다.
 */
@Entity
@Table(name = "push_devices")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PushDevice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private DevicePlatform platform;

    // FCM/APNs 토큰은 보통 200자 안팎이다. PostgreSQL unique 인덱스 크기를 넘지 않도록 512자로 제한한다.
    @Column(nullable = false, unique = true, length = 512)
    private String pushToken;

    @Column(nullable = false)
    private boolean alertEnabled;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    private Instant updatedAt;

    @Builder
    private PushDevice(User user, DevicePlatform platform, String pushToken, boolean alertEnabled) {
        this.user = user;
        this.platform = platform;
        this.pushToken = pushToken;
        this.alertEnabled = alertEnabled;
    }

    public boolean belongsTo(UUID userId) {
        return user.getId().equals(userId);
    }

    public void update(DevicePlatform platform, boolean alertEnabled) {
        this.platform = platform;
        this.alertEnabled = alertEnabled;
    }
}
