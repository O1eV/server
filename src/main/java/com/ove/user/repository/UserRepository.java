package com.ove.user.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.ove.user.domain.User;

import jakarta.persistence.LockModeType;
import com.ove.user.domain.UserStatus;

public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);

    // 탈퇴하지 않은 회원인지 확인할 때 사용(재발급 요청 등)
    boolean existsByIdAndStatus(UUID id, UserStatus status);

    boolean existsByNickname(String nickname);

    // 같은 회원의 요청을 한 줄로 세울 때 사용 (회원 행에 쓰기 잠금을 건다. 트랜잭션 안에서만 의미가 있다)
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.id = :id")
    Optional<User> findByIdForUpdate(@Param("id") UUID id);
}
