package com.steve.ai.util;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.loading.FMLEnvironment;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

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
import java.util.concurrent.CompletableFuture;

/**
 * Utility to capture game screenshots for Vision-based AI processing.
 * Optimized for asynchronous processing and image scaling to support VLM.
 */
@SuppressWarnings("null")
public class VisualCapture {
    private static final Logger LOGGER = LogManager.getLogger();
    private static byte[] lastCapture = null;
    private static long lastCaptureTime = 0;
    private static boolean isProcessing = false;

    private static final int TARGET_WIDTH = 800; // Optimal for Gemini Vision
    private static final float JPEG_QUALITY = 0.75f;

    /**
     * Grabs a compressed screenshot from the client window.
     * Safely returns null on dedicated servers.
     */
    public static byte[] captureScreenshot() {
        if (FMLEnvironment.dist != Dist.CLIENT) {
            return null;
        }

        long now = System.currentTimeMillis();
        // Rate limit: 2 seconds to avoid overhead
        if (now - lastCaptureTime < 2000) {
            return lastCapture;
        }

        // If we are already processing an image, don't start another one to avoid thread pile-up
        if (isProcessing) {
            return lastCapture;
        }

        try {
            captureAsynchronous();
        } catch (Exception e) {
            LOGGER.error("Failed to initiate screenshot capture: " + e.getMessage());
        }

        return lastCapture;
    }

    private static void captureAsynchronous() {
        Minecraft mc = Minecraft.getInstance();
        
        // Step 1: Rapid capture on main thread (required for OpenGL access)
        final byte[] rawBytes;
        try (NativeImage nativeImage = Screenshot.takeScreenshot(mc.getMainRenderTarget())) {
            rawBytes = nativeImage.asByteArray();
        } catch (Exception e) {
            LOGGER.warn("Native screen capture failed: " + e.getMessage());
            return;
        }

        // Step 2: Offload CPU-heavy processing (Resize + JPEG) to background thread
        isProcessing = true;
        CompletableFuture.runAsync(() -> {
            try {
                processImage(rawBytes);
            } catch (Exception e) {
                LOGGER.error("Background image processing failed", e);
            } finally {
                isProcessing = false;
            }
        });
    }

    private static void processImage(byte[] pngBytes) throws IOException {
        BufferedImage original = ImageIO.read(new ByteArrayInputStream(pngBytes));
        if (original == null) return;

        // Calculate scaling
        double scale = (double) TARGET_WIDTH / original.getWidth();
        if (scale >= 1.0) scale = 1.0; // Don't up-scale

        int targetHeight = (int) (original.getHeight() * scale);
        
        // Resize
        BufferedImage resized = new BufferedImage(TARGET_WIDTH, targetHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = resized.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(original, 0, 0, TARGET_WIDTH, targetHeight, null);
        g.dispose();

        // Compress to JPEG with quality control
        lastCapture = compressToJpeg(resized, JPEG_QUALITY);
        lastCaptureTime = System.currentTimeMillis();
    }

    private static byte[] compressToJpeg(BufferedImage image, float quality) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpg");
        if (!writers.hasNext()) throw new IllegalStateException("No JPEG writers found");

        ImageWriter writer = writers.next();
        try (ImageOutputStream ios = ImageIO.createImageOutputStream(baos)) {
            writer.setOutput(ios);
            ImageWriteParam param = writer.getDefaultWriteParam();
            if (param.canWriteCompressed()) {
                param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                param.setCompressionQuality(quality);
            }
            writer.write(null, new IIOImage(image, null, null), param);
        } finally {
            writer.dispose();
        }
        return baos.toByteArray();
    }
}
