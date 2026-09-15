package com.chatbotq.knowledge.application.port;

import com.chatbotq.knowledge.application.model.KnowledgeCsvImportStrategy;
import com.chatbotq.knowledge.application.model.KnowledgeCsvPreview;

public interface KnowledgeCsvPreviewPort {
    byte[] template();

    KnowledgeCsvPreview preview(byte[] csvBytes, KnowledgeCsvImportStrategy strategy);
}
