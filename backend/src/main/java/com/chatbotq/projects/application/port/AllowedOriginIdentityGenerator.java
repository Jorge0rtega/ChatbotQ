package com.chatbotq.projects.application.port;

import java.util.UUID;

public interface AllowedOriginIdentityGenerator {
    UUID newAllowedOriginId();
}
