package nl.spheredetect;

/**
 * Simple single channel (grayscale) floating point image.
 *
 * Values are nominally in the 0..255 range, but nothing enforces that; the
 * detector works on relative differences so any linear scale is fine.
 *
 * Row major layout: index = y * width + x.
 */
public final class GrayImage {

    public final int width;
    public final int height;
    public final float[] data;

    public GrayImage(int width, int height) {
        this(width, height, new float[width * height]);
    }

    public GrayImage(int width, int height, float[] data) {
        if (width <= 0 || height <= 0) throw new IllegalArgumentException("bad size " + width + "x" + height);
        if (data.length < width * height) throw new IllegalArgumentException("buffer too small");
        this.width = width;
        this.height = height;
        this.data = data;
    }

    public float at(int x, int y) {
        return data[y * width + x];
    }

    public void set(int x, int y, float v) {
        data[y * width + x] = v;
    }

    /** True when (x,y) can be bilinearly sampled while staying 'margin' px away from the border. */
    public boolean canSample(double x, double y, double margin) {
        return x >= margin && y >= margin && x <= width - 1 - margin && y <= height - 1 - margin;
    }

    /** Bilinear sample. Caller must guarantee 0 <= x <= width-1 and 0 <= y <= height-1. */
    public float bilinear(double x, double y) {
        return ImageOps.bilinear(data, width, height, x, y);
    }

    public GrayImage crop(int x0, int y0, int w, int h) {
        if (x0 < 0 || y0 < 0 || x0 + w > width || y0 + h > height) {
            throw new IllegalArgumentException("crop out of bounds");
        }
        GrayImage out = new GrayImage(w, h);
        for (int y = 0; y < h; y++) {
            System.arraycopy(data, (y0 + y) * width + x0, out.data, y * w, w);
        }
        return out;
    }

    public GrayImage copy() {
        float[] d = new float[width * height];
        System.arraycopy(data, 0, d, 0, d.length);
        return new GrayImage(width, height, d);
    }

    // ---------------------------------------------------------------- factories

    /**
     * Wraps the Y (luma) plane of a camera frame, optionally cropping a window out of it.
     * This is the cheapest possible path on Android: {@code ImageProxy.getPlanes()[0]}
     * of an {@code YUV_420_888} frame is already grayscale, no colour conversion needed.
     *
     * @param y         luma bytes (unsigned)
     * @param rowStride bytes per row in {@code y} (ImageProxy.PlaneProxy#getRowStride)
     * @param pixelStride bytes between horizontally adjacent luma samples (usually 1)
     * @param cropX     left edge of the window to extract
     * @param cropY     top edge of the window to extract
     * @param cropW     window width
     * @param cropH     window height
     */
    public static GrayImage fromLuma(byte[] y, int rowStride, int pixelStride,
                                     int cropX, int cropY, int cropW, int cropH) {
        GrayImage out = new GrayImage(cropW, cropH);
        for (int j = 0; j < cropH; j++) {
            int src = (cropY + j) * rowStride + cropX * pixelStride;
            int dst = j * cropW;
            for (int i = 0; i < cropW; i++) {
                out.data[dst + i] = (y[src] & 0xFF);
                src += pixelStride;
                dst++;
            }
        }
        return out;
    }

    /** Converts packed ARGB/RGBA pixels (e.g. Bitmap.getPixels) to luma. */
    public static GrayImage fromArgb(int[] argb, int width, int height) {
        GrayImage out = new GrayImage(width, height);
        for (int i = 0, n = width * height; i < n; i++) {
            int p = argb[i];
            int r = (p >> 16) & 0xFF;
            int g = (p >> 8) & 0xFF;
            int b = p & 0xFF;
            out.data[i] = 0.299f * r + 0.587f * g + 0.114f * b;
        }
        return out;
    }
}
