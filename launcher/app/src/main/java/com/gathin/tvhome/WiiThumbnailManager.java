package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.ImageDecoder;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Animatable;
import android.graphics.drawable.AnimatedImageDrawable;
import android.graphics.drawable.Drawable;
import android.media.Image;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import java.nio.ByteBuffer;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;
import android.util.LruCache;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Manages extraction, encoding, caching, and playback of optimized Animated WebP thumbnails (240x135 @ 15 FPS)
 * for Wii channels in /storage/emulated/0/wiimedia/videos/miniaturas/ (with internal app-storage fallback).
 */
public final class WiiThumbnailManager {

    private static final String TAG = "WiiThumbnailManager";

    public static final int THUMB_WIDTH = 480;
    public static final int THUMB_HEIGHT = 270;
    public static final int THUMB_FPS = 15;
    public static final long MAX_DURATION_MS = 60000L; // 60 seconds max safety barrier for full duration
    public static final int THUMB_QUALITY = 75;

    public interface ProgressListener {
        void onProgress(float fraction);
    }

    // Cache for static first-frame bitmaps (up to 30 channels, ~500 KB each = ~15 MB max in memory)
    private static final LruCache<String, Bitmap> sFirstFrameCache = new LruCache<String, Bitmap>(30);

    private WiiThumbnailManager() {}

    /**
     * Primary directory: /storage/emulated/0/wiimedia/videos/miniaturas/
     */
    public static File getThumbnailsDir() {
        return WiiChannelCustomization.getThumbnailsDir();
    }

