package com.unicconnect.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "academic_terms")
@Getter
@Setter
@NoArgsConstructor
public class AcademicTerm {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "term_id")
    private UUID termId;

    @Column(name = "academic_year", nullable = false)
    private String academicYear;

    @Column(name = "start_date")
    private LocalDate startDate;

    @Column(name = "end_date")
    private LocalDate endDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private TermStatus status = TermStatus.ACTIVE;
}