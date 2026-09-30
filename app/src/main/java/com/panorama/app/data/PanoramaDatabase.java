/**
 * SQLite schema. A spot is unique per session, pitch row, and sector.
 */
package com.panorama.app.data;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;

/** App database backed by the platform sqlite3 library. */
public final class PanoramaDatabase extends SQLiteOpenHelper {
    private static final String NAME = "panorama.db";
    private static final int VERSION = 2;

    private static PanoramaDatabase instance;

    public static synchronized PanoramaDatabase get(Context context) {
        if (instance == null) {
            instance = new PanoramaDatabase(context.getApplicationContext());
        }
        return instance;
    }

    private PanoramaDatabase(Context context) {
        super(context, NAME, null, VERSION);
    }

    @Override
    public void onConfigure(SQLiteDatabase db) {
        db.setForeignKeyConstraintsEnabled(true);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE sessions ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "created_at INTEGER NOT NULL,"
                + "status TEXT NOT NULL,"
                + "panorama_path TEXT,"
                + "frame_count INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("CREATE TABLE frames ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "session_id INTEGER NOT NULL,"
                + "pitch_row INTEGER NOT NULL,"
                + "sector INTEGER NOT NULL,"
                + "azimuth REAL NOT NULL,"
                + "pitch REAL NOT NULL,"
                + "file_path TEXT NOT NULL,"
                + "captured_at INTEGER NOT NULL,"
                + "UNIQUE(session_id, pitch_row, sector),"
                + "FOREIGN KEY(session_id) REFERENCES sessions(id) ON DELETE CASCADE)");
        db.execSQL("CREATE INDEX idx_frames_session ON frames(session_id)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        db.execSQL("DROP TABLE IF EXISTS frames");
        db.execSQL("DROP TABLE IF EXISTS sessions");
        onCreate(db);
    }

    public long insertSession() {
        ContentValues values = new ContentValues();
        values.put("created_at", System.currentTimeMillis());
        values.put("status", "open");
        values.put("frame_count", 0);
        return getWritableDatabase().insert("sessions", null, values);
    }

    public Session getSession(long id) {
        Cursor cursor = getReadableDatabase().query(
                "sessions",
                null,
                "id=?",
                new String[]{Long.toString(id)},
                null,
                null,
                null);
        try {
            if (!cursor.moveToFirst()) {
                return null;
            }
            return readSession(cursor);
        } finally {
            cursor.close();
        }
    }

    public List<Session> listSessions() {
        List<Session> sessions = new ArrayList<>();
        Cursor cursor = getReadableDatabase().query(
                "sessions",
                null,
                null,
                null,
                null,
                null,
                "created_at DESC");
        try {
            while (cursor.moveToNext()) {
                sessions.add(readSession(cursor));
            }
        } finally {
            cursor.close();
        }
        return sessions;
    }

    public void deleteSession(long id) {
        SQLiteDatabase db = getWritableDatabase();
        db.delete("frames", "session_id=?", new String[]{Long.toString(id)});
        db.delete("sessions", "id=?", new String[]{Long.toString(id)});
    }

    public void upsertFrame(long sessionId, int pitchRow, int sector, float azimuth, float pitch, String path) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            db.delete("frames", "session_id=? AND pitch_row=? AND sector=?",
                    new String[]{Long.toString(sessionId), Integer.toString(pitchRow), Integer.toString(sector)});
            ContentValues frame = new ContentValues();
            frame.put("session_id", sessionId);
            frame.put("pitch_row", pitchRow);
            frame.put("sector", sector);
            frame.put("azimuth", azimuth);
            frame.put("pitch", pitch);
            frame.put("file_path", path);
            frame.put("captured_at", System.currentTimeMillis());
            db.insert("frames", null, frame);

            int count = countFrames(db, sessionId);
            ContentValues session = new ContentValues();
            session.put("frame_count", count);
            session.put("status", "open");
            db.update("sessions", session, "id=?", new String[]{Long.toString(sessionId)});
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    public List<FrameRecord> listFrames(long sessionId) {
        List<FrameRecord> frames = new ArrayList<>();
        Cursor cursor = getReadableDatabase().query(
                "frames",
                new String[]{"pitch_row", "sector", "azimuth", "pitch", "file_path"},
                "session_id=?",
                new String[]{Long.toString(sessionId)},
                null,
                null,
                "pitch_row ASC, azimuth ASC");
        try {
            while (cursor.moveToNext()) {
                frames.add(new FrameRecord(
                        cursor.getInt(0),
                        cursor.getInt(1),
                        cursor.getFloat(2),
                        cursor.getFloat(3),
                        cursor.getString(4)));
            }
        } finally {
            cursor.close();
        }
        return frames;
    }

    public void markStitched(long sessionId, String panoramaPath) {
        ContentValues values = new ContentValues();
        values.put("status", "stitched");
        values.put("panorama_path", panoramaPath);
        getWritableDatabase().update("sessions", values, "id=?", new String[]{Long.toString(sessionId)});
    }

    public void markFailed(long sessionId) {
        ContentValues values = new ContentValues();
        values.put("status", "failed");
        getWritableDatabase().update("sessions", values, "id=?", new String[]{Long.toString(sessionId)});
    }

    private static int countFrames(SQLiteDatabase db, long sessionId) {
        Cursor cursor = db.rawQuery(
                "SELECT COUNT(*) FROM frames WHERE session_id=?",
                new String[]{Long.toString(sessionId)});
        try {
            cursor.moveToFirst();
            return cursor.getInt(0);
        } finally {
            cursor.close();
        }
    }

    private static Session readSession(Cursor cursor) {
        return new Session(
                cursor.getLong(cursor.getColumnIndexOrThrow("id")),
                cursor.getLong(cursor.getColumnIndexOrThrow("created_at")),
                cursor.getString(cursor.getColumnIndexOrThrow("status")),
                cursor.getString(cursor.getColumnIndexOrThrow("panorama_path")),
                cursor.getInt(cursor.getColumnIndexOrThrow("frame_count")));
    }
}
