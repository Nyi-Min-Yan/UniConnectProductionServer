package com.unicconnect.service;

import com.unicconnect.dto.request.PositionRequest;
import com.unicconnect.dto.response.PositionResponse;
import com.unicconnect.entity.Position;
import com.unicconnect.exception.DuplicateResourceException;
import com.unicconnect.exception.ResourceNotFoundException;
import com.unicconnect.repository.PositionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class PositionService {

    private final PositionRepository positionRepository;

    public PositionService(PositionRepository positionRepository) {
        this.positionRepository = positionRepository;
    }

    public List<PositionResponse> getAll() {
        return positionRepository.findAll().stream().map(PositionService::toResponse).toList();
    }

    public PositionResponse getById(UUID positionId) {
        return toResponse(findPosition(positionId));
    }

    @Transactional
    public PositionResponse create(PositionRequest request) {
        if (positionRepository.existsByPositionName(request.positionName())) {
            throw new DuplicateResourceException("Position name already exists: " + request.positionName());
        }
        Position position = new Position();
        position.setPositionName(request.positionName());
        position.setDescription(request.description());
        return toResponse(positionRepository.save(position));
    }

    @Transactional
    public PositionResponse update(UUID positionId, PositionRequest request) {
        Position position = findPosition(positionId);
        if (!position.getPositionName().equals(request.positionName())
                && positionRepository.existsByPositionName(request.positionName())) {
            throw new DuplicateResourceException("Position name already exists: " + request.positionName());
        }
        position.setPositionName(request.positionName());
        position.setDescription(request.description());
        return toResponse(positionRepository.save(position));
    }

    @Transactional
    public void delete(UUID positionId) {
        findPosition(positionId);
        positionRepository.deleteById(positionId);
    }

    public Position findPosition(UUID positionId) {
        return positionRepository.findById(positionId)
                .orElseThrow(() -> new ResourceNotFoundException("Position not found"));
    }

    static PositionResponse toResponse(Position position) {
        return new PositionResponse(position.getPositionId(), position.getPositionName(), position.getDescription());
    }
}