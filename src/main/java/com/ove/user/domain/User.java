package com.ove.user.domain;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 회원. PostgreSQL에서 user는 예약어라 테이블 이름은 users로 둔다.
 */
@Entity
@Table(name = "users")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User {

    private static final String WITHDRAWN_PASSWORD = "!withdrawn";

    // 탈퇴 시 익명화에 쓰는 값. 가입 때 이 형태를 막아야 남이 먼저 차지해 탈퇴를 방해할 수 없어서 상수로 공유한다.
    public static final String WITHDRAWN_NICKNAME_PREFIX = "탈퇴";
    public static final String WITHDRAWN_EMAIL_DOMAIN = "@deleted.invalid";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true)
    private String email;

    // BCrypt 해시 문자열(60자)
    @Column(nullable = false, length = 60)
    private String password;

    @Column(nullable = false, unique = true, length = 20)
    private String nickname;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    private Instant updatedAt;

    // 기본값(ACTIVE)을 DDL에도 넣어 둔다. ddl-auto=update로 이미 회원이 있는 DB에 컬럼을 추가해도 실패하지 않게 하려는 것이다.
    @ColumnDefault("'ACTIVE'")
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private UserStatus status = UserStatus.ACTIVE;

    // 탈퇴 시각. 탈퇴하지 않았으면 null
    private Instant deletedAt;

    @Builder
    private User(String email, String password, String nickname) {
        this.email = email;
        this.password = password;
        this.nickname = nickname;
        this.status = UserStatus.ACTIVE;
    }

    public boolean isWithdrawn() {
        return status == UserStatus.WITHDRAWN;
    }

    /**
     * 탈퇴 처리. 행은 남기되 개인정보를 지우거나 다른 값으로 바꾼다.
     * 이메일·닉네임은 회원마다 다른 값으로 바꿔서(유일 제약 유지) 같은 이메일·닉네임으로 다시 가입할 수 있게 한다.
     * 비밀번호 해시도 BCrypt 형식이 아닌 값으로 덮어써서 어떤 입력으로도 일치하지 않게 한다.
     */
    public void withdraw(Instant now) {
        this.status = UserStatus.WITHDRAWN;
        this.deletedAt = now;
        this.email = "withdrawn+" + id + WITHDRAWN_EMAIL_DOMAIN;
        this.nickname = WITHDRAWN_NICKNAME_PREFIX + id.toString().replace("-", "").substring(0, 16);
        this.password = WITHDRAWN_PASSWORD;
    }
}
