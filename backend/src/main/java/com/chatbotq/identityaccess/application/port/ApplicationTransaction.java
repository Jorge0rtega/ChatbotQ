package com.chatbotq.identityaccess.application.port;

import java.util.function.Supplier;

/** Runs one application operation in a single database transaction. */
public interface ApplicationTransaction {
    <T> T execute(Supplier<T> work);
}
