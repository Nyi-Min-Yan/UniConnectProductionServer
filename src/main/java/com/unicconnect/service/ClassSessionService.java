package com.unicconnect.service;

import com.unicconnect.dto.request.ClassSessionRequest;
import com.unicconnect.dto.response.ClassSessionResponse;
import com.unicconnect.entity.*;
import com.unicconnect.exception.BusinessRuleException;
import com.unicconnect.exception.DuplicateResourceException;
import com.unicconnect.exception.ResourceNotFoundException;
import com.unicconnect.repository.ClassSessionRepository;
import com.unicconnect.repository.ClassScheduleRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class ClassSessionService {

    private final ClassSessionRepository sessionRepository;
    private final ClassScheduleRepository scheduleRepository;

    public ClassSessionService(ClassSessionRepository sessionRepository,
                               ClassScheduleRepository scheduleRepository) {
        this.sessionRepository = sessionRepository;
        this.scheduleRepository = scheduleRepository;
    }

    public List<ClassSessionResponse> getAll(UUID scheduleId) {
        List<ClassSession> sessions = scheduleId != null
                ? sessionRepository.findBySchedule_ScheduleId(scheduleId)
                : sessionRepository.findAll();
        return sessions.stream().map(ClassSessionService::toResponse).toList();
    }

    public ClassSessionResponse getById(UUID sessionId) {
        return toResponse(findSession(sessionId));
    }

    @Transactional
    public ClassSessionResponse create(ClassSessionRequest request) {
        ClassSchedule schedule = scheduleRepository.findById(request.scheduleId())
                .orElseThrow(() -> new ResourceNotFoundException("Class schedule not found"));
        if (sessionRepository.existsBySchedule_ScheduleIdAndSessionDate(
                request.scheduleId(), request.sessionDate())) {
            throw new DuplicateResourceException(
                    "A session already exists for this schedule on " + request.sessionDate());
        }

        ClassSession session = new ClassSession();
        session.setSchedule(schedule);
        session.setSessionDate(request.sessionDate());
        session.setSessionStatus(request.sessionStatus() != null
                ? request.sessionStatus() : SessionStatus.SCHEDULED);
        session.setStartedAt(request.startedAt());
        session.setEndedAt(request.endedAt());
        return toResponse(sessionRepository.save(session));
    }

    @Transactional
    public ClassSessionResponse update(UUID sessionId, ClassSessionRequest request) {
        ClassSession session = findSession(sessionId);
        ClassSchedule schedule = session.getSchedule();
        if (schedule.getGeneration().getStatus() == GenerationStatus.PUBLISHED) {
            throw new BusinessRuleException("Cannot modify sessions of a published timetable schedule");
        }
        if (!session.getSchedule().getScheduleId().equals(request.scheduleId())) {
            throw new BusinessRuleException("Cannot change the schedule of an existing session");
        }
        if (!session.getSessionDate().equals(request.sessionDate())
                && sessionRepository.existsBySchedule_ScheduleIdAndSessionDate(
                        session.getSchedule().getScheduleId(), request.sessionDate())) {
            throw new DuplicateResourceException(
                    "A session already exists for this schedule on " + request.sessionDate());
        }
        session.setSessionDate(request.sessionDate());
        if (request.sessionStatus() != null) {
            session.setSessionStatus(request.sessionStatus());
        }
        session.setStartedAt(request.startedAt());
        session.setEndedAt(request.endedAt());
        return toResponse(sessionRepository.save(session));
    }

    @Transactional
    public void delete(UUID sessionId) {
        ClassSession session = findSession(sessionId);
        ClassSchedule schedule = session.getSchedule();
        if (schedule.getGeneration().getStatus() == GenerationStatus.PUBLISHED) {
            throw new BusinessRuleException("Cannot delete sessions of a published timetable schedule");
        }
        sessionRepository.deleteById(sessionId);
    }

    @Transactional
    public ClassSessionResponse startSession(UUID sessionId) {
        ClassSession session = findSession(sessionId);
        if (session.getSessionStatus() != SessionStatus.SCHEDULED) {
            throw new BusinessRuleException("Only a SCHEDULED session can be started");
        }
        session.setSessionStatus(SessionStatus.ONGOING);
        session.setStartedAt(Instant.now());
        return toResponse(sessionRepository.save(session));
    }

    @Transactional
    public ClassSessionResponse endSession(UUID sessionId) {
        ClassSession session = findSession(sessionId);
        if (session.getSessionStatus() != SessionStatus.ONGOING) {
            throw new BusinessRuleException("Only an ONGOING session can be ended");
        }
        session.setSessionStatus(SessionStatus.COMPLETED);
        session.setEndedAt(Instant.now());
        return toResponse(sessionRepository.save(session));
    }

    public ClassSession findSession(UUID sessionId) {
        return sessionRepository.findById(sessionId)
                .orElseThrow(() -> new ResourceNotFoundException("Class session not found"));
    }

    static ClassSessionResponse toResponse(ClassSession session) {
        ClassSchedule schedule = session.getSchedule();
        String courseCode = null;
        UUID sectionId = null;
        String sectionName = null;
        if (schedule.getTeachingAssignment() != null) {
            courseCode = schedule.getTeachingAssignment().getCourse().getCourseCode();
            sectionId = schedule.getTeachingAssignment().getSection().getSectionId();
            sectionName = schedule.getTeachingAssignment().getSection().getSectionName();
        } else if (schedule.getTeachingGroup() != null) {
            // Combined (shared) class: expose the group course; the section name is
            // rendered as the joined member sections for display purposes.
            TeachingAssignmentGroup group = schedule.getTeachingGroup();
            courseCode = group.getCourse().getCourseCode();
            List<String> names = new ArrayList<>();
            for (TeachingAssignmentGroupMember m : group.getMembers()) {
                names.add(m.getAssignment().getSection().getSectionName());
            }
            names.sort(String::compareTo);
            sectionName = String.join(" + ", names);
        }
        return new ClassSessionResponse(
                session.getSessionId(),
                schedule.getScheduleId(),
                schedule.getGeneration().getTerm().getTermId(),
                courseCode,
                sectionId,
                sectionName,
                session.getSessionDate(),
                session.getSessionStatus(),
                session.getStartedAt(),
                session.getEndedAt());
    }
}
