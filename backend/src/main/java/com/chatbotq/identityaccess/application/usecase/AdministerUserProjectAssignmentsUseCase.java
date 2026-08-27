package com.chatbotq.identityaccess.application.usecase;

import com.chatbotq.identityaccess.application.port.ApplicationTransaction;
import com.chatbotq.identityaccess.application.port.UserProjectAssignmentRepository;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

public final class AdministerUserProjectAssignmentsUseCase {
    public static final int MAX_PROJECT_IDS = 1000;
    private final UserProjectAssignmentRepository assignments;
    private final ApplicationTransaction transactions;
    private final Clock clock;

    public AdministerUserProjectAssignmentsUseCase(UserProjectAssignmentRepository assignments,
                                                    ApplicationTransaction transactions, Clock clock) {
        this.assignments = require(assignments, "assignments");
        this.transactions = require(transactions, "transactions");
        this.clock = require(clock, "clock");
    }

    public List<UUID> list(UUID actorId, UUID userId) {
        return sorted(assignments.listAsGeneralAdmin(require(actorId, "actorId"), require(userId, "userId")));
    }

    public List<UUID> replace(UUID actorId, UUID userId, List<UUID> projectIds) {
        final UUID actor = require(actorId, "actorId");
        final UUID target = require(userId, "userId");
        final List<UUID> validated = validate(projectIds);
        return transactions.execute(() -> sorted(assignments.replaceAsGeneralAdmin(
            actor, target, validated, clock.instant())));
    }

    public List<UUID> activeForCurrentUser(UUID userId) {
        return sorted(assignments.listActiveForCurrentUser(require(userId, "userId")));
    }

    private static List<UUID> validate(List<UUID> values) {
        if (values == null) throw new IllegalArgumentException("projectIds is required");
        if (values.size() > MAX_PROJECT_IDS) throw new IllegalArgumentException("too many projectIds");
        List<UUID> copy = new ArrayList<>(values.size());
        HashSet<UUID> seen = new HashSet<>();
        for (UUID value : values) {
            if (value == null) throw new IllegalArgumentException("projectIds must not contain null");
            if (!seen.add(value)) throw new IllegalArgumentException("duplicate projectId");
            copy.add(value);
        }
        Collections.sort(copy, (left, right) -> left.toString().compareTo(right.toString()));
        return Collections.unmodifiableList(copy);
    }
    private static List<UUID> sorted(List<UUID> values) {
        if (values == null) throw new IllegalStateException("assignment port returned null");
        List<UUID> result = new ArrayList<>(values);
        Collections.sort(result, (left, right) -> left.toString().compareTo(right.toString()));
        return Collections.unmodifiableList(result);
    }
    private static <T> T require(T value, String name) {
        if (value == null) throw new IllegalArgumentException(name + " must not be null"); return value;
    }
}
