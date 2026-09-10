package com.unicconnect.repository;

import com.unicconnect.entity.RefreshToken;
import com.unicconnect.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {
    Optional<RefreshToken> findByTokenHash(String tokenHash);
    List<RefreshToken> findByUserAndRevokedFalse(User user);
    List<RefreshToken> findByUser(User user);
}