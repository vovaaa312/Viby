package com.example.viby.update;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ReleaseVersionTest {

    @Test
    public void newerPatchIsDetected() {
        assertTrue(ReleaseVersion.isNewer("v1.2.1", "1.2.0"));
    }

    @Test
    public void equalVersionsAreNotNewer() {
        assertFalse(ReleaseVersion.isNewer("v1.2.0", "1.2"));
    }

    @Test
    public void olderVersionIsNotNewer() {
        assertFalse(ReleaseVersion.isNewer("v1.1.9", "1.2.0"));
    }

    @Test
    public void malformedVersionIsRejected() {
        assertFalse(ReleaseVersion.isNewer("latest", "1.2.0"));
    }
}
