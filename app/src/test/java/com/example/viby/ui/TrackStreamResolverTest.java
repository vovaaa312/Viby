package com.example.viby.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.example.viby.data.Track;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

public class TrackStreamResolverTest {

    @Test
    public void requiresVideoIdAndSourceUrl() {
        Track track = track("video", "https://youtube.test/watch?v=video");
        assertTrue(TrackStreamResolver.canResolve(track));

        track.videoId = null;
        assertFalse(TrackStreamResolver.canResolve(track));
        track.videoId = "video";
        track.sourceUrl = "";
        assertFalse(TrackStreamResolver.canResolve(track));
    }

    @Test
    public void onlyLatestRequestMayReachCallback() {
        QueuedRunner runner = new QueuedRunner();
        TrackStreamResolver resolver = new TrackStreamResolver(
                runner, Runnable::run, source -> "stream:" + source);
        AtomicReference<String> result = new AtomicReference<>();

        resolver.resolve(track("one", "one"), callback(result));
        resolver.resolve(track("two", "two"), callback(result));
        runner.tasks.get(0).run();
        assertNull(result.get());
        runner.tasks.get(1).run();

        assertEquals("stream:two", result.get());
    }

    @Test
    public void failureIsDeliveredForCurrentRequest() {
        Exception expected = new Exception("failed");
        AtomicReference<Exception> failure = new AtomicReference<>();
        TrackStreamResolver resolver = new TrackStreamResolver(
                new ImmediateRunner(), Runnable::run, source -> {
                    throw expected;
                });

        resolver.resolve(track("video", "source"), new TrackStreamResolver.Callback() {
            @Override
            public void onResolved(String streamUrl) {
            }

            @Override
            public void onFailure(Exception error) {
                failure.set(error);
            }
        });

        assertEquals(expected, failure.get());
    }

    @Test
    public void destroySuppressesQueuedResultAndShutsRunnerDown() {
        QueuedRunner runner = new QueuedRunner();
        AtomicReference<String> result = new AtomicReference<>();
        TrackStreamResolver resolver = new TrackStreamResolver(
                runner, Runnable::run, source -> "stream");
        resolver.resolve(track("video", "source"), callback(result));

        resolver.destroy();
        runner.tasks.get(0).run();

        assertNull(result.get());
        assertTrue(runner.shutdown);
    }

    private static TrackStreamResolver.Callback callback(
            AtomicReference<String> result) {
        return new TrackStreamResolver.Callback() {
            @Override
            public void onResolved(String streamUrl) {
                result.set(streamUrl);
            }

            @Override
            public void onFailure(Exception error) {
            }
        };
    }

    private static Track track(String videoId, String sourceUrl) {
        Track track = new Track();
        track.videoId = videoId;
        track.sourceUrl = sourceUrl;
        return track;
    }

    private static class QueuedRunner implements TrackStreamResolver.Runner {
        final List<Runnable> tasks = new ArrayList<>();
        boolean shutdown;

        @Override
        public void execute(Runnable task) {
            tasks.add(task);
        }

        @Override
        public void shutdown() {
            shutdown = true;
        }
    }

    private static final class ImmediateRunner extends QueuedRunner {
        @Override
        public void execute(Runnable task) {
            task.run();
        }
    }
}
