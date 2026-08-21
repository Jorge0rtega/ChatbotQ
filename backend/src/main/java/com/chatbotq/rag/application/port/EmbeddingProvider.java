package com.chatbotq.rag.application.port;

import java.io.IOException;

public interface EmbeddingProvider {
    float[] embed(String input) throws IOException;
}
