/**
 * One capture session row from the sessions table.
 */
package com.panorama.app.data;

public final class Session {
    public final long id;
    public final long createdAt;
    public final String status;
    public final String panoramaPath;
    public final int frameCount;

    public Session(long id, long createdAt, String status, String panoramaPath, int frameCount) {
        this.id = id;
        this.createdAt = createdAt;
        this.status = status;
        this.panoramaPath = panoramaPath;
        this.frameCount = frameCount;
    }

    public boolean hasPanorama() {
        return panoramaPath != null && panoramaPath.length() > 0;
    }
}
