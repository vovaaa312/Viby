package com.example.viby.util;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.ContextWrapper;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@RunWith(AndroidJUnit4.class)
public class YtCookiesInstrumentedTest {

    private File directory;
    private Context context;

    @Before
    public void setUp() {
        Context base = InstrumentationRegistry.getInstrumentation().getTargetContext();
        directory = new File(base.getCacheDir(), "yt-cookie-test");
        //noinspection ResultOfMethodCallIgnored
        directory.mkdirs();
        context = new ContextWrapper(base) {
            @Override public File getFilesDir() {
                return directory;
            }
        };
        //noinspection ResultOfMethodCallIgnored
        YtCookies.file(context).delete();
    }

    @After
    public void tearDown() {
        //noinspection ResultOfMethodCallIgnored
        YtCookies.file(context).delete();
        //noinspection ResultOfMethodCallIgnored
        directory.delete();
    }

    @Test
    public void missingOrOrdinaryCookieFileIsNotLoggedIn() throws Exception {
        assertFalse(YtCookies.isLoggedIn(context));
        write("# Netscape HTTP Cookie File\n.youtube.com\tTRUE\t/\tTRUE\t1\tPREF\tf6=400\n");
        assertFalse(YtCookies.isLoggedIn(context));
    }

    @Test
    public void sapisidCookieMarksSessionAsLoggedIn() throws Exception {
        write("# Netscape HTTP Cookie File\n"
                + ".youtube.com\tTRUE\t/\tTRUE\t1\tSAPISID\tsecret\n");
        assertTrue(YtCookies.isLoggedIn(context));
    }

    @Test
    public void clearDeletesExportedSessionBeforeCompleting() throws Exception {
        write("# Netscape HTTP Cookie File\n"
                + ".youtube.com\tTRUE\t/\tTRUE\t1\tSAPISID\tsecret\n");
        CountDownLatch cleared = new CountDownLatch(1);

        YtCookies.clear(context, cleared::countDown);

        assertTrue(cleared.await(5, TimeUnit.SECONDS));
        assertFalse(YtCookies.file(context).exists());
        assertFalse(YtCookies.isLoggedIn(context));
    }

    private void write(String value) throws Exception {
        try (FileOutputStream output = new FileOutputStream(YtCookies.file(context))) {
            output.write(value.getBytes(StandardCharsets.UTF_8));
        }
    }
}
