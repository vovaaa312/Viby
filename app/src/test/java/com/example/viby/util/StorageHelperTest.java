package com.example.viby.util;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class StorageHelperTest {

    @Test
    public void sanitizeKeepsReadableTextAndReplacesFilesystemCharacters() {
        assertEquals("My playlist", StorageHelper.sanitize("  My playlist  "));
        assertEquals("a_b_c_d_e_f_g_h_i_",
                StorageHelper.sanitize("a/b\\c:d*e?f\"g<h>i|"));
        assertEquals("line_break", StorageHelper.sanitize("line\nbreak"));
    }

    @Test
    public void sanitizeUsesDefaultForBlankOrOnlyInvalidName() {
        assertEquals("Default", StorageHelper.sanitize("   "));
        assertEquals("_", StorageHelper.sanitize("?"));
    }
}
