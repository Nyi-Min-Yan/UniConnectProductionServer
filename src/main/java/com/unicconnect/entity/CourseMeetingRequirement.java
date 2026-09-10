package com.unicconnect.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

@Entity
@Table(name = "course_meeting_requirements")
@Getter
@Setter
@NoArgsConstructor
public class CourseMeetingRequirement {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "requirement_id")
    private UUID requirementId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "course_id", nullable = false)
    private Course course;

    @Enumerated(EnumType.STRING)
    @Column(name = "meeting_type", nullable = false, length = 20)
    private MeetingType meetingType;

    @Column(name = "sessions_per_week", nullable = false)
    private Integer sessionsPerWeek;

    @Column(name = "periods_per_session", nullable = false)
    private Integer periodsPerSession;
}