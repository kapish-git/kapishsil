package com.kapish.bridgeshare;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/** In-memory list of files offered to the iPhone and files received from it. */
public final class FileStore {

    public static final class Item {
        public final String id;
        public final Uri uri;
        public final String name;
        public final long size;      // -1 when unknown
        public final String mime;
        public final String location; // human readable place (received files only)

        Item(String id, Uri uri, String name, long size, String mime, String location) {
            this.id = id;
            this.uri = uri;
            this.name = name;
            this.size = size;
            this.mime = mime;
            this.location = location;
        }
    }

    private static final FileStore INSTANCE = new FileStore();

    public static FileStore get() {
        return INSTANCE;
    }

    private final AtomicInteger nextId = new AtomicInteger(1);
    private final List<Item> outgoing = new CopyOnWriteArrayList<>();
    private final List<Item> incoming = new CopyOnWriteArrayList<>();
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private final Handler main = new Handler(Looper.getMainLooper());

    private FileStore() {
    }

    public List<Item> outgoing() {
        return new ArrayList<>(outgoing);
    }

    public List<Item> incoming() {
        return new ArrayList<>(incoming);
    }

    public Item findOutgoing(String id) {
        for (Item i : outgoing) {
            if (i.id.equals(id)) return i;
        }
        return null;
    }

    public void addOutgoing(Context ctx, Uri uri) {
        for (Item i : outgoing) {
            if (i.uri.equals(uri)) return; // already shared
        }
        ContentResolver cr = ctx.getContentResolver();
        String name = null;
        long size = -1;
        try (Cursor c = cr.query(uri, new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE},
                null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int n = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                int s = c.getColumnIndex(OpenableColumns.SIZE);
                if (n >= 0) name = c.getString(n);
                if (s >= 0 && !c.isNull(s)) size = c.getLong(s);
            }
        } catch (Exception ignored) {
        }
        if (name == null || name.isEmpty()) {
            name = uri.getLastPathSegment() != null ? uri.getLastPathSegment() : "file";
        }
        String mime = cr.getType(uri);
        if (mime == null) mime = MimeUtil.fromName(name);
        outgoing.add(new Item(String.valueOf(nextId.getAndIncrement()), uri, name, size, mime, null));
        changed();
    }

    public void removeOutgoing(String id) {
        outgoing.removeIf(i -> i.id.equals(id));
        changed();
    }

    public void clearOutgoing() {
        outgoing.clear();
        changed();
    }

    public void addIncoming(Uri uri, String name, long size, String mime, String location) {
        incoming.add(0, new Item(String.valueOf(nextId.getAndIncrement()), uri, name, size, mime, location));
        changed();
    }

    public void addListener(Runnable r) {
        listeners.add(r);
    }

    public void removeListener(Runnable r) {
        listeners.remove(r);
    }

    private void changed() {
        main.post(() -> {
            for (Runnable r : listeners) r.run();
        });
    }
}