    /**
     * Fallback directory inside app's private files.
     */
    public static File getFallbackThumbnailsDir(Context context) {
        if (context == null) return null;
        File dir = new File(context.getFilesDir(), "miniaturas");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    /**
     * Resolves the thumbnail file for a source MP4 video:
     * Checks primary location first, then internal fallback.
     */
    public static File getThumbnailFileForVideo(Context context, String videoPath) {
        if (videoPath == null || videoPath.trim().isEmpty()) return null;
        File v = new File(videoPath);
        String name = v.getName();
        int dot = name.lastIndexOf('.');
        String baseName = (dot > 0) ? name.substring(0, dot) : name;
        String thumbName = baseName + "_min.webp";

        // 1. Check primary
        File primaryDir = getThumbnailsDir();
        File primaryFile = new File(primaryDir, thumbName);
        if (primaryFile.exists() && primaryFile.length() > 0) {
            return primaryFile;
        }

        // 2. Check fallback
        if (context != null) {
            File fallbackDir = getFallbackThumbnailsDir(context);
            if (fallbackDir != null) {
                File fallbackFile = new File(fallbackDir, thumbName);
                if (fallbackFile.exists() && fallbackFile.length() > 0) {
                    return fallbackFile;
                }
            }
        }

        // Default to primary if not yet created
        return primaryFile;
    }

    public static File getThumbnailFileForVideo(String videoPath) {
        return getThumbnailFileForVideo(null, videoPath);
    }

    public static File getThumbnailFileForChannel(Context context, String channelKey) {
        String videoPath = WiiChannelCustomization.getCustomVideo(context, channelKey);
        if (videoPath == null) return null;
        return getThumbnailFileForVideo(context, videoPath);
    }

    public static boolean hasThumbnailForVideo(Context context, String videoPath) {
        if (videoPath == null || videoPath.isEmpty()) return false;
        File f = getThumbnailFileForVideo(context, videoPath);
        return f != null && f.exists() && f.length() > 0;
    }

    public static boolean hasThumbnailForVideo(String videoPath) {
        return hasThumbnailForVideo(null, videoPath);
    }

    public static boolean hasThumbnail(Context context, String channelKey) {
        File f = getThumbnailFileForChannel(context, channelKey);
        return f != null && f.exists() && f.length() > 0;
    }

    /**
     * Generates an optimized Animated WebP thumbnail from a source video file.
     * Features:
     * - Resolution: 480x270 pixels
     * - Frame rate: 15 FPS
     * - Duration: 2.4 seconds
     * - Infinite loop
     * - Silent
     */
    public static void generateThumbnail(Context context, String videoPath, File outFile, ProgressListener listener) throws Exception {
        if (outFile != null && outFile.exists() && outFile.length() > 0) {
            Log.i(TAG, "Thumbnail already exists, skipping generation: " + outFile.getAbsolutePath());
            return;
        }

        File targetFile = outFile;
        if (targetFile == null) {
            targetFile = getThumbnailFileForVideo(context, videoPath);
        }
        if (targetFile != null && targetFile.exists() && targetFile.length() > 0) {
            Log.i(TAG, "Target thumbnail already exists: " + targetFile.getAbsolutePath());
            return;
        }

        Log.i(TAG, "Generating WebP thumbnail (480x270) for: " + videoPath + " -> " + (targetFile != null ? targetFile.getAbsolutePath() : "null"));

        // Option A: Fast hardware-accelerated linear single-pass decoding with MediaExtractor + MediaCodec
        try {
            boolean success = generateThumbnailWithMediaCodec(context, videoPath, targetFile, listener);
            if (success && targetFile != null && targetFile.exists() && targetFile.length() > 0) {
                Log.i(TAG, "Fast MediaCodec linear generation succeeded for " + videoPath);
                return;
            }
        } catch (Throwable t) {
            Log.w(TAG, "Fast MediaCodec generation failed, falling back to MediaMetadataRetriever: " + t.getMessage(), t);
        }

        // Fallback: MediaMetadataRetriever
        generateThumbnailWithRetriever(context, videoPath, targetFile, listener);
    }

    private static boolean generateThumbnailWithMediaCodec(Context context, String videoPath, File targetFile, ProgressListener listener) {
        MediaExtractor extractor = null;
        MediaCodec codec = null;
        Bitmap firstFrameCopy = null;
        try {
            extractor = new MediaExtractor();
            extractor.setDataSource(videoPath);

            int videoTrack = -1;
            MediaFormat format = null;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat mf = extractor.getTrackFormat(i);
                String mime = mf.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("video/")) {
                    videoTrack = i;
                    format = mf;
                    break;
                }
            }

            if (videoTrack < 0 || format == null) {
                return false;
            }

            extractor.selectTrack(videoTrack);
            String mime = format.getString(MediaFormat.KEY_MIME);

            long durationUs = 0;
            if (format.containsKey(MediaFormat.KEY_DURATION)) {
                durationUs = format.getLong(MediaFormat.KEY_DURATION);
            }
            if (durationUs <= 0) {
                durationUs = 5000000L;
            }
            long maxDurationUs = MAX_DURATION_MS * 1000L;
            if (durationUs > maxDurationUs) {
                durationUs = maxDurationUs;
            }

            long sampleIntervalUs = 1000000L / THUMB_FPS;
            long nextSampleTimeUs = 0;

            codec = MediaCodec.createDecoderByType(mime);
            try {
                format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);
            } catch (Throwable ignored) {}
            codec.configure(format, null, null, 0);
            codec.start();

            AnimatedWebpEncoder encoder = new AnimatedWebpEncoder(THUMB_WIDTH, THUMB_HEIGHT, THUMB_FPS);
            Bitmap reusableBitmap = Bitmap.createBitmap(THUMB_WIDTH, THUMB_HEIGHT, Bitmap.Config.ARGB_8888);
            int[] pixels = new int[THUMB_WIDTH * THUMB_HEIGHT];

            int[] srcX = new int[THUMB_WIDTH];
            int[] srcY = new int[THUMB_HEIGHT];
            int[] uvXOffset = new int[THUMB_WIDTH];
            int[] vXOffset = new int[THUMB_WIDTH];
            int[] uvYOffset = new int[THUMB_HEIGHT];
            int[] vYOffset = new int[THUMB_HEIGHT];
            boolean precalcDone = false;

            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean sawInputEOS = false;
            boolean sawOutputEOS = false;
            int framesCaptured = 0;
            int noOutputCount = 0;

