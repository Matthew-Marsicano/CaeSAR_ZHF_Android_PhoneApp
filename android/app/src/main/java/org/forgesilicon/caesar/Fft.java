package org.forgesilicon.caesar;

/** In-place radix-2 FFT, the same transform the CaeSAR pipelined FFT block runs on chip. */
public final class Fft {
    private Fft() {}

    public static void transform(double[] re, double[] im) {
        int n = re.length;
        if (Integer.bitCount(n) != 1 || im.length != n) throw new IllegalArgumentException("Length must be a power of two");
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            for (; (j & bit) != 0; bit >>= 1) j ^= bit;
            j ^= bit;
            if (i < j) { double t = re[i]; re[i] = re[j]; re[j] = t; t = im[i]; im[i] = im[j]; im[j] = t; }
        }
        for (int len = 2; len <= n; len <<= 1) {
            double angle = -2 * Math.PI / len, wr = Math.cos(angle), wi = Math.sin(angle);
            for (int i = 0; i < n; i += len) {
                double cr = 1, ci = 0;
                for (int k = 0; k < len / 2; k++) {
                    int a = i + k, b = a + len / 2;
                    double xr = re[b] * cr - im[b] * ci, xi = re[b] * ci + im[b] * cr;
                    re[b] = re[a] - xr; im[b] = im[a] - xi; re[a] += xr; im[a] += xi;
                    double nr = cr * wr - ci * wi; ci = cr * wi + ci * wr; cr = nr;
                }
            }
        }
    }

    /** Hann-windowed, zero-padded power spectrum of a gap-free signal. Index k has period n/k samples. */
    public static double[] power(double[] signal, int n) {
        double[] re = new double[n], im = new double[n];
        int m = Math.min(signal.length, n);
        double mean = 0;
        for (int i = 0; i < m; i++) mean += signal[i];
        mean /= Math.max(1, m);
        for (int i = 0; i < m; i++) re[i] = (signal[i] - mean) * (0.5 - 0.5 * Math.cos(2 * Math.PI * i / Math.max(1, m - 1)));
        transform(re, im);
        double[] p = new double[n / 2];
        for (int k = 0; k < n / 2; k++) p[k] = re[k] * re[k] + im[k] * im[k];
        return p;
    }

    /** Linear interpolation across NaN gaps; null when less than {@code minCoverage} of the samples are present. */
    public static double[] fill(float[] v, int from, int to, double minCoverage) {
        int n = to - from;
        if (n < 8) return null;
        double[] out = new double[n];
        int present = 0, last = -1;
        for (int i = 0; i < n; i++) {
            float x = v[from + i];
            if (Float.isNaN(x)) continue;
            present++;
            if (last < 0) for (int j = 0; j < i; j++) out[j] = x;
            else for (int j = last + 1; j < i; j++) out[j] = out[last] + (x - out[last]) * (j - last) / (double) (i - last);
            out[i] = x; last = i;
        }
        if (present < minCoverage * n || last < 0) return null;
        for (int j = last + 1; j < n; j++) out[j] = out[last];
        return out;
    }
}
