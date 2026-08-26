package com.chatbotq.projects.application.usecase;

import com.chatbotq.projects.application.model.ManagedProject;
import com.chatbotq.projects.application.model.ManagedProjectPage;
import com.chatbotq.projects.application.port.ProjectAdministrationPort;
import com.chatbotq.projects.application.port.ProjectIdentityGenerator;

import java.time.Clock;
import java.util.UUID;

public final class AdministerProjectsUseCase {
    public static final int DEFAULT_PAGE_SIZE = 20;
    public static final int MAX_PAGE_SIZE = 100;
    public static final long MAX_OFFSET = 1_000_000L;

    private final ProjectAdministrationPort projects;
    private final ProjectIdentityGenerator identities;
    private final Clock clock;

    public AdministerProjectsUseCase(ProjectAdministrationPort projects,
                                     ProjectIdentityGenerator identities,
                                     Clock clock) {
        this.projects = require(projects, "projects");
        this.identities = require(identities, "identities");
        this.clock = require(clock, "clock");
    }

    public ManagedProject create(UUID actorId, String name) {
        return projects.createAsGeneralAdmin(require(actorId, "actorId"), identities.newProjectId(),
            normalizeName(name), identities.newSiteKey(), clock.instant());
    }

    public ManagedProject get(UUID actorId, UUID projectId) {
        return projects.findVisibleById(require(actorId, "actorId"), require(projectId, "projectId"));
    }

    public ManagedProjectPage list(UUID actorId, int page, int size) {
        require(actorId, "actorId");
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("page must be non-negative and size must be between 1 and 100");
        }
        final long offset;
        try {
            offset = Math.multiplyExact((long) page, (long) size);
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("requested page offset is too large", overflow);
        }
        if (offset > MAX_OFFSET) throw new IllegalArgumentException("requested page offset is too large");
        return projects.listAsGeneralAdmin(actorId, page, size, offset);
    }

    public ManagedProject updateName(UUID actorId, UUID projectId, String name) {
        return projects.updateName(require(actorId, "actorId"), require(projectId, "projectId"),
            normalizeName(name), clock.instant());
    }

    public void activate(UUID actorId, UUID projectId) {
        projects.setActiveAsGeneralAdmin(require(actorId, "actorId"), require(projectId, "projectId"),
            true, clock.instant());
    }

    public void deactivate(UUID actorId, UUID projectId) {
        projects.setActiveAsGeneralAdmin(require(actorId, "actorId"), require(projectId, "projectId"),
            false, clock.instant());
    }

    private static String normalizeName(String name) {
        if (name == null) throw new IllegalArgumentException("name must not be null");
        int start = 0;
        int end = name.length();
        while (start < end) {
            int codePoint = name.codePointAt(start);
            if (!isWhitespace(codePoint)) break;
            start += Character.charCount(codePoint);
        }
        while (end > start) {
            int codePoint = name.codePointBefore(end);
            if (!isWhitespace(codePoint)) break;
            end -= Character.charCount(codePoint);
        }
        String normalized = name.substring(start, end);
        for (int index = 0; index < normalized.length();) {
            int codePoint = normalized.codePointAt(index);
            if (Character.isISOControl(codePoint)) {
                throw new IllegalArgumentException("name must not contain control characters");
            }
            index += Character.charCount(codePoint);
        }
        int characters = normalized.codePointCount(0, normalized.length());
        if (characters == 0 || characters > 160) {
            throw new IllegalArgumentException("name must contain between 1 and 160 characters");
        }
        return normalized;
    }

    private static boolean isWhitespace(int codePoint) {
        return Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint);
    }

    private static <T> T require(T value, String name) {
        if (value == null) throw new IllegalArgumentException(name + " must not be null");
        return value;
    }
}
