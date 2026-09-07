package com.example.viby.playback;

/** Pure queue-order transformations shared by playback code and unit tests. */
final class PlaybackQueueOrder {

    private PlaybackQueueOrder() {
    }

    /**
     * Moves a newly inserted contiguous range directly after the current item in playback order.
     * All other items retain their relative shuffled order, while inserted items retain their
     * selection order.
     */
    static int[] moveInsertedRangeAfterCurrent(int[] playbackOrder, int currentIndex,
                                                int rangeStart, int rangeCount) {
        if (playbackOrder == null || rangeCount <= 0 || rangeStart < 0
                || rangeStart + rangeCount > playbackOrder.length) {
            return playbackOrder == null ? new int[0] : playbackOrder.clone();
        }

        boolean currentFound = false;
        for (int index : playbackOrder) {
            if (index == currentIndex) {
                currentFound = true;
                break;
            }
        }
        if (!currentFound || currentIndex >= rangeStart
                && currentIndex < rangeStart + rangeCount) {
            return playbackOrder.clone();
        }

        int[] result = new int[playbackOrder.length];
        int output = 0;
        for (int index : playbackOrder) {
            if (index >= rangeStart && index < rangeStart + rangeCount) {
                continue;
            }
            result[output++] = index;
            if (index == currentIndex) {
                for (int inserted = rangeStart;
                     inserted < rangeStart + rangeCount; inserted++) {
                    result[output++] = inserted;
                }
            }
        }
        return output == result.length ? result : playbackOrder.clone();
    }
}
