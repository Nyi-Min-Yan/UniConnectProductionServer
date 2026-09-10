package com.unicconnect.repository;

import com.unicconnect.entity.Position;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface PositionRepository extends JpaRepository<Position, UUID> {
    Optional<Position> findByPositionName(String positionName);
    boolean existsByPositionName(String positionName);
}