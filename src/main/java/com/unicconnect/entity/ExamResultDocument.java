package com.unicconnect.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "exam_result_documents")
@Getter
@Setter
@NoArgsConstructor
public class ExamResultDocument {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "result_document_id")
    private UUID resultDocumentId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "batch_id", nullable = false)
    private ResultBatch batch;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "student_id", nullable = false)
    private Student student;

    @Column(name = "pdf_file_name", nullable = false)
    private String pdfFileName;

    @Column(name = "storage_object_path", nullable = false)
    private String storageObjectPath;

    @Enumerated(EnumType.STRING)
    @Column(name = "release_status", nullable = false, length = 20)
    private ReleaseStatus releaseStatus = ReleaseStatus.PENDING;

    @Column(name = "blocked_reason")
    private String blockedReason;

    @Column(name = "viewed_at")
    private Instant viewedAt;

    @Column(name = "downloaded_at")
    private Instant downloadedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}