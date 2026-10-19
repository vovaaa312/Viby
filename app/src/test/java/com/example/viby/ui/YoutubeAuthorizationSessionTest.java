package com.example.viby.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicReference;

public class YoutubeAuthorizationSessionTest {

    @Test
    public void successReturnsTokenAndClearsPendingRequest() {
        YoutubeAuthorizationSession session = new YoutubeAuthorizationSession();
        AtomicReference<String> token = new AtomicReference<>();

        assertTrue(session.begin(token::set, error -> { }));
        session.complete("access-token", null, new Exception("cancelled"));

        assertEquals("access-token", token.get());
        assertFalse(session.isPending());
    }

    @Test
    public void concurrentRequestIsRejectedWithoutReplacingFirstCallbacks() {
        YoutubeAuthorizationSession session = new YoutubeAuthorizationSession();
        AtomicReference<String> first = new AtomicReference<>();
        AtomicReference<String> second = new AtomicReference<>();

        assertTrue(session.begin(first::set, error -> { }));
        assertFalse(session.begin(second::set, error -> { }));
        session.complete("token", null, new Exception("cancelled"));

        assertEquals("token", first.get());
        assertEquals(null, second.get());
    }

    @Test
    public void errorAndMissingTokenReachFailureCallback() {
        YoutubeAuthorizationSession session = new YoutubeAuthorizationSession();
        AtomicReference<Exception> failure = new AtomicReference<>();
        Exception apiError = new Exception("api");
        assertTrue(session.begin(token -> { }, failure::set));

        session.complete(null, apiError, new Exception("cancelled"));
        assertSame(apiError, failure.get());

        Exception cancelled = new Exception("cancelled");
        assertTrue(session.begin(token -> { }, failure::set));
        session.complete("", null, cancelled);
        assertSame(cancelled, failure.get());
    }

    @Test
    public void clearDropsCallbacksDuringActivityDestruction() {
        YoutubeAuthorizationSession session = new YoutubeAuthorizationSession();
        AtomicReference<String> token = new AtomicReference<>();
        assertTrue(session.begin(token::set, error -> { }));

        session.clear();
        session.complete("late-token", null, new Exception("cancelled"));

        assertFalse(session.isPending());
        assertEquals(null, token.get());
    }
}
