package com.unicconnect.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

@Entity
@Table(name = "exam_types")
@Getter
@Setter
@NoArgsConstructor
public class ExamType {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "exam_type_id")
    private UUID examTypeId;

    @Column(name = "exam_type_name", nullable = false, unique = true)
    private String examTypeName;
}