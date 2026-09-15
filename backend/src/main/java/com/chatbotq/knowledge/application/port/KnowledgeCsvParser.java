package com.chatbotq.knowledge.application.port;

import com.chatbotq.knowledge.application.model.KnowledgeCsvParsedFile;

public interface KnowledgeCsvParser {
    KnowledgeCsvParsedFile parse(byte[] csvBytes);
}
