/**
 * Angle math for the capture grid: 12 azimuth columns and 3 pitch rows.
 */
package com.panorama.app;

public final class CaptureGeometry {
    public static final int SECTOR_COUNT = 12;
    public static final float SECTOR_DEGREES = 360f / SECTOR_COUNT;
    /** Top row looks up, middle is the horizon, bottom looks down. */
    public static final int PITCH_ROWS = 3;
    public static final float[] PITCH_TARGETS = {55f, 0f, -55f};
    public static final float PITCH_TOLERANCE = 18f;
    public static final float MAX_ROLL = 40f;
    public static final int SPOT_COUNT = PITCH_ROWS * SECTOR_COUNT;
    /** Approximate horizontal field of view while the phone is held in portrait. */
    public static final float HORIZONTAL_FOV = 50f;

    private CaptureGeometry() {
    }

    public static float wrap(float degrees) {
        float value = degrees % 360f;
        if (value < 0f) {
            value += 360f;
        }
        return value;
    }

    /** Shortest signed turn from `from` to `to`. Positive is clockwise (turn right). */
    public static float signedDelta(float from, float to) {
        return wrap(to - from + 180f) - 180f;
    }

    public static int sectorOf(float azimuth) {
        int sector = (int) (wrap(azimuth) / SECTOR_DEGREES);
        if (sector >= SECTOR_COUNT) {
            return SECTOR_COUNT - 1;
        }
        if (sector < 0) {
            return 0;
        }
        return sector;
    }

    public static float sectorCenter(int sector) {
        return (sector + 0.5f) * SECTOR_DEGREES;
    }

    public static int pitchRowOf(float pitch) {
        int best = 0;
        float bestDistance = Float.MAX_VALUE;
        for (int row = 0; row < PITCH_ROWS; row++) {
            float distance = Math.abs(pitch - PITCH_TARGETS[row]);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = row;
            }
        }
        return best;
    }

    public static boolean pitchMatches(float pitch, int row) {
        return Math.abs(pitch - PITCH_TARGETS[row]) <= PITCH_TOLERANCE;
    }

    /** @return {pitch row, sector}, or null when every spot is filled. */
    public static int[] nearestEmpty(float azimuth, float pitch, boolean[][] filled) {
        int bestRow = -1;
        int bestSector = -1;
        float bestScore = Float.MAX_VALUE;
        for (int row = 0; row < PITCH_ROWS; row++) {
            for (int sector = 0; sector < SECTOR_COUNT; sector++) {
                if (filled[row][sector]) {
                    continue;
                }
                float score = Math.abs(signedDelta(azimuth, sectorCenter(sector)))
                        + Math.abs(pitch - PITCH_TARGETS[row]) * 1.4f;
                if (score < bestScore) {
                    bestScore = score;
                    bestRow = row;
                    bestSector = sector;
                }
            }
        }
        if (bestRow < 0) {
            return null;
        }
        return new int[]{bestRow, bestSector};
    }

    public static int nearestEmpty(float azimuth, boolean[] filled) {
        int best = -1;
        float bestDistance = Float.MAX_VALUE;
        for (int sector = 0; sector < SECTOR_COUNT; sector++) {
            if (filled[sector]) {
                continue;
            }
            float distance = Math.abs(signedDelta(azimuth, sectorCenter(sector)));
            if (distance < bestDistance) {
                bestDistance = distance;
                best = sector;
            }
        }
        return best;
    }
}
