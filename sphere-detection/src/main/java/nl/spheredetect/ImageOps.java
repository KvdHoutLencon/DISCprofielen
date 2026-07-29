package nl.spheredetect;

/** Small collection of image primitives. Pure Java, no allocations in inner loops. */
public final class ImageOps {

    private ImageOps() {}

    /** Bilinear interpolation on a raw buffer. Coordinates must be inside [0,w-1] x [0,h-1]. */
    public static float bilinear(float[] a, int w, int h, double x, double y) {
        if (x < 0) x = 0; else if (x > w - 1) x = w - 1;
        if (y < 0) y = 0; else if (y > h - 1) y = h - 1;
        int x0 = (int) x;
        int y0 = (int) y;
        int x1 = x0 + 1; if (x1 > w - 1) x1 = w - 1;
        int y1 = y0 + 1; if (y1 > h - 1) y1 = h - 1;
        double fx = x - x0;
        double fy = y - y0;
        int r0 = y0 * w;
        int r1 = y1 * w;
        double top = a[r0 + x0] + (a[r0 + x1] - a[r0 + x0]) * fx;
        double bot = a[r1 + x0] + (a[r1 + x1] - a[r1 + x0]) * fx;
        return (float) (top + (bot - top) * fy);
    }

    /** Separable 1-2-1 blur (approximate Gaussian, sigma ~0.85). Border pixels are clamped. */
    public static GrayImage blur121(GrayImage src) {
        int w = src.width, h = src.height;
        float[] tmp = new float[w * h];
        float[] out = new float[w * h];
        float[] in = src.data;
        for (int y = 0; y < h; y++) {
            int r = y * w;
            for (int x = 0; x < w; x++) {
                float l = in[r + (x > 0 ? x - 1 : 0)];
                float c = in[r + x];
                float rr = in[r + (x < w - 1 ? x + 1 : w - 1)];
                tmp[r + x] = 0.25f * l + 0.5f * c + 0.25f * rr;
            }
        }
        for (int y = 0; y < h; y++) {
            int rm = (y > 0 ? y - 1 : 0) * w;
            int rc = y * w;
            int rp = (y < h - 1 ? y + 1 : h - 1) * w;
            for (int x = 0; x < w; x++) {
                out[rc + x] = 0.25f * tmp[rm + x] + 0.5f * tmp[rc + x] + 0.25f * tmp[rp + x];
            }
        }
        return new GrayImage(w, h, out);
    }

    /** 2x2 box average downsample. Result size is floor(w/2) x floor(h/2). */
    public static GrayImage downsample2(GrayImage src) {
        int w = src.width / 2, h = src.height / 2;
        if (w < 1 || h < 1) throw new IllegalArgumentException("image too small to downsample");
        GrayImage out = new GrayImage(w, h);
        for (int y = 0; y < h; y++) {
            int s0 = (2 * y) * src.width;
            int s1 = (2 * y + 1) * src.width;
            int d = y * w;
            for (int x = 0; x < w; x++) {
                int x0 = 2 * x;
                out.data[d + x] = 0.25f * (src.data[s0 + x0] + src.data[s0 + x0 + 1]
                        + src.data[s1 + x0] + src.data[s1 + x0 + 1]);
            }
        }
        return out;
    }

    /** Sobel gradients scaled to intensity-units per pixel. Border ring is zero. */
    public static GradField sobel(GrayImage src) {
        int w = src.width, h = src.height;
        GradField g = new GradField(w, h);
        float[] in = src.data;
        for (int y = 1; y < h - 1; y++) {
            int rm = (y - 1) * w, rc = y * w, rp = (y + 1) * w;
            for (int x = 1; x < w - 1; x++) {
                float a = in[rm + x - 1], b = in[rm + x], c = in[rm + x + 1];
                float d = in[rc + x - 1], f = in[rc + x + 1];
                float p = in[rp + x - 1], q = in[rp + x], r = in[rp + x + 1];
                float gx = ((c + 2 * f + r) - (a + 2 * d + p)) * 0.125f;
                float gy = ((p + 2 * q + r) - (a + 2 * b + c)) * 0.125f;
                int i = rc + x;
                g.gx[i] = gx;
                g.gy[i] = gy;
                g.mag[i] = (float) Math.sqrt(gx * gx + gy * gy);
            }
        }
        return g;
    }

