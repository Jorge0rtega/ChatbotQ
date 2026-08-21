package com.chatbotq.rag.application.port;

import java.io.IOException;

public interface ChatCompletionProvider {

    StreamResult stream(String systemPrompt, String userPrompt, DeltaHandler handler) throws IOException;

    interface DeltaHandler {
        boolean onDelta(String delta);
    }

    enum StreamResult {
        COMPLETED,
        CANCELLED
    }
}
