package local.jiege.hook.aicomposition;

/**
 * Keeps the suggested target on the scene while the phone turns. The camera hands the tracker its
 * rotation-vector attitude at each frame (quaternion w, x, y, z, device to world, as
 * SensorManager.getQuaternionFromVector gives it), so no sensor of our own is read.
 *
 * Pinhole model on the tracking frame: the target's viewing ray at the start is carried into the
 * device frame of the current attitude and projected back. Device axes: x right, y up, z out of the
 * screen; the back camera looks along -z, image x follows device x, image y runs down.
 */
final class AiCompositionAttitude {
    /** 35 mm equivalent focal length of the 1x lens (23 mm) against the 43.27 mm diagonal of the frame. */
    private static final double DIAGONAL_MM = 43.27;
    private static final double FOCAL_1X_MM = 23.0;

    private static float[] start;
    private static double startX;
    private static double startY;
    private static double focal;

    private AiCompositionAttitude() {}

    /** A composition round starts: target pixel (x, y) in a width x height frame at the given zoom ratio. */
    static synchronized void anchor(float[] attitude, float x, float y, int width, int height, float zoom) {
        start = attitude == null ? null : attitude.clone();
        startX = x;
        startY = y;
        double diagonal = Math.hypot(width, height);
        focal = diagonal / 2 / (DIAGONAL_MM / 2 / FOCAL_1X_MM) * Math.max(zoom, 1f);
    }

    /** The target's pixel in the current attitude; null while there is no attitude or the target is behind the camera. */
    static synchronized float[] project(float[] attitude, int width, int height) {
        if (start == null || attitude == null || attitude.length < 4 || focal <= 0) return null;
        double[] ray = {(startX - width / 2.0) / focal, -(startY - height / 2.0) / focal, -1};
        double[] world = rotate(start, ray, false);
        double[] now = rotate(attitude, world, true);
        if (now[2] >= -1e-3) return null;
        return new float[]{(float) (width / 2.0 + focal * now[0] / -now[2]), (float) (height / 2.0 - focal * now[1] / -now[2])};
    }

    /** v rotated by the quaternion (w, x, y, z), or by its inverse. */
    private static double[] rotate(float[] q, double[] v, boolean inverse) {
        double w = q[0];
        double x = inverse ? -q[1] : q[1];
        double y = inverse ? -q[2] : q[2];
        double z = inverse ? -q[3] : q[3];
        double norm = Math.sqrt(w * w + x * x + y * y + z * z);
        w /= norm;
        x /= norm;
        y /= norm;
        z /= norm;
        // v + 2 u x (u x v + w v), u = (x, y, z)
        double tx = y * v[2] - z * v[1] + w * v[0];
        double ty = z * v[0] - x * v[2] + w * v[1];
        double tz = x * v[1] - y * v[0] + w * v[2];
        return new double[]{
            v[0] + 2 * (y * tz - z * ty),
            v[1] + 2 * (z * tx - x * tz),
            v[2] + 2 * (x * ty - y * tx)};
    }
}
