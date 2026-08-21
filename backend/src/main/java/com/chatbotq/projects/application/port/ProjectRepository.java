package com.chatbotq.projects.application.port;

import com.chatbotq.projects.domain.Project;

public interface ProjectRepository {
    Project save(Project project);
}
