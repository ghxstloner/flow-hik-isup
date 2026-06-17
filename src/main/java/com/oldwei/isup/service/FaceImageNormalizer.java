package com.oldwei.isup.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;

/**
 * Normalizes a face photo before uploading it to a Hikvision device through the
 * ISUP session.
 *
 * <p>The normalization pipeline is intentionally close to the proven Laravel
 * {@code HikvisionPhotoProcessor} configuration used by the direct-IP
 * integration:
 * <ul>
 *   <li>resize to at most {@code 300x300} keeping aspect ratio;</li>
 *   <li>re-encode as <b>baseline</b> JPEG (progressive JPEGs are rejected by
 *       some access-control devices);</li>
 *   <li>cap file size by lowering JPEG quality when required.</li>
 * </ul>
 *
 * <p>This component is deliberately defensive: if the input cannot be decoded
 * by {@code ImageIO} (no JPEG reader available, corrupt image), the caller is
 * expected to fall back to the original bytes rather than fail the upload.
 */
@Slf4j
@Component
public class FaceImageNormalizer {

    public static final int DEFAULT_MAX_WIDTH = 300;
    public static final int DEFAULT_MAX_HEIGHT = 300;
    public static final float DEFAULT_QUALITY = 0.85f;
    public static final float MIN_QUALITY = 0.40f;
    public static final int MAX_FILE_SIZE_BYTES = 200 * 1024;

    public NormalizedFace normalize(byte[] input) {
        return normalize(input, DEFAULT_MAX_WIDTH, DEFAULT_MAX_HEIGHT, DEFAULT_QUALITY, MAX_FILE_SIZE_BYTES);
    }

    /**
     * @param input          original JPEG bytes
     * @param maxWidth       max width in pixels (aspect ratio kept)
     * @param maxHeight      max height in pixels (aspect ratio kept)
     * @param quality        starting JPEG quality (0..1)
     * @param maxFileBytes   hard cap on encoded byte length
     * @return normalized metadata + baseline JPEG bytes
     */
    public NormalizedFace normalize(byte[] input, int maxWidth, int maxHeight, float quality, int maxFileBytes) {
        if (input == null || input.length == 0) {
            throw new IllegalArgumentException("empty image bytes");
        }
        boolean originalProgressive = isProgressiveJpeg(input);
        BufferedImage src;
        try (ByteArrayInputStream in = new ByteArrayInputStream(input)) {
            src = ImageIO.read(in);
        } catch (IOException e) {
            throw new IllegalArgumentException("image could not be decoded", e);
        }
        if (src == null) {
            throw new IllegalArgumentException("image could not be decoded (unsupported format)");
        }

        int srcWidth = src.getWidth();
        int srcHeight = src.getHeight();
        BufferedImage scaled = scaleIfNeeded(src, maxWidth, maxHeight);
        int outWidth = scaled.getWidth();
        int outHeight = scaled.getHeight();

        float usedQuality = clampQuality(quality);
        byte[] encoded = encodeBaselineJpeg(scaled, usedQuality);

        // Enforce file-size cap by progressively lowering quality, mirroring
        // the Laravel HikvisionPhotoProcessor.enforceMaxSize behavior.
        while (encoded.length > maxFileBytes && usedQuality > MIN_QUALITY) {
            usedQuality = Math.max(MIN_QUALITY, usedQuality - 0.10f);
            encoded = encodeBaselineJpeg(scaled, usedQuality);
        }
        if (encoded.length > maxFileBytes) {
            log.warn("Face image still above size cap after normalization: bytes={}, maxBytes={}",
                    encoded.length, maxFileBytes);
        }

        return new NormalizedFace(
                srcWidth,
                srcHeight,
                outWidth,
                outHeight,
                input.length,
                encoded.length,
                originalProgressive,
                false,
                encoded
        );
    }

