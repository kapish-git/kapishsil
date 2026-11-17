package com.kapish.bridgeshare;

import android.webkit.MimeTypeMap;

import java.util.Locale;

final class MimeUtil {
    private MimeUtil() {
    }

    static String fromName(String name) {
        int dot = name.lastIndexOf('.');
        if (dot >= 0 && dot < name.length() - 1) {
            String ext = name.substring(dot + 1).toLowerCase(Locale.ROOT);
            if (ext.equals("heic")) return "image/heic";
            if (ext.equals("heif")) return "image/heif";
            String m = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
            if (m != null) return m;
        }
        return "application/octet-stream";
    }
}