            while (!sawOutputEOS && (maxDurationUs <= 0 || nextSampleTimeUs <= durationUs)) {
                if (!sawInputEOS) {
                    int inputIndex = codec.dequeueInputBuffer(5000);
                    if (inputIndex >= 0) {
                        ByteBuffer inputBuffer = codec.getInputBuffer(inputIndex);
                        if (inputBuffer != null) {
                            int sampleSize = extractor.readSampleData(inputBuffer, 0);
                            if (sampleSize < 0) {
                                codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                                sawInputEOS = true;
                            } else {
                                long sampleTimeUs = extractor.getSampleTime();
                                codec.queueInputBuffer(inputIndex, 0, sampleSize, sampleTimeUs, 0);
                                extractor.advance();
                            }
                        }
                    }
                }

                int outputIndex = codec.dequeueOutputBuffer(info, 5000);
                if (outputIndex >= 0) {
                    noOutputCount = 0;
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        sawOutputEOS = true;
                    }
                    if (info.size > 0) {
                        long pts = info.presentationTimeUs;
                        if (pts >= nextSampleTimeUs || framesCaptured == 0) {
                            Image image = codec.getOutputImage(outputIndex);
                            if (image != null) {
                                try {
                                    if (!precalcDone) {
                                        initPrecalc(image.getWidth(), image.getHeight(), THUMB_WIDTH, THUMB_HEIGHT,
                                                image.getPlanes()[1].getPixelStride(), image.getPlanes()[2].getPixelStride(),
                                                image.getPlanes()[1].getRowStride(), image.getPlanes()[2].getRowStride(),
                                                srcX, srcY, uvXOffset, vXOffset, uvYOffset, vYOffset);
                                        precalcDone = true;
                                    }
                                    scaleYuvToRgb(image, THUMB_WIDTH, THUMB_HEIGHT, srcX, srcY, uvXOffset, vXOffset, uvYOffset, vYOffset, pixels);
                                    reusableBitmap.setPixels(pixels, 0, THUMB_WIDTH, 0, 0, THUMB_WIDTH, THUMB_HEIGHT);
                                    if (framesCaptured == 0) {
                                        firstFrameCopy = reusableBitmap.copy(Bitmap.Config.ARGB_8888, false);
                                    }
                                    encoder.addFrame(reusableBitmap, THUMB_QUALITY);
                                    framesCaptured++;
                                    nextSampleTimeUs = pts + sampleIntervalUs;
                                    if (listener != null && durationUs > 0) {
                                        listener.onProgress(Math.min(1f, (float) pts / (float) durationUs));
                                    }
                                } finally {
                                    image.close();
                                }
                            }
                        }
                    }
                    codec.releaseOutputBuffer(outputIndex, false);
                } else if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    precalcDone = false;
                } else if (outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    if (sawInputEOS) {
                        noOutputCount++;
                        if (noOutputCount > 30) {
                            break;
                        }
                    }
                }
            }

            if (framesCaptured < 2) {
                return false;
            }

            boolean written = saveThumbnail(context, videoPath, targetFile, encoder);
            if (written && firstFrameCopy != null && targetFile != null) {
                sFirstFrameCache.put(targetFile.getAbsolutePath(), firstFrameCopy);
            }
            return written;
        } catch (Throwable t) {
            Log.w(TAG, "MediaCodec linear decode failed: " + t.getMessage(), t);
            return false;
        } finally {
            if (codec != null) {
                try { codec.stop(); } catch (Throwable ignored) {}
                try { codec.release(); } catch (Throwable ignored) {}
            }
            if (extractor != null) {
                try { extractor.release(); } catch (Throwable ignored) {}
            }
        }
    }

    private static void initPrecalc(int srcW, int srcH, int dstW, int dstH,
                                    int uPixelStride, int vPixelStride,
                                    int uRowStride, int vRowStride,
                                    int[] srcX, int[] srcY,
                                    int[] uvXOffset, int[] vXOffset,
                                    int[] uvYOffset, int[] vYOffset) {
        float scale = Math.max((float) dstW / srcW, (float) dstH / srcH);
        float cropW = dstW / scale;
        float cropH = dstH / scale;
        float cropX = (srcW - cropW) / 2f;
        float cropY = (srcH - cropH) / 2f;

        for (int x = 0; x < dstW; x++) {
            int sx = Math.max(0, Math.min(srcW - 1, (int) (cropX + x / scale)));
            srcX[x] = sx;
            uvXOffset[x] = (sx / 2) * uPixelStride;
            vXOffset[x] = (sx / 2) * vPixelStride;
        }

        for (int y = 0; y < dstH; y++) {
            int sy = Math.max(0, Math.min(srcH - 1, (int) (cropY + y / scale)));
            srcY[y] = sy;
            uvYOffset[y] = (sy / 2) * uRowStride;
            vYOffset[y] = (sy / 2) * vRowStride;
        }
    }

    private static void scaleYuvToRgb(Image image, int dstW, int dstH,
                                      int[] srcX, int[] srcY,
                                      int[] uvXOffset, int[] vXOffset,
                                      int[] uvYOffset, int[] vYOffset,
                                      int[] outPixels) {
        Image.Plane[] planes = image.getPlanes();
        ByteBuffer yBuf = planes[0].getBuffer();
        ByteBuffer uBuf = planes[1].getBuffer();
        ByteBuffer vBuf = planes[2].getBuffer();
        int yRowStride = planes[0].getRowStride();

        for (int y = 0; y < dstH; y++) {
            int yRowOffset = srcY[y] * yRowStride;
            int uvRow = uvYOffset[y];
            int vRow = vYOffset[y];
            int lineStart = y * dstW;

            for (int x = 0; x < dstW; x++) {
                int yVal = yBuf.get(yRowOffset + srcX[x]) & 0xFF;
                int uVal = (uBuf.get(uvRow + uvXOffset[x]) & 0xFF) - 128;
                int vVal = (vBuf.get(vRow + vXOffset[x]) & 0xFF) - 128;

                int r = yVal + (1436 * vVal >> 10);
                int g = yVal - (352 * uVal + 731 * vVal >> 10);
                int b = yVal + (1815 * uVal >> 10);

                if (r < 0) r = 0; else if (r > 255) r = 255;
                if (g < 0) g = 0; else if (g > 255) g = 255;
                if (b < 0) b = 0; else if (b > 255) b = 255;

                outPixels[lineStart + x] = 0xFF000000 | (r << 16) | (g << 8) | b;
            }
        }
    }

    private static boolean saveThumbnail(Context context, String videoPath, File targetFile, AnimatedWebpEncoder encoder) {
        boolean written = false;
        if (targetFile != null) {
            try {
                encoder.writeToFile(targetFile);
                written = targetFile.exists() && targetFile.length() > 0;
                if (written) {
                    Log.i(TAG, "Successfully saved WebP thumbnail to: " + targetFile.getAbsolutePath() + " (" + targetFile.length() + " bytes)");
                    return true;
                }
            } catch (Throwable t) {
                Log.w(TAG, "Primary write failed to " + targetFile + ": " + t.getMessage(), t);
            }
        }
        if (!written && context != null) {
            try {
                File fallbackDir = getFallbackThumbnailsDir(context);
                String name = new File(videoPath).getName();
                int dot = name.lastIndexOf('.');
                String baseName = (dot > 0) ? name.substring(0, dot) : name;
                File fallbackFile = new File(fallbackDir, baseName + "_min.webp");
                encoder.writeToFile(fallbackFile);
                return fallbackFile.exists() && fallbackFile.length() > 0;
            } catch (Throwable t) {
                Log.e(TAG, "Fallback write failed: " + t.getMessage(), t);
            }
        }
        return false;
    }

    private static void generateThumbnailWithRetriever(Context context, String videoPath, File targetFile, ProgressListener listener) throws Exception {
        MediaMetadataRetriever mmr = new MediaMetadataRetriever();
        try {
            mmr.setDataSource(videoPath);

            String durStr = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            long totalDurationMs = 5000L;
            if (durStr != null) {
                try {
                    long parsed = Long.parseLong(durStr);
                    if (parsed > 0) totalDurationMs = parsed;
                } catch (NumberFormatException ignored) {}
            }

            long durationMs = totalDurationMs;
            if (durationMs > MAX_DURATION_MS) {
                durationMs = MAX_DURATION_MS;
            }
            if (durationMs < 500L) {
                durationMs = 500L;
            }

            int frameCount = (int) Math.round((durationMs / 1000.0) * THUMB_FPS);
            if (frameCount < 1) frameCount = 1;

            long stepUs = (durationMs * 1000L) / frameCount;

            AnimatedWebpEncoder encoder = new AnimatedWebpEncoder(THUMB_WIDTH, THUMB_HEIGHT, THUMB_FPS);
            Bitmap canvasBmp = Bitmap.createBitmap(THUMB_WIDTH, THUMB_HEIGHT, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(canvasBmp);
            Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);

            Bitmap firstFrameCopy = null;

            for (int i = 0; i < frameCount; i++) {
                long timeUs = i * stepUs;
                Bitmap frame = null;

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                    try {
                        frame = mmr.getScaledFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST, THUMB_WIDTH, THUMB_HEIGHT);
                    } catch (Throwable ignored) {}
                }
                if (frame == null) {
                    try {
                        Bitmap raw = mmr.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST);
                        if (raw != null) {
                            frame = Bitmap.createScaledBitmap(raw, THUMB_WIDTH, THUMB_HEIGHT, true);
                            if (raw != frame) raw.recycle();
                        }
                    } catch (Throwable ignored) {}
                }

                if (frame == null) {
                    canvas.drawColor(0xFF000000);
                } else {
                    if (frame.getWidth() == THUMB_WIDTH && frame.getHeight() == THUMB_HEIGHT) {
                        canvas.drawBitmap(frame, 0, 0, null);
                    } else {
                        canvas.drawColor(0xFF000000);
                        float scale = Math.min((float) THUMB_WIDTH / frame.getWidth(), (float) THUMB_HEIGHT / frame.getHeight());
                        float dw = frame.getWidth() * scale;
                        float dh = frame.getHeight() * scale;
                        float dx = (THUMB_WIDTH - dw) / 2f;
                        float dy = (THUMB_HEIGHT - dh) / 2f;
                        canvas.drawBitmap(frame, null, new RectF(dx, dy, dx + dw, dy + dh), paint);
                    }
                    frame.recycle();
                }

                if (i == 0) {
                    firstFrameCopy = canvasBmp.copy(Bitmap.Config.ARGB_8888, false);
                }

                encoder.addFrame(canvasBmp, THUMB_QUALITY);

                if (listener != null) {
                    listener.onProgress((float) (i + 1) / (float) frameCount);
                }
            }

            canvasBmp.recycle();

            boolean written = saveThumbnail(context, videoPath, targetFile, encoder);
            if (written && firstFrameCopy != null && targetFile != null) {
                sFirstFrameCache.put(targetFile.getAbsolutePath(), firstFrameCopy);
            }
        } finally {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    mmr.close();
                } else {
                    mmr.release();
                }
            } catch (Throwable ignored) {}
        }
    }
    public static Bitmap getFirstFrame(Context context, String channelKey) {
        File file = getThumbnailFileForChannel(context, channelKey);
        if (file == null || !file.exists() || file.length() == 0) return null;

        String path = file.getAbsolutePath();
        Bitmap cached = sFirstFrameCache.get(path);
        if (cached != null && !cached.isRecycled()) {
            return cached;
        }

        Bitmap decoded = loadFirstFrameFromFile(file);
        if (decoded != null) {
            sFirstFrameCache.put(path, decoded);
        }
        return decoded;
    }

    private static Bitmap loadFirstFrameFromFile(File file) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                ImageDecoder.Source src = ImageDecoder.createSource(file);
                return ImageDecoder.decodeBitmap(src, (decoder, info, source) -> {
                    decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                });
            } catch (Throwable t) {
                Log.w(TAG, "ImageDecoder first frame failed, trying RIFF fallback: " + t.getMessage());
            }
        }

        // Fallback: parse first ANMF chunk subchunks and decode via BitmapFactory
        try {
            byte[] data = readAllBytes(file);
            if (data != null && data.length > 32) {
                byte[] firstFrameWebp = extractFirstFrameWebpBytes(data, data.length);
                if (firstFrameWebp != null) {
                    return BitmapFactory.decodeByteArray(firstFrameWebp, 0, firstFrameWebp.length);
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "Error decoding first frame from " + file, t);
        }
        return null;
    }

    private static byte[] extractFirstFrameWebpBytes(byte[] data, int length) {
        int idx = 12; // skip RIFF <len> WEBP
        while (idx + 8 <= length) {
            String fourcc = new String(data, idx, 4, StandardCharsets.US_ASCII);
            int chunkSize = ((data[idx + 4] & 0xFF))
                    | ((data[idx + 5] & 0xFF) << 8)
                    | ((data[idx + 6] & 0xFF) << 16)
                    | ((data[idx + 7] & 0xFF) << 24);

            if ("ANMF".equals(fourcc)) {
                int subchunksStart = idx + 24;
                int subchunksLen = chunkSize - 16;
                if (subchunksStart + subchunksLen <= length && subchunksLen > 8) {
                    byte[] sub = new byte[subchunksLen];
                    System.arraycopy(data, subchunksStart, sub, 0, subchunksLen);
                    return AnimatedWebpEncoder.makeSingleFrameWebp(sub);
                }
                break;
            }
            int padded = chunkSize + (chunkSize % 2);
            idx += 8 + padded;
        }
        return null;
    }

    /**
     * Loads an animated Drawable for on-demand playback when focused for >= 300 ms.
     * Uses AnimatedImageDrawable on API 28+, and FallbackWebpDrawable as fallback.
     */
    public static Drawable loadAnimatedDrawable(Context context, String channelKey) {
        File file = getThumbnailFileForChannel(context, channelKey);
        if (file == null || !file.exists() || file.length() == 0) return null;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                ImageDecoder.Source src = ImageDecoder.createSource(file);
                Drawable d = ImageDecoder.decodeDrawable(src);
                if (d instanceof AnimatedImageDrawable) {
                    AnimatedImageDrawable anim = (AnimatedImageDrawable) d;
                    anim.setRepeatCount(AnimatedImageDrawable.REPEAT_INFINITE);
                    return anim;
                }
            } catch (Throwable t) {
                Log.w(TAG, "ImageDecoder animated decode failed: " + t.getMessage());
            }
        }

        // Robust fallback player
        return new FallbackWebpDrawable(file);
    }

    public static void clearCache() {
        sFirstFrameCache.evictAll();
    }

    private static byte[] readAllBytes(File file) {
        try (FileInputStream fis = new FileInputStream(file);
             ByteArrayOutputStream baos = new ByteArrayOutputStream((int) file.length())) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = fis.read(buf)) > 0) {
                baos.write(buf, 0, n);
            }
            return baos.toByteArray();
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Lightweight fallback animated drawable that decodes frames on the fly
     * with minimal memory footprint (only 1 active frame bitmap).
     */
    public static final class FallbackWebpDrawable extends Drawable implements Animatable, Runnable {
        private final List<byte[]> frameWebpBytes = new ArrayList<>();
        private final List<Integer> frameDurations = new ArrayList<>();
        private int currentFrame = 0;
        private boolean isRunning = false;
        private Bitmap activeBitmap = null;
        private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);

        public FallbackWebpDrawable(File file) {
            loadFrames(file);
            if (!frameWebpBytes.isEmpty()) {
                activeBitmap = BitmapFactory.decodeByteArray(frameWebpBytes.get(0), 0, frameWebpBytes.get(0).length);
            }
        }

        private void loadFrames(File file) {
            byte[] data = readAllBytes(file);
            if (data == null || data.length < 32) return;
            int read = data.length;
            int idx = 12;
            while (idx + 8 <= read) {
                String fourcc = new String(data, idx, 4, StandardCharsets.US_ASCII);
                int chunkSize = ((data[idx + 4] & 0xFF))
                        | ((data[idx + 5] & 0xFF) << 8)
                        | ((data[idx + 6] & 0xFF) << 16)
                        | ((data[idx + 7] & 0xFF) << 24);

                if ("ANMF".equals(fourcc)) {
                    int dur = ((data[idx + 20] & 0xFF))
                            | ((data[idx + 21] & 0xFF) << 8)
                            | ((data[idx + 22] & 0xFF) << 16);
                    if (dur <= 0) dur = 67;

                    int subStart = idx + 24;
                    int subLen = chunkSize - 16;
                    if (subStart + subLen <= read && subLen > 8) {
                        byte[] sub = new byte[subLen];
                        System.arraycopy(data, subStart, sub, 0, subLen);
                        frameWebpBytes.add(AnimatedWebpEncoder.makeSingleFrameWebp(sub));
                        frameDurations.add(dur);
                    }
                }
                int padded = chunkSize + (chunkSize % 2);
                idx += 8 + padded;
            }
        }

        @Override
        public void start() {
            if (isRunning || frameWebpBytes.isEmpty()) return;
            isRunning = true;
            scheduleSelf(this, SystemClock.uptimeMillis() + getDuration(currentFrame));
        }

        @Override
        public void stop() {
            isRunning = false;
            unscheduleSelf(this);
            if (activeBitmap != null && !activeBitmap.isRecycled()) {
                activeBitmap.recycle();
                activeBitmap = null;
            }
        }

        @Override
        public boolean isRunning() {
            return isRunning;
        }

        @Override
        public void run() {
            if (!isRunning || frameWebpBytes.isEmpty()) return;
            currentFrame = (currentFrame + 1) % frameWebpBytes.size();
            byte[] bytes = frameWebpBytes.get(currentFrame);
            try {
                Bitmap old = activeBitmap;
                activeBitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                if (old != null && old != activeBitmap && !old.isRecycled()) {
                    old.recycle();
                }
            } catch (Throwable ignored) {}
            invalidateSelf();
            scheduleSelf(this, SystemClock.uptimeMillis() + getDuration(currentFrame));
        }

        private int getDuration(int index) {
            if (index >= 0 && index < frameDurations.size()) {
                return frameDurations.get(index);
            }
            return 67;
        }

        @Override
        public int getIntrinsicWidth() {
            return THUMB_WIDTH;
        }

        @Override
        public int getIntrinsicHeight() {
            return THUMB_HEIGHT;
        }

        @Override
        public void draw(Canvas canvas) {
            if (activeBitmap != null && !activeBitmap.isRecycled()) {
                canvas.drawBitmap(activeBitmap, null, getBounds(), paint);
            }
        }

        @Override
        public void setAlpha(int alpha) {
            paint.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(ColorFilter colorFilter) {
            paint.setColorFilter(colorFilter);
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
}