    /**
     * Detects whether a JPEG byte stream is progressive (SOF2, marker
     * {@code 0xFFC2}) versus baseline/sequential (SOF0/SOF1/SOF3). Only public
     * for diagnostic logging; this function never inspects pixel data.
     */
    public static boolean isProgressiveJpeg(byte[] data) {
        if (data == null || data.length < 4) {
            return false;
        }
        if ((data[0] & 0xFF) != 0xFF || (data[1] & 0xFF) != 0xD8) {
            // Not a JPEG SOI marker - cannot classify.
            return false;
        }
        int i = 2; // skip SOI
        while (i + 1 < data.length) {
            if ((data[i] & 0xFF) != 0xFF) {
                i++;
                continue;
            }
            // Skip any 0xFF padding bytes before the actual marker.
            while (i + 1 < data.length && (data[i + 1] & 0xFF) == 0xFF) {
                i++;
            }
            if (i + 1 >= data.length) {
                break;
            }
            int marker = data[i + 1] & 0xFF;
            // Standalone markers without length field.
            if (marker == 0xD8 || marker == 0xD9
                    || (marker >= 0xD0 && marker <= 0xD7)
                    || marker == 0x01) {
                i += 2;
                continue;
            }
            if (marker == 0xC2) {
                return true;
            }
            if (marker == 0xC0 || marker == 0xC1 || marker == 0xC3) {
                return false;
            }
            // RST markers handled above. For all others read length and skip.
            if (i + 3 >= data.length) {
                break;
            }
            int length = ((data[i + 2] & 0xFF) << 8) | (data[i + 3] & 0xFF);
            if (length < 2) {
                break;
            }
            i += 2 + length;
        }
        return false;
    }

    private BufferedImage scaleIfNeeded(BufferedImage src, int maxWidth, int maxHeight) {
        int w = src.getWidth();
        int h = src.getHeight();
        BufferedImage bgr = toBgr(src);
        if (w <= maxWidth && h <= maxHeight) {
            return bgr;
        }
        double scale = Math.min((double) maxWidth / w, (double) maxHeight / h);
        int nw = Math.max(1, (int) Math.round(w * scale));
        int nh = Math.max(1, (int) Math.round(h * scale));
        BufferedImage out = new BufferedImage(nw, nh, BufferedImage.TYPE_3BYTE_BGR);
        Graphics2D g = out.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.drawImage(bgr, 0, 0, nw, nh, null);
        } finally {
            g.dispose();
        }
        return out;
    }

    private BufferedImage toBgr(BufferedImage src) {
        if (src.getType() == BufferedImage.TYPE_3BYTE_BGR) {
            return src;
        }
        BufferedImage out = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_3BYTE_BGR);
        Graphics2D g = out.createGraphics();
        try {
            g.drawImage(src, 0, 0, null);
        } finally {
            g.dispose();
        }
        return out;
    }

    private byte[] encodeBaselineJpeg(BufferedImage img, float quality) {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpg");
        if (!writers.hasNext()) {
            throw new IllegalStateException("no JPEG ImageWriter available");
        }
        ImageWriter writer = writers.next();
        try {
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(quality);
            // Explicitly disable progressive (huffman tables) output so the
            // device receives a baseline sequential JPEG.
            param.setProgressiveMode(ImageWriteParam.MODE_DISABLED);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (ImageOutputStream ios = ImageIO.createImageOutputStream(out)) {
                writer.setOutput(ios);
                writer.write(null, new IIOImage(img, null, null), param);
            }
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("jpeg encoding failed", e);
        } finally {
            writer.dispose();
        }
    }

    private static float clampQuality(float quality) {
        if (quality <= 0f || quality > 1f) {
            return DEFAULT_QUALITY;
        }
        return quality;
    }

    /**
     * Metadata + baseline JPEG bytes for one normalized face photo. Metadata is
     * safe to log; the {@code bytes} field MUST NOT be logged.
     */
    public record NormalizedFace(
            int srcWidth,
            int srcHeight,
            int outWidth,
            int outHeight,
            int originalBytes,
            int normalizedBytes,
            boolean originalProgressive,
            boolean normalizedProgressive,
            byte[] bytes
    ) {
    }
}
