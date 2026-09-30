/**
 * Rows on the home list: date, spot count, and a thumbnail of the sphere.
 */
package com.panorama.app.ui;

import android.graphics.Bitmap;
import android.text.format.DateFormat;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.panorama.app.CaptureGeometry;
import com.panorama.app.R;
import com.panorama.app.data.Session;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

public class SessionAdapter extends RecyclerView.Adapter<SessionAdapter.Holder> {
    public interface Listener {
        void onOpen(Session session);

        void onAddShots(Session session);

        void onDelete(Session session);
    }

    public interface ThumbLoader {
        Bitmap load(Session session);
    }

    private final Listener listener;
    private final List<Session> sessions = new ArrayList<>();
    private ThumbLoader thumbLoader = session -> null;

    public SessionAdapter(Listener listener) {
        this.listener = listener;
    }

    public void submit(List<Session> next, ThumbLoader loader) {
        sessions.clear();
        sessions.addAll(next);
        thumbLoader = loader;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_session, parent, false);
        return new Holder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        Session session = sessions.get(position);
        String when = DateFormat.getMediumDateFormat(holder.itemView.getContext())
                .format(new Date(session.createdAt))
                + " "
                + DateFormat.getTimeFormat(holder.itemView.getContext())
                        .format(new Date(session.createdAt));
        holder.title.setText(when);
        holder.subtitle.setText(subtitle(holder, session));
        holder.addShots.setVisibility(session.hasPanorama() ? View.VISIBLE : View.GONE);
        Bitmap thumb = thumbLoader.load(session);
        if (thumb != null) {
            holder.thumb.setImageBitmap(thumb);
        } else {
            holder.thumb.setImageDrawable(null);
        }
        holder.itemView.setOnClickListener(v -> listener.onOpen(session));
        holder.addShots.setOnClickListener(v -> listener.onAddShots(session));
        holder.delete.setOnClickListener(v -> listener.onDelete(session));
    }

    private static String subtitle(Holder holder, Session session) {
        if (session.hasPanorama()) {
            return holder.itemView.getContext().getString(R.string.ready_to_view);
        }
        if ("failed".equals(session.status)) {
            return holder.itemView.getContext().getString(
                    R.string.stitch_failed_row, session.frameCount, CaptureGeometry.SPOT_COUNT);
        }
        return holder.itemView.getContext().getString(
                R.string.spots_filled_row, session.frameCount, CaptureGeometry.SPOT_COUNT);
    }

    @Override
    public int getItemCount() {
        return sessions.size();
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final ImageView thumb;
        final TextView title;
        final TextView subtitle;
        final TextView addShots;
        final TextView delete;

        Holder(View itemView) {
            super(itemView);
            thumb = itemView.findViewById(R.id.thumb);
            title = itemView.findViewById(R.id.title);
            subtitle = itemView.findViewById(R.id.subtitle);
            addShots = itemView.findViewById(R.id.add_shots);
            delete = itemView.findViewById(R.id.delete);
        }
    }
}