    /**
     * Local contrast normalisation: (I - mean_w) / (std_w + eps), clamped to +-3.
     * Removes any local gain/offset difference in illumination, which makes image
     * comparison largely illumination invariant. Uses integral images, so cost is
     * independent of the window size.
     */
    public static float[] localNormalize(GrayImage src, int radius) {
        int w = src.width, h = src.height;
        double[] s1 = new double[(w + 1) * (h + 1)];
        double[] s2 = new double[(w + 1) * (h + 1)];
        for (int y = 0; y < h; y++) {
            double rs1 = 0, rs2 = 0;
            int ri = y * w;
            int oi = (y + 1) * (w + 1);
            int pi = y * (w + 1);
            for (int x = 0; x < w; x++) {
                float v = src.data[ri + x];
                rs1 += v;
                rs2 += (double) v * v;
                s1[oi + x + 1] = s1[pi + x + 1] + rs1;
                s2[oi + x + 1] = s2[pi + x + 1] + rs2;
            }
        }
        float[] out = new float[w * h];
        for (int y = 0; y < h; y++) {
            int y0 = Math.max(0, y - radius), y1 = Math.min(h - 1, y + radius);
            for (int x = 0; x < w; x++) {
                int x0 = Math.max(0, x - radius), x1 = Math.min(w - 1, x + radius);
                int n = (x1 - x0 + 1) * (y1 - y0 + 1);
                double a = box(s1, w, x0, y0, x1, y1);
                double b = box(s2, w, x0, y0, x1, y1);
                double mean = a / n;
                double var = b / n - mean * mean;
                if (var < 0) var = 0;
                double sd = Math.sqrt(var);
                double v = (src.data[y * w + x] - mean) / (sd + 1.5);
                if (v > 3) v = 3; else if (v < -3) v = -3;
                out[y * w + x] = (float) v;
            }
        }
        return out;
    }

    /** Local mean over a (2r+1)^2 window, window clamped at the borders. */
    public static GrayImage boxMean(GrayImage src, int radius) {
        int w = src.width, h = src.height;
        double[] s1 = new double[(w + 1) * (h + 1)];
        for (int y = 0; y < h; y++) {
            double rs = 0;
            int ri = y * w;
            int oi = (y + 1) * (w + 1);
            int pi = y * (w + 1);
            for (int x = 0; x < w; x++) {
                rs += src.data[ri + x];
                s1[oi + x + 1] = s1[pi + x + 1] + rs;
            }
        }
        GrayImage out = new GrayImage(w, h);
        for (int y = 0; y < h; y++) {
            int y0 = Math.max(0, y - radius), y1 = Math.min(h - 1, y + radius);
            for (int x = 0; x < w; x++) {
                int x0 = Math.max(0, x - radius), x1 = Math.min(w - 1, x + radius);
                int n = (x1 - x0 + 1) * (y1 - y0 + 1);
                out.data[y * w + x] = (float) (box(s1, w, x0, y0, x1, y1) / n);
            }
        }
        return out;
    }

    private static double box(double[] integral, int w, int x0, int y0, int x1, int y1) {
        int stride = w + 1;
        return integral[(y1 + 1) * stride + (x1 + 1)]
                - integral[y0 * stride + (x1 + 1)]
                - integral[(y1 + 1) * stride + x0]
                + integral[y0 * stride + x0];
    }

    /** Dilates a boolean mask by 'r' pixels using a square structuring element. */
    public static boolean[] dilate(boolean[] mask, int w, int h, int r) {
        boolean[] tmp = new boolean[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                boolean v = false;
                for (int k = -r; k <= r && !v; k++) {
                    int xx = x + k;
                    if (xx < 0 || xx >= w) continue;
                    if (mask[y * w + xx]) v = true;
                }
                tmp[y * w + x] = v;
            }
        }
        boolean[] out = new boolean[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                boolean v = false;
                for (int k = -r; k <= r && !v; k++) {
                    int yy = y + k;
                    if (yy < 0 || yy >= h) continue;
                    if (tmp[yy * w + x]) v = true;
                }
                out[y * w + x] = v;
            }
        }
        return out;
    }
}
