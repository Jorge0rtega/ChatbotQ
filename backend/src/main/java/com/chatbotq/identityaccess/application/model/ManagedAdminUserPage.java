package com.chatbotq.identityaccess.application.model;

import java.util.Collections;
import java.util.List;

public final class ManagedAdminUserPage {
    private final List<ManagedAdminUser> items;
    private final int page;
    private final int size;
    private final long totalElements;
    private final long totalPages;

    public ManagedAdminUserPage(List<ManagedAdminUser> items, int page, int size, long totalElements) {
        this.items = Collections.unmodifiableList(items);
        this.page = page;
        this.size = size;
        this.totalElements = totalElements;
        this.totalPages = totalElements == 0 ? 0 : ((totalElements - 1) / size) + 1;
    }

    public List<ManagedAdminUser> getItems() { return items; }
    public int getPage() { return page; }
    public int getSize() { return size; }
    public long getTotalElements() { return totalElements; }
    public long getTotalPages() { return totalPages; }
}
