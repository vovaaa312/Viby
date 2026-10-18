package com.example.viby.ui.widget;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class PlaylistScrollMathTest {

    @Test
    public void proportionalThumbRepresentsVisiblePartOfPlaylist() {
        assertEquals(100f,
                PlaylistScrollMath.thumbHeight(1000, 10, 100, 52f), 0.001f);
        assertEquals(52f,
                PlaylistScrollMath.thumbHeight(1000, 2, 100, 52f), 0.001f);
        assertEquals(0f,
                PlaylistScrollMath.thumbHeight(1000, 10, 10, 52f), 0.001f);
    }

    @Test
    public void thumbPositionClampsAtBothEnds() {
        assertEquals(0f, PlaylistScrollMath.thumbTop(1000, 100, -5, 90), 0.001f);
        assertEquals(450f, PlaylistScrollMath.thumbTop(1000, 100, 45, 90), 0.001f);
        assertEquals(900f, PlaylistScrollMath.thumbTop(1000, 100, 500, 90), 0.001f);
    }

    @Test
    public void draggingThumbMapsDirectlyToPlaylistPosition() {
        assertEquals(0,
                PlaylistScrollMath.targetPosition(-100, 50, 1000, 100, 90));
        assertEquals(45,
                PlaylistScrollMath.targetPosition(500, 50, 1000, 100, 90));
        assertEquals(90,
                PlaylistScrollMath.targetPosition(2000, 50, 1000, 100, 90));
    }

    @Test
    public void grabbingThumbAwayFromItsTopDoesNotJump() {
        // A thumb starting at y=360, grabbed 40 px below its top, stays at 40%.
        assertEquals(36,
                PlaylistScrollMath.targetPosition(400, 40, 1000, 100, 90));
    }
}
