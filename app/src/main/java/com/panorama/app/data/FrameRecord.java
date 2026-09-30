/**
 * One captured spot: pitch row, sector, azimuth, pitch, and JPEG path.
 */
package com.panorama.app.data;

public final class FrameRecord {
    public final int pitchRow;
    public final int sector;
    public final float azimuth;
    public final float pitch;
    public final String filePath;

    public FrameRecord(int pitchRow, int sector, float azimuth, float pitch, String filePath) {
        this.pitchRow = pitchRow;
        this.sector = sector;
        this.azimuth = azimuth;
        this.pitch = pitch;
        this.filePath = filePath;
    }
}
