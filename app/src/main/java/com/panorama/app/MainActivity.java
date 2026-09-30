/**
 * Session list. Opens the viewer when a sphere picture exists, otherwise the camera.
 */
package com.panorama.app;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.panorama.app.data.CaptureStorage;
import com.panorama.app.data.PanoramaDatabase;
import com.panorama.app.data.Session;
import com.panorama.app.ui.SessionAdapter;

import java.util.List;

public class MainActivity extends AppCompatActivity {
    private PanoramaDatabase database;
    private SessionAdapter adapter;
    private TextView emptyView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        database = PanoramaDatabase.get(this);
        emptyView = findViewById(R.id.empty);
        RecyclerView list = findViewById(R.id.sessions);
        list.setLayoutManager(new LinearLayoutManager(this));
        adapter = new SessionAdapter(new SessionAdapter.Listener() {
            @Override
            public void onOpen(Session session) {
                if (session.hasPanorama()) {
                    Intent intent = new Intent(MainActivity.this, ViewerActivity.class);
                    intent.putExtra(ViewerActivity.EXTRA_SESSION_ID, session.id);
                    startActivity(intent);
                } else {
                    openCapture(session.id);
                }
            }

            @Override
            public void onAddShots(Session session) {
                openCapture(session.id);
            }

            @Override
            public void onDelete(Session session) {
                confirmDelete(session);
            }
        });
        list.setAdapter(adapter);
        findViewById(R.id.new_capture).setOnClickListener(v -> {
            long id = database.insertSession();
            if (id > 0) {
                openCapture(id);
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        List<Session> sessions = database.listSessions();
        adapter.submit(sessions, this::thumbFor);
        emptyView.setVisibility(sessions.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void openCapture(long sessionId) {
        Intent intent = new Intent(this, CaptureActivity.class);
        intent.putExtra(CaptureActivity.EXTRA_SESSION_ID, sessionId);
        startActivity(intent);
    }

    private void confirmDelete(Session session) {
        new AlertDialog.Builder(this)
                .setMessage(R.string.delete_confirm)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.delete, (dialog, which) -> {
                    database.deleteSession(session.id);
                    CaptureStorage.deleteSessionFiles(this, session.id);
                    onResume();
                })
                .show();
    }

    private Bitmap thumbFor(Session session) {
        String path = session.hasPanorama() ? session.panoramaPath : null;
        if (path == null) {
            return null;
        }
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, bounds);
        int sample = 1;
        while (bounds.outWidth / sample > 320 && sample < 32) {
            sample *= 2;
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sample;
        return BitmapFactory.decodeFile(path, options);
    }
}
