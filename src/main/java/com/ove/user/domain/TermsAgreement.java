package com.ove.user.domain;

import java.time.Instant;

import org.hibernate.annotations.CreationTimestamp;

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
 * 약관 동의 이력. 언제 어떤 약관에 동의했는지 남겨 두기 위해 약관마다 한 행씩 저장한다.
 */
@Entity
@Table(name = "terms_agreements")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TermsAgreement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private TermsType termsType;

    @Column(nullable = false)
    private boolean agreed;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant agreedAt;

    @Builder
    private TermsAgreement(User user, TermsType termsType, boolean agreed) {
        this.user = user;
        this.termsType = termsType;
        this.agreed = agreed;
    }
}
