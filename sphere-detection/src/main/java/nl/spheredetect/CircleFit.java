package nl.spheredetect;

/**
 * Sub-pixel circle fit through edge points (algebraic / Kasa fit with iteratively
 * reweighted least squares, so a few stray points cannot drag the circle away).
 *
 * The Hough peak is only accurate to about a pixel; this refinement is what makes
 * the radius-consistency test sharp enough to tell a circle from a rounded square,
 * especially for small objects of 20-30 px across.
 */
public final class CircleFit {

    public double cx, cy, r;
    public int inliers;
    public double rmse;
    public boolean ok;

    /**
     * @param xs,ys edge point coordinates
     * @param ws    per point weight (gradient magnitude x radial agreement)
     * @param n     number of points
     * @param cx0,cy0,r0 initial guess (Hough peak); the result is rejected and the
     *                   guess returned when the fit runs away from it
     */
    public static CircleFit fit(double[] xs, double[] ys, double[] ws, int n,
                                double cx0, double cy0, double r0) {
        CircleFit out = new CircleFit();
        out.cx = cx0; out.cy = cy0; out.r = r0; out.ok = false;
        if (n < 6) return out;

        double mx = 0, my = 0;
        for (int i = 0; i < n; i++) { mx += xs[i]; my += ys[i]; }
        mx /= n; my /= n;

        double[] w = new double[n];
        System.arraycopy(ws, 0, w, 0, n);

        double cx = cx0, cy = cy0, r = r0;
        double[] dist = new double[n];

        for (int iter = 0; iter < 4; iter++) {
            // normal equations for x^2+y^2 + D x + E y + F = 0 (coords relative to centroid)
            double[][] A = new double[3][3];
            double[] b = new double[3];
            for (int i = 0; i < n; i++) {
                double x = xs[i] - mx, y = ys[i] - my;
                double z = -(x * x + y * y);
                double[] basis = {x, y, 1};
                double wi = w[i];
                for (int a = 0; a < 3; a++) {
                    for (int c = 0; c < 3; c++) A[a][c] += wi * basis[a] * basis[c];
                    b[a] += wi * basis[a] * z;
                }
            }
            double[] sol = solve3(A, b);
            if (sol == null) break;
            double ncx = -sol[0] / 2 + mx;
            double ncy = -sol[1] / 2 + my;
            double rr = sol[0] * sol[0] / 4 + sol[1] * sol[1] / 4 - sol[2];
            if (rr <= 0.25) break;
            double nr = Math.sqrt(rr);
            cx = ncx; cy = ncy; r = nr;

            for (int i = 0; i < n; i++) {
                double dx = xs[i] - cx, dy = ys[i] - cy;
                dist[i] = Math.abs(Math.sqrt(dx * dx + dy * dy) - r);
            }
            double s = Stats.sigmaFromMad(dist, n);
            if (s < 0.7) s = 0.7;
            for (int i = 0; i < n; i++) {
                double t = dist[i] / (2.0 * s);
                w[i] = ws[i] / (1.0 + t * t);
            }
        }

        // Stay anchored to the Hough hypothesis. Without this a short arc (a back-lit
        // crescent) lets the fit slide along the arc normal: centre and radius trade off
        // against each other and a wrong but self-consistent circle comes out.
        double moved = Math.hypot(cx - cx0, cy - cy0);
        if (moved > Math.max(2.5, 0.30 * r0) || r < 0.82 * r0 || r > 1.22 * r0) {
            out.cx = cx0; out.cy = cy0; out.r = r0; out.ok = false;
            return out;
        }

        double se = 0;
        int cnt = 0;
        for (int i = 0; i < n; i++) {
            double dx = xs[i] - cx, dy = ys[i] - cy;
            double d = Math.sqrt(dx * dx + dy * dy) - r;
            if (Math.abs(d) < 0.35 * r) { se += d * d; cnt++; }
        }
        out.cx = cx; out.cy = cy; out.r = r;
        out.inliers = cnt;
        out.rmse = cnt > 0 ? Math.sqrt(se / cnt) : 0;
        out.ok = cnt >= 6;
        return out;
    }

    /** Gaussian elimination with partial pivoting for a 3x3 system. */
    private static double[] solve3(double[][] A, double[] b) {
        double[][] m = new double[3][4];
        for (int i = 0; i < 3; i++) {
            System.arraycopy(A[i], 0, m[i], 0, 3);
            m[i][3] = b[i];
        }
        for (int col = 0; col < 3; col++) {
            int piv = col;
            for (int r = col + 1; r < 3; r++) if (Math.abs(m[r][col]) > Math.abs(m[piv][col])) piv = r;
            if (Math.abs(m[piv][col]) < 1e-10) return null;
            double[] t = m[col]; m[col] = m[piv]; m[piv] = t;
            for (int r = col + 1; r < 3; r++) {
                double f = m[r][col] / m[col][col];
                for (int c = col; c < 4; c++) m[r][c] -= f * m[col][c];
            }
        }
        double[] x = new double[3];
        for (int r = 2; r >= 0; r--) {
            double s = m[r][3];
            for (int c = r + 1; c < 3; c++) s -= m[r][c] * x[c];
            x[r] = s / m[r][r];
        }
        return x;
    }
}
