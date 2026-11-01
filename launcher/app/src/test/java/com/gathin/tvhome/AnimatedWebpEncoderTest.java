package com.gathin.tvhome;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class AnimatedWebpEncoderTest {

    @Test
    public void testMakeSingleFrameWebp() {
        byte[] dummySubchunk = new byte[]{ 'V', 'P', '8', ' ', 4, 0, 0, 0, 1, 2, 3, 4 };
        byte[] singleFrame = AnimatedWebpEncoder.makeSingleFrameWebp(dummySubchunk);

        assertNotNull(singleFrame);
        assertEquals(12 + dummySubchunk.length, singleFrame.length);

        String riff = new String(singleFrame, 0, 4, StandardCharsets.US_ASCII);
        assertEquals("RIFF", riff);

        String webp = new String(singleFrame, 8, 4, StandardCharsets.US_ASCII);
        assertEquals("WEBP", webp);

        String vp8 = new String(singleFrame, 12, 4, StandardCharsets.US_ASCII);
        assertEquals("VP8 ", vp8);
    }

    @Test
    public void testExtractFrameSubchunks() {
        // Build mock single frame WebP: RIFF <size> WEBP VP8  <size> <data>
        byte[] payload = new byte[]{ 10, 20, 30, 40 };
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ByteBuffer bb = ByteBuffer.allocate(24);
        bb.order(ByteOrder.LITTLE_ENDIAN);
        bb.put("RIFF".getBytes(StandardCharsets.US_ASCII));
        bb.putInt(12); // 4 + 8 + 4 - 8? payload length
        bb.put("WEBP".getBytes(StandardCharsets.US_ASCII));
        bb.put("VP8 ".getBytes(StandardCharsets.US_ASCII));
        bb.putInt(4);
        bb.put(payload);

        byte[] subchunks = AnimatedWebpEncoder.extractFrameSubchunks(bb.array());
        assertNotNull(subchunks);
        assertEquals(8 + 4, subchunks.length); // 8 header + 4 payload

        String fourcc = new String(subchunks, 0, 4, StandardCharsets.US_ASCII);
        assertEquals("VP8 ", fourcc);
    }

    @Test
    public void testWriteToStreamContainerStructure() throws Exception {
        AnimatedWebpEncoder encoder = new AnimatedWebpEncoder(240, 135, 15);
        // Mock 2 frames
        byte[] dummyFrame1 = new byte[]{ 'V', 'P', '8', ' ', 4, 0, 0, 0, 1, 2, 3, 4 };
        byte[] dummyFrame2 = new byte[]{ 'V', 'P', '8', ' ', 4, 0, 0, 0, 5, 6, 7, 8 };

        // Test writing stream with subchunks directly
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        // Add frames via reflection or addFrame
        java.lang.reflect.Field f = AnimatedWebpEncoder.class.getDeclaredField("frames");
        f.setAccessible(true);
        @SuppressWarnings("unchecked")
        java.util.List<byte[]> framesList = (java.util.List<byte[]>) f.get(encoder);
        framesList.add(dummyFrame1);
        framesList.add(dummyFrame2);

        encoder.writeToStream(out);
        byte[] result = out.toByteArray();
        assertTrue(result.length > 32);

        // Header: RIFF <size> WEBP
        assertEquals("RIFF", new String(result, 0, 4, StandardCharsets.US_ASCII));
        assertEquals("WEBP", new String(result, 8, 4, StandardCharsets.US_ASCII));

        // First chunk: VP8X
        assertEquals("VP8X", new String(result, 12, 4, StandardCharsets.US_ASCII));
        assertEquals(0x02, result[20]); // Animation flag bit 1

        // Canvas width 240-1 = 239 -> 0xEF
        assertEquals((byte) 0xEF, result[24]);
        assertEquals((byte) 0x00, result[25]);

        // Canvas height 135-1 = 134 -> 0x86
        assertEquals((byte) 0x86, result[27]);
        assertEquals((byte) 0x00, result[28]);

        // Second chunk: ANIM
        assertEquals("ANIM", new String(result, 30, 4, StandardCharsets.US_ASCII));

        // Third chunk: ANMF
        assertEquals("ANMF", new String(result, 44, 4, StandardCharsets.US_ASCII));
    }
}
