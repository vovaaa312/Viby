package com.example.viby.ui;

import androidx.annotation.Nullable;

import java.util.function.Consumer;

/** Holds one in-flight YouTube OAuth request independently from Android UI plumbing. */
final class YoutubeAuthorizationSession {

    private Consumer<String> success;
    private Consumer<Exception> failure;

    boolean begin(Consumer<String> onSuccess, Consumer<Exception> onFailure) {
        if (success != null) {
            return false;
        }
        success = onSuccess;
        failure = onFailure;
        return true;
    }

    void complete(@Nullable String accessToken, @Nullable Exception error,
                  Exception cancellationError) {
        Consumer<String> successCallback = success;
        Consumer<Exception> failureCallback = failure;
        clear();
        if (error == null && accessToken != null && !accessToken.isEmpty()) {
            if (successCallback != null) {
                successCallback.accept(accessToken);
            }
        } else if (failureCallback != null) {
            failureCallback.accept(error != null ? error : cancellationError);
        }
    }

    void clear() {
        success = null;
        failure = null;
    }

    boolean isPending() {
        return success != null;
    }
}
