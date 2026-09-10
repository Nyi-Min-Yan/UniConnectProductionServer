package com.unicconnect.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "teaching_assignment_group_members")
@Getter
@Setter
@NoArgsConstructor
public class TeachingAssignmentGroupMember {

    @EmbeddedId
    private TeachingAssignmentGroupMemberId id;

    @MapsId("groupId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "group_id", nullable = false)
    private TeachingAssignmentGroup group;

    @MapsId("assignmentId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "assignment_id", nullable = false)
    private TeachingAssignment assignment;

    public TeachingAssignmentGroupMember(TeachingAssignmentGroup group,
                                         TeachingAssignment assignment) {
        this.group = group;
        this.assignment = assignment;
        this.id = new TeachingAssignmentGroupMemberId(group.getGroupId(), assignment.getAssignmentId());
    }
}
