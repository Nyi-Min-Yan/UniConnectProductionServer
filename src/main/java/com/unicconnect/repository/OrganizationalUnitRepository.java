package com.unicconnect.repository;

import com.unicconnect.entity.OrganizationalUnit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface OrganizationalUnitRepository extends JpaRepository<OrganizationalUnit, UUID> {
    Optional<OrganizationalUnit> findByUnitCode(String unitCode);
    Optional<OrganizationalUnit> findByUnitName(String unitName);
    boolean existsByUnitCode(String unitCode);
}