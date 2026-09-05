package com.chatbotq.rag.infrastructure.configuration;

/** Validated, conservative limits for the future manual embedding trigger. */
public final class EmbeddingProcessingLimits {
    private final int maxEntriesPerManualRun;
    private final int maxInputTokensPerEntry;
    private final int maxEntriesPerDay;
    private final int maxInputTokensPerDay;
    private final int monthlyAlertUsd;
    private final int monthlyHardLimitUsd;

    public EmbeddingProcessingLimits(int maxEntriesPerManualRun, int maxInputTokensPerEntry,
                                     int maxEntriesPerDay, int maxInputTokensPerDay,
                                     int monthlyAlertUsd, int monthlyHardLimitUsd) {
        requirePositive(maxEntriesPerManualRun, "maxEntriesPerManualRun");
        requirePositive(maxInputTokensPerEntry, "maxInputTokensPerEntry");
        requirePositive(maxEntriesPerDay, "maxEntriesPerDay");
        requirePositive(maxInputTokensPerDay, "maxInputTokensPerDay");
        requirePositive(monthlyAlertUsd, "monthlyAlertUsd");
        if (monthlyHardLimitUsd < monthlyAlertUsd) {
            throw new IllegalArgumentException("monthlyHardLimitUsd must not be smaller than monthlyAlertUsd");
        }
        this.maxEntriesPerManualRun = maxEntriesPerManualRun;
        this.maxInputTokensPerEntry = maxInputTokensPerEntry;
        this.maxEntriesPerDay = maxEntriesPerDay;
        this.maxInputTokensPerDay = maxInputTokensPerDay;
        this.monthlyAlertUsd = monthlyAlertUsd;
        this.monthlyHardLimitUsd = monthlyHardLimitUsd;
    }

    public int getMaxEntriesPerManualRun() { return maxEntriesPerManualRun; }
    public int getMaxInputTokensPerEntry() { return maxInputTokensPerEntry; }
    public int getMaxEntriesPerDay() { return maxEntriesPerDay; }
    public int getMaxInputTokensPerDay() { return maxInputTokensPerDay; }
    public int getMonthlyAlertUsd() { return monthlyAlertUsd; }
    public int getMonthlyHardLimitUsd() { return monthlyHardLimitUsd; }

    private static void requirePositive(int value, String name) {
        if (value < 1) throw new IllegalArgumentException(name + " must be positive");
    }
}
