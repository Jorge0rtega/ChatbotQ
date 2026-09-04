package com.chatbotq.knowledge.application.model;

import java.util.List;

public final class ManagedKnowledgeEntryPage {
    private final List<ManagedKnowledgeEntry> items;
    private final int page;
    private final int size;
    private final long totalElements;
    private final long totalPages;

    public ManagedKnowledgeEntryPage(List<ManagedKnowledgeEntry> items, int page, int size, long totalElements) {
        this.items = items;
        this.page = page;
        this.size = size;
        this.totalElements = totalElements;
        this.totalPages = totalElements == 0 ? 0L : ((totalElements - 1L) / size) + 1L;
    }

    public List<ManagedKnowledgeEntry> getItems() { return items; }
    public int getPage() { return page; }
    public int getSize() { return size; }
    public long getTotalElements() { return totalElements; }
    public long getTotalPages() { return totalPages; }
}
