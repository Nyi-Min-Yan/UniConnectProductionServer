package com.unicconnect.service;

import com.unicconnect.dto.request.SectionRequest;
import com.unicconnect.dto.response.SectionResponse;
import com.unicconnect.entity.Section;
import com.unicconnect.exception.ResourceNotFoundException;
import com.unicconnect.repository.SectionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class SectionService {

    private final SectionRepository sectionRepository;

    public SectionService(SectionRepository sectionRepository) {
        this.sectionRepository = sectionRepository;
    }

    public List<SectionResponse> getAll() {
        return sectionRepository.findAll().stream().map(SectionService::toResponse).toList();
    }

    public SectionResponse getById(UUID sectionId) {
        return toResponse(findSection(sectionId));
    }

    @Transactional
    public SectionResponse create(SectionRequest request) {
        Section section = new Section();
        section.setSectionName(request.sectionName());
        return toResponse(sectionRepository.save(section));
    }

    @Transactional
    public SectionResponse update(UUID sectionId, SectionRequest request) {
        Section section = findSection(sectionId);
        section.setSectionName(request.sectionName());
        return toResponse(sectionRepository.save(section));
    }

    @Transactional
    public void delete(UUID sectionId) {
        findSection(sectionId);
        sectionRepository.deleteById(sectionId);
    }

    public Section findSection(UUID sectionId) {
        return sectionRepository.findById(sectionId)
                .orElseThrow(() -> new ResourceNotFoundException("Section not found"));
    }

    static SectionResponse toResponse(Section section) {
        return new SectionResponse(section.getSectionId(), section.getSectionName());
    }
}