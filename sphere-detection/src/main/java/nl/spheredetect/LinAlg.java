package nl.spheredetect;

/** Minimal dense linear algebra: just enough to solve small normal equations. */
public final class LinAlg {

    private LinAlg() {}

    /**
     * Solves A x = b by Gaussian elimination with partial pivoting.
     * A and b are consumed (modified in place).
     *
     * @return x, or null when the system is singular
     */
    public static double[] solve(double[][] A, double[] b) {
        int n = b.length;
        for (int col = 0; col < n; col++) {
            int piv = col;
            for (int r = col + 1; r < n; r++) {
                if (Math.abs(A[r][col]) > Math.abs(A[piv][col])) piv = r;
            }
            if (Math.abs(A[piv][col]) < 1e-12) return null;
            double[] t = A[col]; A[col] = A[piv]; A[piv] = t;
            double tb = b[col]; b[col] = b[piv]; b[piv] = tb;
            for (int r = col + 1; r < n; r++) {
                double f = A[r][col] / A[col][col];
                if (f == 0) continue;
                for (int c = col; c < n; c++) A[r][c] -= f * A[col][c];
                b[r] -= f * b[col];
            }
        }
        double[] x = new double[n];
        for (int r = n - 1; r >= 0; r--) {
            double s = b[r];
            for (int c = r + 1; c < n; c++) s -= A[r][c] * x[c];
            x[r] = s / A[r][r];
        }
        return x;
    }

    /**
     * Weighted least squares fit of y ~ sum_j coeff[j] * basis[i][j].
     *
     * @param basis  design matrix, n rows of m columns
     * @param y      observations
     * @param w      weights (may be null for uniform)
     * @param n      number of rows to use
     * @return coefficients, or null when the system is singular
     */
    public static double[] leastSquares(double[][] basis, double[] y, double[] w, int n, int m) {
        double[][] A = new double[m][m];
        double[] rhs = new double[m];
        for (int i = 0; i < n; i++) {
            double wi = (w == null) ? 1 : w[i];
            if (wi == 0) continue;
            double[] row = basis[i];
            for (int a = 0; a < m; a++) {
                double wa = wi * row[a];
                for (int c = a; c < m; c++) A[a][c] += wa * row[c];
                rhs[a] += wa * y[i];
            }
        }
        for (int a = 0; a < m; a++) for (int c = 0; c < a; c++) A[a][c] = A[c][a];
        return solve(A, rhs);
    }
}
