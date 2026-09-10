package com.unicconnect.service;

import com.unicconnect.dto.request.CreateUserRequest;
import com.unicconnect.dto.request.UpdateMeRequest;
import com.unicconnect.dto.request.UpdateUserRequest;
import com.unicconnect.dto.request.UpdateUserRoleRequest;
import com.unicconnect.dto.request.UpdateUserStatusRequest;
import com.unicconnect.dto.response.UserResponse;
import com.unicconnect.entity.RegistrationStatus;
import com.unicconnect.entity.Role;
import com.unicconnect.entity.User;
import com.unicconnect.exception.DuplicateResourceException;
import com.unicconnect.exception.ResourceNotFoundException;
import com.unicconnect.exception.ValidationException;
import com.unicconnect.repository.RefreshTokenRepository;
import com.unicconnect.repository.RoleRepository;
import com.unicconnect.repository.StaffPositionAssignmentRepository;
import com.unicconnect.repository.StaffRepository;
import com.unicconnect.repository.StudentRepository;
import com.unicconnect.repository.UserRepository;
import com.unicconnect.util.SecurityUtil;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class UserService {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;
    private final StudentRepository studentRepository;
    private final StaffRepository staffRepository;
    private final StaffPositionAssignmentRepository assignmentRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final SecurityUtil securityUtil;

    public UserService(UserRepository userRepository,
                       RoleRepository roleRepository,
                       PasswordEncoder passwordEncoder,
                       StudentRepository studentRepository,
                       StaffRepository staffRepository,
                       StaffPositionAssignmentRepository assignmentRepository,
                       RefreshTokenRepository refreshTokenRepository,
                       SecurityUtil securityUtil) {
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.passwordEncoder = passwordEncoder;
        this.studentRepository = studentRepository;
        this.staffRepository = staffRepository;
        this.assignmentRepository = assignmentRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.securityUtil = securityUtil;
    }

    public List<UserResponse> getAllUsers() {
        return userRepository.findAllWithRole().stream().map(UserService::toResponse).toList();
    }

    @Transactional
    public UserResponse createUser(CreateUserRequest request) {
        if (userRepository.existsByEmail(request.email().toLowerCase())) {
            throw new DuplicateResourceException("Email is already in use");
        }
        Role role = roleRepository.findByRoleName(request.roleName())
                .orElseThrow(() -> new ResourceNotFoundException("Role not found: " + request.roleName()));
        User user = new User();
        user.setEmail(request.email().toLowerCase());
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setRole(role);
        user.setActive(request.isActive() == null || request.isActive());
        user.setRegistrationStatus(RegistrationStatus.APPROVED);
        return toResponse(userRepository.save(user));
    }

    public UserResponse getUserById(UUID userId) {
        return toResponse(findUser(userId));
    }

    public List<UserResponse> getUsersByRole(String roleName) {
        return userRepository.findAllWithRole().stream()
                .filter(u -> u.getRole().getRoleName().equalsIgnoreCase(roleName))
                .map(UserService::toResponse)
                .toList();
    }

    public UserResponse getMe(UUID userId) {
        return toResponse(findUser(userId));
    }

    @Transactional
    public UserResponse updateMe(UUID userId, UpdateMeRequest request) {
        User user = findUser(userId);

        if (request.email() != null && !request.email().isBlank() && !request.email().equals(user.getEmail())) {
            if (userRepository.existsByEmail(request.email())) {
                throw new DuplicateResourceException("Email is already in use");
            }
            user.setEmail(request.email());
        }

        if (request.newPassword() != null && !request.newPassword().isBlank()) {
            if (request.currentPassword() == null
                    || !passwordEncoder.matches(request.currentPassword(), user.getPasswordHash())) {
                throw new ValidationException("Current password is incorrect");
            }
            user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        }

        return toResponse(userRepository.save(user));
    }

    @Transactional
    public UserResponse updateStatus(UUID userId, UpdateUserStatusRequest request) {
        User user = findUser(userId);
        if (request.isActive() != null) {
            user.setActive(request.isActive());
        }
        if (request.registrationStatus() != null) {
            user.setRegistrationStatus(request.registrationStatus());
        }
        return toResponse(userRepository.save(user));
    }

    @Transactional
    public UserResponse updateUser(UUID userId, UpdateUserRequest request) {
        User user = findUser(userId);
        if (request.email() != null && !request.email().isBlank() && !request.email().equals(user.getEmail())) {
            if (userRepository.existsByEmail(request.email())) {
                throw new DuplicateResourceException("Email is already in use");
            }
            user.setEmail(request.email());
        }
        if (request.isActive() != null) {
            user.setActive(request.isActive());
        }
        return toResponse(userRepository.save(user));
    }

    @Transactional
    public void deleteUser(UUID userId) {
        User user = findUser(userId);
        if (userId.equals(securityUtil.currentUserId())) {
            throw new ValidationException("You cannot delete your own account");
        }
        try {
            studentRepository.findByUser_UserId(userId).ifPresent(studentRepository::delete);
            staffRepository.findByUser_UserId(userId).ifPresent(staff -> {
                assignmentRepository.deleteAll(assignmentRepository.findByStaff_StaffId(staff.getStaffId()));
                staffRepository.delete(staff);
            });
            refreshTokenRepository.deleteAll(refreshTokenRepository.findByUser(user));
            userRepository.delete(user);
            userRepository.flush();
        } catch (DataIntegrityViolationException ex) {
            throw new ValidationException(
                    "Cannot delete this account because it has related records "
                            + "(e.g. attendance, results, or teaching assignments)");
        }
    }

    @Transactional
    public void deleteUsers(List<UUID> userIds) {
        for (UUID userId : userIds) {
            deleteUser(userId);
        }
    }

    @Transactional
    public UserResponse updateRole(UUID userId, UpdateUserRoleRequest request) {
        User user = findUser(userId);
        Role role = roleRepository.findById(request.roleId())
                .orElseThrow(() -> new ResourceNotFoundException("Role not found"));
        user.setRole(role);
        return toResponse(userRepository.save(user));
    }

    public User findUser(UUID userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
    }

    static UserResponse toResponse(User user) {
        return new UserResponse(
                user.getUserId(),
                user.getEmail(),
                user.getRole().getRoleName(),
                user.isActive(),
                user.getRegistrationStatus(),
                user.getLastLogin(),
                user.getCreatedAt()
        );
    }
}