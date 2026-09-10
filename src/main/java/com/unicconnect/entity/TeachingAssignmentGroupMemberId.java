package com.unicconnect.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

@Embeddable
@Getter
@Setter
@NoArgsConstructor
public class TeachingAssignmentGroupMemberId implements Serializable {

    @Column(name = "group_id")
    private UUID groupId;

    @Column(name = "assignment_id")
    private UUID assignmentId;

    public TeachingAssignmentGroupMemberId(UUID groupId, UUID assignmentId) {
        this.groupId = groupId;
        this.assignmentId = assignmentId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof TeachingAssignmentGroupMemberId that)) return false;
        return Objects.equals(groupId, that.groupId)
                && Objects.equals(assignmentId, that.assignmentId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(groupId, assignmentId);
    }
}
