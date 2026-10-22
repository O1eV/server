package com.ove.user.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.ove.user.domain.User;
import com.ove.user.domain.UserStatus;

public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);

    // 탈퇴하지 않은 회원인지 확인할 때 사용(재발급 요청 등)
    boolean existsByIdAndStatus(UUID id, UserStatus status);

    boolean existsByNickname(String nickname);
}
