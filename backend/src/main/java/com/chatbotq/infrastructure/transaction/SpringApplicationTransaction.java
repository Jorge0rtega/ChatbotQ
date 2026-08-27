package com.chatbotq.infrastructure.transaction;

import com.chatbotq.identityaccess.application.port.ApplicationTransaction;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.function.Supplier;

public final class SpringApplicationTransaction implements ApplicationTransaction {
    private final TransactionTemplate transactions;

    public SpringApplicationTransaction(TransactionTemplate transactions) {
        if (transactions == null) throw new IllegalArgumentException("transactions must not be null");
        this.transactions = transactions;
    }

    @Override
    public <T> T execute(final Supplier<T> work) {
        if (work == null) throw new IllegalArgumentException("work must not be null");
        return transactions.execute(status -> work.get());
    }
}
