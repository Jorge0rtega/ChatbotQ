package com.chatbotq.projects.application.model;

import java.util.Collections;
import java.util.List;

public final class ManagedProjectPage {
    private final List<ManagedProject> items;
    private final int page;
    private final int size;
    private final long totalElements;
    private final long totalPages;

    public ManagedProjectPage(List<ManagedProject> items, int page, int size, long totalElements) {
        this.items = Collections.unmodifiableList(items);
        this.page = page;
        this.size = size;
        this.totalElements = totalElements;
        this.totalPages = totalElements == 0 ? 0L
            : totalElements / size + (totalElements % size == 0 ? 0L : 1L);
    }

    public List<ManagedProject> getItems() { return items; }
    public int getPage() { return page; }
    public int getSize() { return size; }
    public long getTotalElements() { return totalElements; }
    public long getTotalPages() { return totalPages; }
}
