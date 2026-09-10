package com.unicconnect.controller;

import com.unicconnect.dto.request.CreateLobbyRequest;
import com.unicconnect.dto.request.InviteLobbyMemberRequest;
import com.unicconnect.dto.response.TimetableLobbyResponse;
import com.unicconnect.service.TimetableLobbyService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/timetable-lobbies")
public class TimetableLobbyController {

    private final TimetableLobbyService service;

    public TimetableLobbyController(TimetableLobbyService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<List<TimetableLobbyResponse>> getAll() {
        return ResponseEntity.ok(service.list());
    }

    @GetMapping("/{lobbyId}")
    public ResponseEntity<TimetableLobbyResponse> getById(@PathVariable UUID lobbyId) {
        return ResponseEntity.ok(service.getById(lobbyId));
    }

    @PostMapping
    public ResponseEntity<TimetableLobbyResponse> create(
            @RequestBody(required = false) CreateLobbyRequest request) {
        return ResponseEntity.ok(service.create(request));
    }

    @PostMapping("/{lobbyId}/join")
    public ResponseEntity<TimetableLobbyResponse> join(@PathVariable UUID lobbyId) {
        return ResponseEntity.ok(service.join(lobbyId));
    }

    @PostMapping("/{lobbyId}/invite")
    public ResponseEntity<TimetableLobbyResponse> invite(
            @PathVariable UUID lobbyId,
            @Valid @RequestBody InviteLobbyMemberRequest request) {
        return ResponseEntity.ok(service.invite(lobbyId, request));
    }

    @PostMapping("/{lobbyId}/cancel")
    public ResponseEntity<TimetableLobbyResponse> cancel(@PathVariable UUID lobbyId) {
        return ResponseEntity.ok(service.cancel(lobbyId));
    }

    @PostMapping("/{lobbyId}/generate")
    public ResponseEntity<TimetableLobbyResponse> generate(@PathVariable UUID lobbyId) {
        return ResponseEntity.ok(service.generate(lobbyId));
    }
}
