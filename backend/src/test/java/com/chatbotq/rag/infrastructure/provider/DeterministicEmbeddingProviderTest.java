package com.chatbotq.rag.infrastructure.provider;

import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class DeterministicEmbeddingProviderTest {
    @Test
    void producesStableFiniteVectorWithProductionDimensionsForTheSameInput() throws IOException {
        DeterministicEmbeddingProvider provider = new DeterministicEmbeddingProvider();

        float[] first = provider.embed("¿Cuál es el horario de atención?");
        float[] second = provider.embed("¿Cuál es el horario de atención?");

        assertEquals(1536, first.length);
        assertArrayEquals(first, second);
        for (float value : first) {
            assertFalse(Float.isNaN(value));
            assertFalse(Float.isInfinite(value));
        }
    }

    @Test
    void producesADifferentVectorForDifferentInput() throws IOException {
        DeterministicEmbeddingProvider provider = new DeterministicEmbeddingProvider();

        float[] first = provider.embed("¿Cuál es el horario?");
        float[] second = provider.embed("¿Dónde están ubicados?");

        assertFalse(java.util.Arrays.equals(first, second));
    }
}
