package nl.spheredetect;

/**
 * Warps the reference into the sampling frame, removes the illumination difference
 * and produces the signed residual (what is in the sample that is not in the reference).
 *
 * Illumination handling is the crux here. The requirement is that the scene may be lit
 * differently than during calibration (including backlight). So instead of a plain
 * subtraction we fit a smooth gain/offset field:
 *
 *      sample(x,y) ~= gain(x,y) * warpedRef(x,y) + offset(x,y)
 *
 * The field is estimated per tile with ITERATIVELY REWEIGHTED least squares and then
 * bilinearly interpolated. Robust weights keep the ball (a local outlier) from being
 * absorbed into the illumination model, and tiles are forced to be at least 4x the
 * maximum ball diameter for the same reason.
 */
public final class ResidualMap {

    public final int width;
    public final int height;

    /** Reference resampled into the sampling frame. */
    public final float[] warped;
    public final boolean[] valid;
    /** sample - (gain*warpedRef + offset), signed, sensor units. */
    public final float[] residual;
    /** Mildly blurred residual; all edge/shape analysis runs on this. */
    public final GrayImage smooth;
    public final GradField grad;

    /** Robust sigma of the smoothed residual over the valid, unchanged area. */
    public final double sigma;
    /** Decision threshold applied to |residual|. */
    public final double threshold;
    public final boolean[] mask;
    public final int maskCount;

    /** Median gain/offset actually used (diagnostics: tells you how much the light changed). */
    public final double medianGain;
    public final double medianOffset;

    private ResidualMap(int w, int h, float[] warped, boolean[] valid, float[] residual,
                        GrayImage smooth, GradField grad, double sigma, double threshold,
                        boolean[] mask, int maskCount, double medianGain, double medianOffset) {
        this.width = w;
        this.height = h;
        this.warped = warped;
        this.valid = valid;
        this.residual = residual;
        this.smooth = smooth;
        this.grad = grad;
        this.sigma = sigma;
        this.threshold = threshold;
        this.mask = mask;
        this.maskCount = maskCount;
        this.medianGain = medianGain;
        this.medianOffset = medianOffset;
    }

