package com.unicconnect.service;

import com.unicconnect.dto.request.LoginRequest;
import com.unicconnect.dto.request.RefreshTokenRequest;
import com.unicconnect.dto.response.AuthResponse;
import com.unicconnect.entity.RefreshToken;
import com.unicconnect.entity.User;
import com.unicconnect.exception.ResourceNotFoundException;
import com.unicconnect.repository.RefreshTokenRepository;
import com.unicconnect.repository.UserRepository;
import com.unicconnect.security.JwtUtil;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
@Transactional
public class AuthService {

    private static final int MAX_FAILED_ATTEMPTS = 5;
    private static final int LOCK_DURATION_MINUTES = 15;

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;

    public AuthService(UserRepository userRepository,
                       RefreshTokenRepository refreshTokenRepository,
                       PasswordEncoder passwordEncoder,
                       JwtUtil jwtUtil) {
        this.userRepository = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtUtil = jwtUtil;
    }

    public AuthResponse login(LoginRequest request, String deviceName, String ipAddress) {
        User user = userRepository.findByEmail(request.email())
                .orElseThrow(() -> new BadCredentialsException("Invalid email or password"));

        if (user.getAccountLockedUntil() != null && Instant.now().isAfter(user.getAccountLockedUntil())) {
            user.setAccountLockedUntil(null);
            user.setFailedLoginAttempts(0);
            userRepository.save(user);
        }

        if (!user.isActive()) {
            throw new DisabledException("Account is disabled");
        }

        if (user.isLocked()) {
            throw new LockedException("Account is locked due to too many failed login attempts. Try again later.");
        }

        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            int attempts = user.getFailedLoginAttempts() + 1;
            user.setFailedLoginAttempts(attempts);
            if (attempts >= MAX_FAILED_ATTEMPTS) {
                user.setAccountLockedUntil(Instant.now().plusSeconds(LOCK_DURATION_MINUTES * 60));
                userRepository.save(user);
                throw new LockedException("Account locked due to too many failed login attempts. Try again in 15 minutes.");
            }
            userRepository.save(user);
            throw new BadCredentialsException("Invalid email or password. "
                    + (MAX_FAILED_ATTEMPTS - attempts) + " attempt(s) remaining before account lockout.");
        }

        user.setFailedLoginAttempts(0);
        user.setAccountLockedUntil(null);
        user.setLastLogin(Instant.now());
        userRepository.save(user);

        return issueTokens(user, deviceName, ipAddress);
    }

    public AuthResponse refresh(RefreshTokenRequest request) {
        String refreshTokenStr = request.refreshToken();
        if (!jwtUtil.validateToken(refreshTokenStr)) {
            throw new BadCredentialsException("Invalid refresh token");
        }

        String tokenHash = jwtUtil.hashToken(refreshTokenStr);
        RefreshToken storedToken = refreshTokenRepository.findByTokenHash(tokenHash)
                .orElseThrow(() -> new BadCredentialsException("Refresh token not found"));

        if (storedToken.isRevoked() || storedToken.isExpired()) {
            throw new BadCredentialsException("Refresh token is revoked or expired");
        }

        User user = storedToken.getUser();
        if (!user.isActive()) {
            throw new DisabledException("Account is disabled");
        }

        storedToken.setRevoked(true);
        refreshTokenRepository.save(storedToken);

        return issueTokens(user, storedToken.getDeviceName(), storedToken.getIpAddress());
    }

    public void logout(UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        refreshTokenRepository.findByUserAndRevokedFalse(user)
                .forEach(token -> {
                    token.setRevoked(true);
                    refreshTokenRepository.save(token);
                });
    }

    private AuthResponse issueTokens(User user, String deviceName, String ipAddress) {
        String accessToken = jwtUtil.generateAccessToken(user.getUserId(), user.getEmail(), user.getRole().getRoleName());
        String refreshTokenStr = jwtUtil.generateRefreshToken(user.getUserId(), user.getEmail());

        RefreshToken refreshToken = new RefreshToken(
                user,
                jwtUtil.hashToken(refreshTokenStr),
                Instant.now().plusSeconds(jwtUtil.getRefreshTokenExpirationMs() / 1000),
                deviceName,
                ipAddress
        );
        refreshTokenRepository.save(refreshToken);

        return new AuthResponse(accessToken, refreshTokenStr,
                jwtUtil.getAccessTokenExpirationMs() / 1000,
                user.getUserId(), user.getEmail(), user.getRole().getRoleName(), user.isActive());
    }
}