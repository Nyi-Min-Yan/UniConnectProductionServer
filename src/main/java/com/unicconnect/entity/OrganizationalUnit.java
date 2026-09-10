package com.unicconnect.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

@Entity
@Table(name = "organizational_units")
@Getter
@Setter
@NoArgsConstructor
public class OrganizationalUnit {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "unit_id")
    private UUID unitId;

    @Column(name = "unit_name", nullable = false)
    private String unitName;

    @Column(name = "unit_code", nullable = false, unique = true)
    private String unitCode;

    @Column(name = "unit_type")
    private String unitType;

    @Column(name = "description")
    private String description;
}