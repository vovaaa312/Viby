package com.example.viby.download;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class DownloadProgressParserTest {

    @Test
    public void usesCallbackProgress() {
        assertEquals(42, DownloadProgressParser.resolve(10, 42.4f, null));
    }

    @Test
    public void parsesYtDlpLineWhenCallbackIsZero() {
        assertEquals(67, DownloadProgressParser.resolve(
                0, 0f, "[download]  67.3% of ~ 4.32MiB at 1.00MiB/s"));
    }

    @Test
    public void neverMovesBackwardsOrOutsideBounds() {
        assertEquals(80, DownloadProgressParser.resolve(
                80, 12f, "[download]  12.0% of 4.32MiB"));
        assertEquals(100, DownloadProgressParser.resolve(0, 120f, null));
    }
}
