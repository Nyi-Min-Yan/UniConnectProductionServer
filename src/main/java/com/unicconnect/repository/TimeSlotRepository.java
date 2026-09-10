package com.unicconnect.repository;

import com.unicconnect.entity.TimeSlot;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface TimeSlotRepository extends JpaRepository<TimeSlot, UUID> {
    List<TimeSlot> findAllByOrderByDisplayOrderAscPeriodNoAsc();
}