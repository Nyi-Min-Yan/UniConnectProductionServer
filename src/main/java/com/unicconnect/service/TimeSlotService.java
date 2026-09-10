package com.unicconnect.service;

import com.unicconnect.dto.request.TimeSlotRequest;
import com.unicconnect.dto.response.TimeSlotResponse;
import com.unicconnect.entity.TimeSlot;
import com.unicconnect.exception.ResourceNotFoundException;
import com.unicconnect.exception.ValidationException;
import com.unicconnect.repository.TimeSlotRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class TimeSlotService {

    private final TimeSlotRepository timeSlotRepository;

    public TimeSlotService(TimeSlotRepository timeSlotRepository) {
        this.timeSlotRepository = timeSlotRepository;
    }

    public List<TimeSlotResponse> getAll() {
        return timeSlotRepository.findAllByOrderByDisplayOrderAscPeriodNoAsc().stream()
                .map(TimeSlotService::toResponse).toList();
    }

    public TimeSlotResponse getById(UUID slotId) {
        return toResponse(findSlot(slotId));
    }

    @Transactional
    public TimeSlotResponse create(TimeSlotRequest request) {
        validate(request);
        TimeSlot slot = new TimeSlot();
        apply(slot, request);
        return toResponse(timeSlotRepository.save(slot));
    }

    @Transactional
    public TimeSlotResponse update(UUID slotId, TimeSlotRequest request) {
        validate(request);
        TimeSlot slot = findSlot(slotId);
        apply(slot, request);
        return toResponse(timeSlotRepository.save(slot));
    }

    @Transactional
    public void delete(UUID slotId) {
        findSlot(slotId);
        timeSlotRepository.deleteById(slotId);
    }

    private void validate(TimeSlotRequest request) {
        if (!request.endTime().isAfter(request.startTime())) {
            throw new ValidationException("endTime must be after startTime");
        }
    }

    private void apply(TimeSlot slot, TimeSlotRequest request) {
        slot.setPeriodNo(request.periodNo());
        slot.setStartTime(request.startTime());
        slot.setEndTime(request.endTime());
        slot.setDisplayOrder(request.displayOrder() != null ? request.displayOrder() : 0);
    }

    public TimeSlot findSlot(UUID slotId) {
        return timeSlotRepository.findById(slotId)
                .orElseThrow(() -> new ResourceNotFoundException("Time slot not found"));
    }

    static TimeSlotResponse toResponse(TimeSlot slot) {
        return new TimeSlotResponse(slot.getSlotId(), slot.getPeriodNo(),
                slot.getStartTime(), slot.getEndTime(), slot.getDisplayOrder());
    }
}