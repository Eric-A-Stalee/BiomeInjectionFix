package com.bicbiomecraft.biomefix;

/**
 * 2D simplex noise, seeded. Returns values in [-1, 1].
 */
public final class SimplexNoise2D {

    private static final double F2 = 0.5 * (Math.sqrt(3.0) - 1.0);
    private static final double G2 = (3.0 - Math.sqrt(3.0)) / 6.0;

    private static final int[][] GRAD2 = {
        {1,1},{-1,1},{1,-1},{-1,-1},
        {1,0},{-1,0},{0,1},{0,-1}
    };

    private final short[] perm = new short[512];

    public SimplexNoise2D(long seed) {
        short[] p = new short[256];
        for (int i = 0; i < 256; i++) p[i] = (short) i;
        for (int i = 255; i > 0; i--) {
            seed = seed * 6364136223846793005L + 1442695040888963407L;
            int j = (int) ((seed >>> 33) % (i + 1));
            if (j < 0) j += (i + 1);
            short tmp = p[i];
            p[i] = p[j];
            p[j] = tmp;
        }
        for (int i = 0; i < 512; i++) perm[i] = p[i & 255];
    }

    public double sample(double x, double y) {
        double s = (x + y) * F2;
        int i = fastFloor(x + s);
        int j = fastFloor(y + s);
        double t = (i + j) * G2;
        double x0 = x - (i - t);
        double y0 = y - (j - t);

        int i1, j1;
        if (x0 > y0) { i1 = 1; j1 = 0; }
        else { i1 = 0; j1 = 1; }

        double x1 = x0 - i1 + G2;
        double y1 = y0 - j1 + G2;
        double x2 = x0 - 1.0 + 2.0 * G2;
        double y2 = y0 - 1.0 + 2.0 * G2;

        int ii = i & 255;
        int jj = j & 255;

        double n0 = contribute(x0, y0, ii, jj);
        double n1 = contribute(x1, y1, ii + i1, jj + j1);
        double n2 = contribute(x2, y2, ii + 1, jj + 1);

        return 70.0 * (n0 + n1 + n2);
    }

    private double contribute(double x, double y, int gi, int gj) {
        double t = 0.5 - x * x - y * y;
        if (t < 0) return 0.0;
        t *= t;
        int g = perm[(gi + perm[gj & 255]) & 255] & 7;
        return t * t * (GRAD2[g][0] * x + GRAD2[g][1] * y);
    }

    private static int fastFloor(double x) {
        int xi = (int) x;
        return x < xi ? xi - 1 : xi;
    }
}
