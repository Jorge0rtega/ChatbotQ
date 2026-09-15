package com.chatbotq.knowledge.application.model;

public final class KnowledgeTextNormalizer {
    private KnowledgeTextNormalizer() { }

    public static String normalize(String value, String name, int maximum, boolean optional) {
        if (value == null) {
            if (optional) return null;
            throw new IllegalArgumentException(name + " must not be null");
        }
        int start = 0;
        int end = value.length();
        while (start < end) {
            int codePoint = value.codePointAt(start);
            if (!isWhitespace(codePoint)) break;
            start += Character.charCount(codePoint);
        }
        while (end > start) {
            int codePoint = value.codePointBefore(end);
            if (!isWhitespace(codePoint)) break;
            end -= Character.charCount(codePoint);
        }
        String normalized = value.substring(start, end);
        for (int index = 0; index < normalized.length();) {
            int codePoint = normalized.codePointAt(index);
            if (Character.isISOControl(codePoint)) {
                throw new IllegalArgumentException(name + " must not contain control characters");
            }
            index += Character.charCount(codePoint);
        }
        int characters = normalized.codePointCount(0, normalized.length());
        if (characters == 0 || characters > maximum) {
            throw new IllegalArgumentException(name + " must contain between 1 and " + maximum + " characters");
        }
        return normalized;
    }

    private static boolean isWhitespace(int codePoint) {
        return Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint);
    }
}
