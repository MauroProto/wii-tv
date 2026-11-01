package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Environment;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Handles persistent video customizations for Wii channels,
 * and manages access to media files in /storage/emulated/0/wiimedia/videos/.
 * (Audio is integrated within custom channel MP4 videos).
 */
public final class WiiChannelCustomization {

    private static final String PREFS_NAME = "wii_channel_custom_media";
    private static final String PREF_VIDEO_PREFIX = "video_";

    public static final String PRIMARY_MEDIA_ROOT = "/storage/emulated/0/wiimedia";
    public static final String VIDEOS_DIR_NAME = "videos";
    public static final String MINIATURAS_DIR_NAME = "miniaturas";

    private WiiChannelCustomization() {}

    /**
     * Returns the directory for custom video files (/storage/emulated/0/wiimedia/videos/).
     * Ensures parent folders exist.
     */
    public static File getVideosDir() {
        File dir = new File(PRIMARY_MEDIA_ROOT, VIDEOS_DIR_NAME);
        if (!dir.exists()) {
            boolean created = dir.mkdirs();
            if (!created) {
                File fallback = new File(Environment.getExternalStorageDirectory(), "wiimedia/" + VIDEOS_DIR_NAME);
                if (!fallback.exists()) fallback.mkdirs();
                return fallback;
            }
        }
        return dir;
    }

    /**
     * Returns the directory for thumbnail files (/storage/emulated/0/wiimedia/videos/miniaturas/).
     * Ensures parent folders exist.
     */
    public static File getThumbnailsDir() {
        File dir = new File(getVideosDir(), MINIATURAS_DIR_NAME);
        if (!dir.exists()) {
            boolean created = dir.mkdirs();
            if (!created) {
                File fallback = new File(Environment.getExternalStorageDirectory(), "wiimedia/" + VIDEOS_DIR_NAME + "/" + MINIATURAS_DIR_NAME);
                if (!fallback.exists()) fallback.mkdirs();
                return fallback;
            }
        }
        return dir;
    }

    /**
     * Resolves the thumbnail file for a source MP4 video:
     * e.g., /path/to/myvideo.mp4 -> /storage/emulated/0/wiimedia/videos/miniaturas/myvideo_min.webp
     */
    public static File getThumbnailFileForVideo(String videoPath) {
        if (videoPath == null || videoPath.trim().isEmpty()) return null;
        File v = new File(videoPath);
        String name = v.getName();
        int dot = name.lastIndexOf('.');
        String baseName = (dot > 0) ? name.substring(0, dot) : name;
        return new File(getThumbnailsDir(), baseName + "_min.webp");
    }

    public static File getThumbnailFile(Context context, String channelKey) {
        String videoPath = getCustomVideo(context, channelKey);
        if (videoPath == null) return null;
        return WiiThumbnailManager.getThumbnailFileForVideo(context, videoPath);
    }

    public static boolean hasThumbnail(Context context, String channelKey) {
        File f = getThumbnailFile(context, channelKey);
        return f != null && f.exists() && f.length() > 0;
    }

    /**
     * Returns all MP4 files located in /storage/emulated/0/wiimedia/videos/.
     */
    public static List<File> listVideoFiles() {
        File dir = getVideosDir();
        File[] files = dir.listFiles((d, name) -> name != null && name.toLowerCase(Locale.ROOT).endsWith(".mp4"));
        if (files == null || files.length == 0) {
            File alt = new File(Environment.getExternalStorageDirectory(), "wiimedia/" + VIDEOS_DIR_NAME);
            if (!alt.equals(dir) && alt.exists()) {
                files = alt.listFiles((d, name) -> name != null && name.toLowerCase(Locale.ROOT).endsWith(".mp4"));
            }
        }
        if (files == null) return Collections.emptyList();
        List<File> list = new ArrayList<>(Arrays.asList(files));
        Collections.sort(list, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        return list;
    }

    public static String getCustomVideo(Context context, String channelKey) {
        if (channelKey == null) return null;
        SharedPreferences sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String path = sp.getString(PREF_VIDEO_PREFIX + channelKey, null);
        if (path != null && !new File(path).exists()) {
            return null;
        }
        return path;
    }

    public static void setCustomVideo(Context context, String channelKey, String path) {
        if (channelKey == null) return;
        SharedPreferences sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        SharedPreferences.Editor ed = sp.edit();
        if (path == null || path.isEmpty()) {
            ed.remove(PREF_VIDEO_PREFIX + channelKey);
        } else {
            ed.putString(PREF_VIDEO_PREFIX + channelKey, path);
        }
        ed.apply();
    }
}
