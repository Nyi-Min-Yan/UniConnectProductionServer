package com.unicconnect.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

@Entity
@Table(name = "majors")
@Getter
@Setter
@NoArgsConstructor
public class Major {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "major_id")
    private UUID majorId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "unit_id", nullable = false)
    private OrganizationalUnit unit;

    @Column(name = "major_code", nullable = false)
    private String majorCode;

    @Column(name = "major_name", nullable = false)
    private String majorName;
}