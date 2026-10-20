package com.example.viby.lyrics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class YoutubeMusicConfigTest {

    @Test
    public void extractsApiKeyFromCompactAndSpacedConfiguration() {
        assertEquals("example_web_client_token_12345",
                YoutubeMusicConfig.extractApiKey(
                        "{\"INNERTUBE_API_KEY\":\"example_web_client_token_12345\"}"));
        assertEquals("another-client-token_67890",
                YoutubeMusicConfig.extractApiKey(
                        "{\"INNERTUBE_API_KEY\" : \"another-client-token_67890\"}"));
    }

    @Test
    public void rejectsMissingOrUnsafeConfigurationValues() {
        assertNull(YoutubeMusicConfig.extractApiKey("{}"));
        assertNull(YoutubeMusicConfig.extractApiKey(
                "{\"INNERTUBE_API_KEY\":\"short\"}"));
        assertNull(YoutubeMusicConfig.extractApiKey(
                "{\"INNERTUBE_API_KEY\":\"unsafe value with spaces\"}"));
    }
}
