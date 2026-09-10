package com.unicconnect.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "generation_sessions")
@Getter
@Setter
@NoArgsConstructor
public class GenerationSession {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "generation_id")
    private UUID generationId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "term_id", nullable = false)
    private AcademicTerm term;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "generated_by_staff_id", nullable = false)
    private Staff generatedByStaff;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private GenerationStatus status = GenerationStatus.PENDING;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

@Column(name = "scope_json")
private String scopeJson;
@Column(name = "failure_report")
private String failureReport;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}