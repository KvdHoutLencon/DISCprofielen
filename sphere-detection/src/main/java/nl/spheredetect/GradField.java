package nl.spheredetect;

/** Gradient field (dx, dy and magnitude) of an image. */
public final class GradField {
    public final int width;
    public final int height;
    public final float[] gx;
    public final float[] gy;
    public final float[] mag;

    public GradField(int width, int height) {
        this.width = width;
        this.height = height;
        this.gx = new float[width * height];
        this.gy = new float[width * height];
        this.mag = new float[width * height];
    }

    public float gxAt(double x, double y) { return ImageOps.bilinear(gx, width, height, x, y); }

    public float gyAt(double x, double y) { return ImageOps.bilinear(gy, width, height, x, y); }

    public float magAt(double x, double y) { return ImageOps.bilinear(mag, width, height, x, y); }
}
