package nl.spheredetect;

/**
 * The two colour planes of a YUV_420_888 camera frame, addressed in LUMA coordinates.
 *
 * Chroma in 4:2:0 is stored at half resolution in both directions, so a ball 25 px
 * across in luma is only about 12 px across here. That is the reason colour is used in
 * this detector to judge REGIONS ("is this patch ball-coloured / grass-coloured?") and
 * never to trace contours: the geometry that separates a ball from a club head lives in
 * the luma plane, at full resolution.
 *
 * Where colour earns its keep is exactly where luma is weak:
 *  - a hard shadow sweeping over the spot (the player's or the club's) changes brightness
 *    a lot and colour hardly at all, so a shadow no longer looks like a departed ball;
 *  - sunlit grass is bright but stays green, so it cannot masquerade as a white ball;
 *  - a yellow ball on dry, pale grass may have little brightness contrast but plenty of
 *    colour contrast.
 *
 * No conversion or copying takes place: this wraps the ByteBuffers the camera already
 * handed you. Copy the bytes out yourself only if you must keep them past
 * ImageProxy.close().
 */
public final class ChromaPlanes {

    private final byte[] u;
    private final byte[] v;
    private final int uRowStride, uPixelStride;
    private final int vRowStride, vPixelStride;
    /** Luma-plane coordinate of chroma sample (0,0), and how many luma px per chroma px. */
    private final int cropX, cropY;
    private final int subsampleX, subsampleY;
    private final int chromaW, chromaH;

    /**
     * @param u,v                 the U and V plane bytes (unsigned, nominally 16..240)
     * @param uRowStride,uPixelStride  strides of the U plane, from ImageProxy.PlaneProxy
     * @param vRowStride,vPixelStride  strides of the V plane
     * @param chromaW,chromaH     size of the chroma planes
     * @param cropX,cropY         luma coordinate that chroma sample (0,0) corresponds to
     * @param subsampleX,subsampleY luma pixels per chroma pixel (2 and 2 for 4:2:0)
     */
    public ChromaPlanes(byte[] u, int uRowStride, int uPixelStride,
                        byte[] v, int vRowStride, int vPixelStride,
                        int chromaW, int chromaH,
                        int cropX, int cropY, int subsampleX, int subsampleY) {
        this.u = u;
        this.v = v;
        this.uRowStride = uRowStride;
        this.uPixelStride = uPixelStride;
        this.vRowStride = vRowStride;
        this.vPixelStride = vPixelStride;
        this.chromaW = chromaW;
        this.chromaH = chromaH;
        this.cropX = cropX;
        this.cropY = cropY;
        this.subsampleX = Math.max(1, subsampleX);
        this.subsampleY = Math.max(1, subsampleY);
    }

    /**
     * Convenience for the usual Android case: a 4:2:0 frame from which the detector uses
     * the window starting at (lumaCropX, lumaCropY).
     */
    public static ChromaPlanes yuv420(byte[] u, int uRowStride, int uPixelStride,
                                      byte[] v, int vRowStride, int vPixelStride,
                                      int fullChromaW, int fullChromaH,
                                      int lumaCropX, int lumaCropY) {
        return new ChromaPlanes(u, uRowStride, uPixelStride, v, vRowStride, vPixelStride,
                fullChromaW, fullChromaH, -lumaCropX, -lumaCropY, 2, 2);
    }

    /** Nearest-sample U at a luma coordinate, or -1 when outside the planes. */
    public int uAt(double lumaX, double lumaY) {
        int cx = (int) ((lumaX - cropX) / subsampleX + 0.5);
        int cy = (int) ((lumaY - cropY) / subsampleY + 0.5);
        if (cx < 0 || cy < 0 || cx >= chromaW || cy >= chromaH) return -1;
        int idx = cy * uRowStride + cx * uPixelStride;
        if (idx < 0 || idx >= u.length) return -1;
        return u[idx] & 0xFF;
    }

    /** Nearest-sample V at a luma coordinate, or -1 when outside the planes. */
    public int vAt(double lumaX, double lumaY) {
        int cx = (int) ((lumaX - cropX) / subsampleX + 0.5);
        int cy = (int) ((lumaY - cropY) / subsampleY + 0.5);
        if (cx < 0 || cy < 0 || cx >= chromaW || cy >= chromaH) return -1;
        int idx = cy * vRowStride + cx * vPixelStride;
        if (idx < 0 || idx >= v.length) return -1;
        return v[idx] & 0xFF;
    }

    /**
     * Robust average chroma over a disc, as {@code {u, v, n}} with u,v centred on 128
     * (so 0,0 is neutral grey). Returns n = 0 when nothing could be sampled.
     *
     * Sampling steps by one chroma pixel, so a small disc still yields every distinct
     * sample there is and nothing is counted twice.
     */
    public double[] meanChromaInDisc(double cx, double cy, double rInner, double rOuter) {
        double su = 0, sv = 0;
        int n = 0;
        double r2i = rInner * rInner, r2o = rOuter * rOuter;
        for (double y = cy - rOuter; y <= cy + rOuter; y += subsampleY) {
            for (double x = cx - rOuter; x <= cx + rOuter; x += subsampleX) {
                double dx = x - cx, dy = y - cy;
                double d2 = dx * dx + dy * dy;
                if (d2 > r2o || d2 < r2i) continue;
                int uu = uAt(x, y);
                int vv = vAt(x, y);
                if (uu < 0 || vv < 0) continue;
                su += uu - 128;
                sv += vv - 128;
                n++;
            }
        }
        if (n == 0) return new double[]{0, 0, 0};
        return new double[]{su / n, sv / n, n};
    }
}
