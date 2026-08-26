package com.chatbotq.projects.application.model;

import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ManagedProjectPageTest {
    @Test
    void calculatesTotalPagesWithoutOverflow() {
        ManagedProjectPage page = new ManagedProjectPage(
            Collections.<ManagedProject>emptyList(), 0, 100, Long.MAX_VALUE);

        assertEquals(92233720368547759L, page.getTotalPages());
    }
}