    public static ResidualMap compute(GrayImage sample, GrayImage reference,
                                      SimilarityTransform t, DetectorConfig cfg, Calibration cal) {
        int w = sample.width, h = sample.height;
        float[] warped = new float[w * h];
        boolean[] valid = new boolean[w * h];

        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                double rx = t.mapX(x, y);
                double ry = t.mapY(x, y);
                int i = y * w + x;
                if (rx >= 0.5 && ry >= 0.5 && rx <= reference.width - 1.5 && ry <= reference.height - 1.5) {
                    warped[i] = reference.bilinear(rx, ry);
                    valid[i] = true;
                }
            }
        }

        // ---- illumination field ------------------------------------------------
        int tile = (int) Math.max(cfg.minIlluminationTile, Math.ceil(4 * cal.rMax));
        int nx = Math.max(1, w / tile);
        int ny = Math.max(1, h / tile);
        double[] gains = new double[nx * ny];
        double[] offs = new double[nx * ny];
        double[] cxs = new double[nx];
        double[] cys = new double[ny];

        // global fit first: used as a prior / fallback for weak tiles
        double[] global = robustAffineFit(sample.data, warped, valid, 0, 0, w, h, w, 2, 1.0, 0.0);

        for (int ty = 0; ty < ny; ty++) {
            int y0 = ty * h / ny, y1 = (ty + 1) * h / ny;
            cys[ty] = 0.5 * (y0 + y1 - 1);
            for (int tx2 = 0; tx2 < nx; tx2++) {
                int x0 = tx2 * w / nx, x1 = (tx2 + 1) * w / nx;
                cxs[tx2] = 0.5 * (x0 + x1 - 1);
                double[] fit = robustAffineFit(sample.data, warped, valid, x0, y0, x1, y1, w, 2,
                        global[0], global[1]);
                gains[ty * nx + tx2] = fit[0];
                offs[ty * nx + tx2] = fit[1];
            }
        }

        float[] residual = new float[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int i = y * w + x;
                if (!valid[i]) continue;
                double g = interp(gains, cxs, cys, nx, ny, x, y);
                double o = interp(offs, cxs, cys, nx, ny, x, y);
                residual[i] = (float) (sample.data[i] - (g * warped[i] + o));
            }
        }

        double[] gtmp = new double[gains.length];
        System.arraycopy(gains, 0, gtmp, 0, gains.length);
        double mg = Stats.medianInPlace(gtmp, gtmp.length);
        double[] otmp = new double[offs.length];
        System.arraycopy(offs, 0, otmp, 0, offs.length);
        double mo = Stats.medianInPlace(otmp, otmp.length);

        // ---- noise level + change mask -----------------------------------------
        GrayImage smooth = ImageOps.blur121(new GrayImage(w, h, residual));
        // Blank out invalid pixels so they never trigger the mask.
        for (int i = 0; i < w * h; i++) if (!valid[i]) smooth.data[i] = 0;

        int nSamp = 0;
        double[] samp = new double[(w / 2 + 1) * (h / 2 + 1)];
        for (int y = 1; y < h - 1; y += 2) {
            for (int x = 1; x < w - 1; x += 2) {
                int i = y * w + x;
                if (valid[i]) samp[nSamp++] = smooth.data[i];
            }
        }
        double sigma = Stats.sigmaFromMad(samp, nSamp);
        if (sigma < 0.35) sigma = 0.35;
        double threshold = Math.max(cal.residualFloor, cfg.kSigma * sigma);

        boolean[] mask = new boolean[w * h];
        int count = 0;
        for (int y = 1; y < h - 1; y++) {
            for (int x = 1; x < w - 1; x++) {
                int i = y * w + x;
                if (valid[i] && Math.abs(smooth.data[i]) > threshold) {
                    mask[i] = true;
                    count++;
                }
            }
        }

        GradField grad = ImageOps.sobel(smooth);
        return new ResidualMap(w, h, warped, valid, residual, smooth, grad, sigma, threshold,
                mask, count, mg, mo);
    }

    /**
     * Residual for a small window of the sampling image only.
     *
     * Once the ball has been located and the camera is standing still, the question per
     * frame is no longer "where is a ball" but "is the ball still where it was". That only
     * needs the neighbourhood of the known position, which is roughly an order of
     * magnitude less work than the whole frame - and it is also the reason a golf club
     * entering the picture elsewhere cannot disturb anything: it is simply not looked at.
     *
     * The window is small enough that illumination is uniform across it, so a single
     * robust gain/offset replaces the tile grid. The previous frame's values are passed in
     * as a prior, which keeps the estimate steady when something large (a club, a shoe)
     * temporarily covers part of the window.
     *
     * The returned map has the size of the WINDOW; its pixel (x,y) is sample pixel
     * (ox+x, oy+y). Convert coordinates before and after calling.
     */
    public static ResidualMap computeWindow(GrayImage sample, GrayImage reference,
                                            SimilarityTransform t, DetectorConfig cfg,
                                            Calibration cal, int ox, int oy, int w, int h,
                                            double priorGain, double priorOffset,
                                            double maxGainStep, double maxOffsetStep) {
        float[] warped = new float[w * h];
        boolean[] valid = new boolean[w * h];
        float[] cropped = new float[w * h];

        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int sx = ox + x, sy = oy + y;
                int i = y * w + x;
                if (sx < 0 || sy < 0 || sx >= sample.width || sy >= sample.height) continue;
                cropped[i] = sample.data[sy * sample.width + sx];
                double rx = t.mapX(sx, sy);
                double ry = t.mapY(sx, sy);
                if (rx >= 0.5 && ry >= 0.5 && rx <= reference.width - 1.5 && ry <= reference.height - 1.5) {
                    warped[i] = reference.bilinear(rx, ry);
                    valid[i] = true;
                }
            }
        }

        double[] fit = robustAffineFit(cropped, warped, valid, 0, 0, w, h, w, 1,
                priorGain, priorOffset);
        double g = fit[0], o = fit[1];

        // Illumination is allowed to drift only slowly. Real light changes - a cloud, the
        // sun moving - take seconds; a shadow edge sweeping through this small window
        // takes a few frames. Without a rate limit the fit would simply absorb that
        // shadow, the spot would look "explained", and a ball passing under a shadow
        // would be indistinguishable from a ball that had left. Capping the step per
        // frame means a fast local change stays in the residual, where it belongs.
        if (maxGainStep > 0) {
            g = Stats.clamp(g, priorGain - maxGainStep, priorGain + maxGainStep);
            o = Stats.clamp(o, priorOffset - maxOffsetStep, priorOffset + maxOffsetStep);
        }

        float[] residual = new float[w * h];
        for (int i = 0; i < w * h; i++) {
            if (valid[i]) residual[i] = (float) (cropped[i] - (g * warped[i] + o));
        }

        GrayImage smooth = ImageOps.blur121(new GrayImage(w, h, residual));
        for (int i = 0; i < w * h; i++) if (!valid[i]) smooth.data[i] = 0;

        double[] samp = new double[w * h];
        int nSamp = 0;
        for (int i = 0; i < w * h; i++) if (valid[i]) samp[nSamp++] = smooth.data[i];
        double sigma = Math.max(0.35, Stats.sigmaFromMad(samp, nSamp));
        double threshold = Math.max(cal.residualFloor, cfg.kSigma * sigma);

        boolean[] mask = new boolean[w * h];
        int count = 0;
        for (int y = 1; y < h - 1; y++) {
            for (int x = 1; x < w - 1; x++) {
                int i = y * w + x;
                if (valid[i] && Math.abs(smooth.data[i]) > threshold) { mask[i] = true; count++; }
            }
        }

        GradField grad = ImageOps.sobel(smooth);
        return new ResidualMap(w, h, warped, valid, residual, smooth, grad, sigma, threshold,
                mask, count, g, o);
    }

    /**
     * Builds a residual map for the reference-free fallback: the "residual" is simply
     * the locally high-pass filtered sample, so the shape machinery still works when
     * there is no usable reference alignment.
     */
    public static ResidualMap withoutReference(GrayImage sample, DetectorConfig cfg, Calibration cal) {
        int w = sample.width, h = sample.height;
        boolean[] valid = new boolean[w * h];
        for (int i = 0; i < w * h; i++) valid[i] = true;
        // No reference to subtract, so high-pass the sample instead: subtract a local
        // mean taken over a window clearly larger than the ball.
        GrayImage blurred = ImageOps.blur121(sample);
        GrayImage background = ImageOps.boxMean(sample, (int) Math.max(6, Math.ceil(2.5 * cal.rMax)));
        GrayImage smooth = new GrayImage(w, h);
        for (int i = 0; i < w * h; i++) smooth.data[i] = blurred.data[i] - background.data[i];
        float[] residual = new float[w * h];
        System.arraycopy(smooth.data, 0, residual, 0, residual.length);
        boolean[] mask = new boolean[w * h];
        int count = 0;
        for (int y = 1; y < h - 1; y++) {
            for (int x = 1; x < w - 1; x++) { mask[y * w + x] = true; count++; }
        }
        GradField grad = ImageOps.sobel(smooth);
        return new ResidualMap(w, h, new float[w * h], valid, residual, smooth, grad,
                1.0, cal.residualFloor, mask, count, 1.0, 0.0);
    }

    // -------------------------------------------------------------------- helpers

    /**
     * Robust fit of a = g*b + o over a rectangle, using Cauchy reweighting.
     * Falls back to offset-only (and then to the prior) when the data is degenerate.
     *
     * @return {gain, offset}
     */
    private static double[] robustAffineFit(float[] a, float[] b, boolean[] valid,
                                            int x0, int y0, int x1, int y1, int stride,
                                            int step, double priorGain, double priorOffset) {
        int cap = ((x1 - x0) / step + 1) * ((y1 - y0) / step + 1);
        double[] av = new double[cap];
        double[] bv = new double[cap];
        int n = 0;
        for (int y = y0; y < y1; y += step) {
            for (int x = x0; x < x1; x += step) {
                int i = y * stride + x;
                if (!valid[i]) continue;
                av[n] = a[i];
                bv[n] = b[i];
                n++;
            }
        }
        if (n < 25) return new double[]{priorGain, priorOffset};

        // spread of b decides whether a gain can be identified at all
        double mb = 0;
        for (int i = 0; i < n; i++) mb += bv[i];
        mb /= n;
        double varb = 0;
        for (int i = 0; i < n; i++) { double d = bv[i] - mb; varb += d * d; }
        varb /= n;

        double g = priorGain, o = priorOffset;
        boolean fitGain = varb > 9.0; // std > 3 grey levels: enough texture

        double[] res = new double[n];
        double scale = 0;
        for (int iter = 0; iter < 4; iter++) {
            for (int i = 0; i < n; i++) res[i] = av[i] - (g * bv[i] + o);
            scale = Stats.sigmaFromMad(res, n);
            if (scale < 1.0) scale = 1.0;
            double k = 2.5 * scale;

            double sw = 0, swb = 0, swa = 0, swbb = 0, swab = 0;
            for (int i = 0; i < n; i++) {
                double r = res[i] / k;
                double wgt = 1.0 / (1.0 + r * r);
                sw += wgt;
                swb += wgt * bv[i];
                swa += wgt * av[i];
                swbb += wgt * bv[i] * bv[i];
                swab += wgt * av[i] * bv[i];
            }
            if (fitGain) {
                double det = sw * swbb - swb * swb;
                if (Math.abs(det) > 1e-9) {
                    double ng = (sw * swab - swb * swa) / det;
                    double no = (swbb * swa - swb * swab) / det;
                    if (ng > 0.45 && ng < 2.2 && Math.abs(no) < 200) {
                        g = ng;
                        o = no;
                    } else {
                        g = 1.0;
                        o = (swa - swb) / sw;
                    }
                }
            } else {
                g = 1.0;
                o = (swa - swb) / sw;
            }
        }
        return new double[]{g, o};
    }

    /** Bilinear interpolation over the tile-centre grid, clamped outside. */
    private static double interp(double[] v, double[] cxs, double[] cys, int nx, int ny,
                                double x, double y) {
        if (nx == 1 && ny == 1) return v[0];
        int ix = 0;
        while (ix < nx - 2 && x > cxs[ix + 1]) ix++;
        int iy = 0;
        while (iy < ny - 2 && y > cys[iy + 1]) iy++;
        int ix2 = Math.min(nx - 1, ix + 1);
        int iy2 = Math.min(ny - 1, iy + 1);
        double fx = (ix2 == ix) ? 0 : Stats.clamp((x - cxs[ix]) / (cxs[ix2] - cxs[ix]), 0, 1);
        double fy = (iy2 == iy) ? 0 : Stats.clamp((y - cys[iy]) / (cys[iy2] - cys[iy]), 0, 1);
        double v00 = v[iy * nx + ix], v01 = v[iy * nx + ix2];
        double v10 = v[iy2 * nx + ix], v11 = v[iy2 * nx + ix2];
        double top = v00 + (v01 - v00) * fx;
        double bot = v10 + (v11 - v10) * fx;
        return top + (bot - top) * fy;
    }
}
