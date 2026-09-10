package com.unicconnect.repository;

import com.unicconnect.entity.Staff;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface StaffRepository extends JpaRepository<Staff, UUID> {
    Optional<Staff> findByStaffNo(String staffNo);
    boolean existsByStaffNo(String staffNo);
    Optional<Staff> findByUser_UserId(UUID userId);
    boolean existsByUser_UserId(UUID userId);
    List<Staff> findByUnit_UnitId(UUID unitId);

    @Query("select st from Staff st join fetch st.user left join fetch st.unit")
    List<Staff> findAllWithUserAndUnit();
}