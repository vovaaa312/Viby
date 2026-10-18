package com.example.viby.playback;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNotSame;

import org.junit.Test;

public class PlaybackQueueOrderTest {

    @Test
    public void insertedItemBecomesNextWithoutChangingRemainingShuffleOrder() {
        int[] shuffledAfterInsertion = {4, 1, 5, 2, 0, 3};

        int[] result = PlaybackQueueOrder.moveInsertedRangeAfterCurrent(
                shuffledAfterInsertion, 2, 3, 1);

        assertArrayEquals(new int[]{4, 1, 5, 2, 3, 0}, result);
    }

    @Test
    public void multipleInsertedItemsKeepSelectionOrder() {
        int[] shuffledAfterInsertion = {6, 2, 4, 0, 5, 1, 3};

        int[] result = PlaybackQueueOrder.moveInsertedRangeAfterCurrent(
                shuffledAfterInsertion, 0, 3, 2);

        assertArrayEquals(new int[]{6, 2, 0, 3, 4, 5, 1}, result);
    }

    @Test
    public void insertedAtPhysicalEndStillBecomesNextInShuffleOrder() {
        int[] shuffledAfterInsertion = {2, 0, 3, 1};

        int[] result = PlaybackQueueOrder.moveInsertedRangeAfterCurrent(
                shuffledAfterInsertion, 2, 3, 1);

        assertArrayEquals(new int[]{2, 3, 0, 1}, result);
    }

    @Test
    public void missingCurrentLeavesOrderUntouched() {
        int[] shuffledAfterInsertion = {2, 0, 3, 1};

        int[] result = PlaybackQueueOrder.moveInsertedRangeAfterCurrent(
                shuffledAfterInsertion, 8, 3, 1);

        assertArrayEquals(shuffledAfterInsertion, result);
        assertNotSame(shuffledAfterInsertion, result);
    }

    @Test
    public void invalidInsertionNeverMutatesInput() {
        int[] order = {2, 0, 1};

        assertArrayEquals(order,
                PlaybackQueueOrder.moveInsertedRangeAfterCurrent(order, 2, -1, 1));
        assertArrayEquals(order,
                PlaybackQueueOrder.moveInsertedRangeAfterCurrent(order, 2, 2, 2));
        assertArrayEquals(order,
                PlaybackQueueOrder.moveInsertedRangeAfterCurrent(order, 2, 1, 0));
        assertArrayEquals(new int[0],
                PlaybackQueueOrder.moveInsertedRangeAfterCurrent(null, 0, 0, 1));
    }

    @Test
    public void currentInsideInsertedRangeLeavesOrderUntouched() {
        int[] order = {2, 0, 1, 3};

        assertArrayEquals(order,
                PlaybackQueueOrder.moveInsertedRangeAfterCurrent(order, 1, 1, 2));
    }
}
