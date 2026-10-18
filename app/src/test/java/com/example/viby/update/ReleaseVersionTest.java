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

    @Test
    public void prefixesWhitespaceAndBuildSuffixesAreHandled() {
        assertTrue(ReleaseVersion.isNewer("  V2.0.0-beta+7 ", "1.9.9"));
        assertFalse(ReleaseVersion.isNewer("v1.2.0+15", "1.2"));
    }

    @Test
    public void nullBlankNegativeAndOverflowVersionsAreRejected() {
        assertFalse(ReleaseVersion.isNewer(null, "1.0"));
        assertFalse(ReleaseVersion.isNewer("", "1.0"));
        assertFalse(ReleaseVersion.isNewer("-1", "1.0"));
        assertFalse(ReleaseVersion.isNewer("999999999999", "1.0"));
        assertFalse(ReleaseVersion.isNewer("1..2", "1.0"));
    }
}
