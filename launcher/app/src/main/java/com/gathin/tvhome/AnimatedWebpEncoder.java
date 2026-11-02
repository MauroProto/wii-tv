package com.gathin.tvhome;

import android.graphics.Bitmap;
import android.os.Build;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Lightweight, self-contained Animated WebP encoder adhering to Google WebP container specifications (RFC 9649).
 * Encodes sequential frames with specified duration, infinite loop, and 480x270 canvas dimensions.
 */
public final class AnimatedWebpEncoder {

    private final int width;
    private final int height;
    private final int frameDurationMs;
    private final int loopCount;
    private final List<byte[]> frames = new ArrayList<>();

    public AnimatedWebpEncoder(int width, int height, int fps) {
        this.width = width;
        this.height = height;
        this.frameDurationMs = Math.max(1, Math.round(1000f / fps));
        this.loopCount = 0; // 0 = infinite loop
    }

    public void addFrame(Bitmap bitmap, int quality) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, quality, baos);
        } else {
            bitmap.compress(Bitmap.CompressFormat.WEBP, quality, baos);
        }
        byte[] webpBytes = baos.toByteArray();
        byte[] subchunks = extractFrameSubchunks(webpBytes);
        frames.add(subchunks);
    }

    public void writeToFile(File outFile) throws IOException {
        File parent = outFile.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        File tmpFile = new File(outFile.getAbsolutePath() + ".tmp");
        try (FileOutputStream fos = new FileOutputStream(tmpFile)) {
            writeToStream(fos);
            fos.flush();
        }
        if (outFile.exists()) {
            outFile.delete();
        }
        if (!tmpFile.renameTo(outFile)) {
            // Fallback copy if rename across volumes fails
            try (FileOutputStream fos = new FileOutputStream(outFile)) {
                writeToStream(fos);
                fos.flush();
            } finally {
                tmpFile.delete();
            }
        }
    }

    public void writeToStream(OutputStream out) throws IOException {
        int totalAnmfSize = 0;
        for (byte[] subchunks : frames) {
            int anmfPayloadSize = 16 + subchunks.length;
            int anmfPaddedSize = anmfPayloadSize + (anmfPayloadSize % 2);
            totalAnmfSize += 8 + anmfPaddedSize;
        }

        // RIFF payload size:
        // 'WEBP' (4) + VP8X (8 + 10 = 18) + ANIM (8 + 6 = 14) + totalAnmfSize
        int riffPayloadSize = 4 + 18 + 14 + totalAnmfSize;

        ByteBuffer header = ByteBuffer.allocate(12 + 18 + 14);
        header.order(ByteOrder.LITTLE_ENDIAN);

        // RIFF header
        header.put((byte) 'R').put((byte) 'I').put((byte) 'F').put((byte) 'F');
        header.putInt(riffPayloadSize);
        header.put((byte) 'W').put((byte) 'E').put((byte) 'B').put((byte) 'P');

        // VP8X extended chunk (10 bytes payload)
        header.put((byte) 'V').put((byte) 'P').put((byte) '8').put((byte) 'X');
        header.putInt(10);
        // Flags: Bit 1 is Animation (0x02)
        header.put((byte) 0x02);
        header.put((byte) 0).put((byte) 0).put((byte) 0); // reserved
        putUInt24LE(header, width - 1);
        putUInt24LE(header, height - 1);

        // ANIM chunk (6 bytes payload)
        header.put((byte) 'A').put((byte) 'N').put((byte) 'I').put((byte) 'M');
        header.putInt(6);
        header.putInt(0); // background color (RGBA = 0)
        header.putShort((short) loopCount); // loop count (0 = infinite)

        out.write(header.array(), 0, header.position());

        // ANMF chunks for each frame
        for (byte[] subchunks : frames) {
            int anmfPayloadSize = 16 + subchunks.length;
            int pad = anmfPayloadSize % 2;

            ByteBuffer anmfHeader = ByteBuffer.allocate(8 + 16);
            anmfHeader.order(ByteOrder.LITTLE_ENDIAN);

            anmfHeader.put((byte) 'A').put((byte) 'N').put((byte) 'M').put((byte) 'F');
            anmfHeader.putInt(anmfPayloadSize);

            // Frame X (uint24 LE) / 2
            putUInt24LE(anmfHeader, 0);
            // Frame Y (uint24 LE) / 2
            putUInt24LE(anmfHeader, 0);
            // Frame Width - 1 (uint24 LE)
            putUInt24LE(anmfHeader, width - 1);
            // Frame Height - 1 (uint24 LE)
            putUInt24LE(anmfHeader, height - 1);
            // Frame Duration in ms (uint24 LE)
            putUInt24LE(anmfHeader, frameDurationMs);
            // Flags: bit 1 = do not blend (overwrite canvas), bit 0 = do not dispose -> 0x02
            anmfHeader.put((byte) 0x02);

            out.write(anmfHeader.array(), 0, anmfHeader.position());
            out.write(subchunks);
            if (pad != 0) {
                out.write(0);
            }
        }
    }

    private static void putUInt24LE(ByteBuffer buf, int val) {
        buf.put((byte) (val & 0xFF));
        buf.put((byte) ((val >> 8) & 0xFF));
        buf.put((byte) ((val >> 16) & 0xFF));
    }

    /**
     * Extracts bitstream subchunks ('VP8 ', 'VP8L', 'ALPH') from a single-frame WebP byte stream.
     */
    static byte[] extractFrameSubchunks(byte[] webpBytes) {
        if (webpBytes == null || webpBytes.length < 12) {
            return new byte[0];
        }
        int offset = 12; // skip RIFF <len> WEBP
        ByteArrayOutputStream subchunks = new ByteArrayOutputStream();
        while (offset + 8 <= webpBytes.length) {
            String fourcc = new String(webpBytes, offset, 4, StandardCharsets.US_ASCII);
            int chunkSize = ((webpBytes[offset + 4] & 0xFF))
                    | ((webpBytes[offset + 5] & 0xFF) << 8)
                    | ((webpBytes[offset + 6] & 0xFF) << 16)
                    | ((webpBytes[offset + 7] & 0xFF) << 24);

            int paddedChunkSize = chunkSize + (chunkSize % 2);
            int totalChunkBytes = 8 + paddedChunkSize;

            if ("VP8 ".equals(fourcc) || "VP8L".equals(fourcc) || "ALPH".equals(fourcc)) {
                int lenToWrite = Math.min(totalChunkBytes, webpBytes.length - offset);
                subchunks.write(webpBytes, offset, lenToWrite);
            }
            offset += totalChunkBytes;
        }
        if (subchunks.size() == 0) {
            int len = Math.max(0, webpBytes.length - 12);
            subchunks.write(webpBytes, 12, len);
        }
        return subchunks.toByteArray();
    }

    /**
     * Reconstructs a valid single-frame WebP file byte array from frame subchunks,
     * suitable for decoding with BitmapFactory.decodeByteArray.
     */
    public static byte[] makeSingleFrameWebp(byte[] subchunks) {
        int riffPayloadLen = 4 + subchunks.length;
        ByteBuffer buf = ByteBuffer.allocate(12 + subchunks.length);
        buf.order(ByteOrder.LITTLE_ENDIAN);
        buf.put((byte) 'R').put((byte) 'I').put((byte) 'F').put((byte) 'F');
        buf.putInt(riffPayloadLen);
        buf.put((byte) 'W').put((byte) 'E').put((byte) 'B').put((byte) 'P');
        buf.put(subchunks);
        return buf.array();
    }
}

