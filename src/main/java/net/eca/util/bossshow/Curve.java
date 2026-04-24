package net.eca.util.bossshow;

//关键帧曲线预设：控制 marker 区间内的播放速度缓动
public enum Curve {

    NONE,
    EASE_IN,
    EASE_OUT,
    EASE_IN_OUT,
    EASE_OUT_IN,
    STEP,
    BEZIER;

    private static final Curve[] VALUES = values();

    //CSS ease 标准控制点
    private static final double BEZ_X1 = 0.42, BEZ_Y1 = 0.0;
    private static final double BEZ_X2 = 0.58, BEZ_Y2 = 1.0;

    //把线性 t ∈ [0,1] 重映射为缓动后的 t'
    public double apply(double t) {
        if (t <= 0.0) return 0.0;
        if (t >= 1.0) return 1.0;
        return switch (this) {
            case NONE -> t;
            case EASE_IN -> t * t;
            case EASE_OUT -> 1.0 - (1.0 - t) * (1.0 - t);
            case EASE_IN_OUT -> t < 0.5
                ? 2.0 * t * t
                : 1.0 - 2.0 * (1.0 - t) * (1.0 - t);
            case EASE_OUT_IN -> t < 0.5
                ? 0.5 * (1.0 - (1.0 - 2.0 * t) * (1.0 - 2.0 * t))
                : 0.5 + 0.5 * (2.0 * t - 1.0) * (2.0 * t - 1.0);
            case STEP -> 0.0;
            case BEZIER -> cubicBezier(t, BEZ_X1, BEZ_Y1, BEZ_X2, BEZ_Y2);
        };
    }

    //循环到下一个预设
    public Curve next() {
        return VALUES[(ordinal() + 1) % VALUES.length];
    }

    //按钮显示文本
    public String displayName() {
        return switch (this) {
            case NONE -> "None";
            case EASE_IN -> "Ease In";
            case EASE_OUT -> "Ease Out";
            case EASE_IN_OUT -> "Ease In Out";
            case EASE_OUT_IN -> "Ease Out In";
            case STEP -> "Step";
            case BEZIER -> "Bezier";
        };
    }

    //i18n translation key（按钮上显示用）
    public String translationKey() {
        return "gui.eca.bossshow.curve." + name().toLowerCase();
    }

    //JSON 序列化用的 key
    public String key() {
        return name().toLowerCase();
    }

    public static Curve fromKey(String key) {
        if (key == null || key.isEmpty()) return NONE;
        for (Curve c : VALUES) {
            if (c.key().equals(key)) return c;
        }
        return NONE;
    }

    //cubic bezier：用 Newton 迭代解 x(s)=t，返回 y(s)
    private static double cubicBezier(double t, double x1, double y1, double x2, double y2) {
        double s = t;
        for (int i = 0; i < 8; i++) {
            double xs = bezierComponent(s, x1, x2) - t;
            double dx = bezierDerivative(s, x1, x2);
            if (Math.abs(dx) < 1e-10) break;
            s -= xs / dx;
            s = Math.max(0.0, Math.min(1.0, s));
        }
        return bezierComponent(s, y1, y2);
    }

    // B(s) = 3(1-s)²·s·p1 + 3(1-s)·s²·p2 + s³
    private static double bezierComponent(double s, double p1, double p2) {
        double inv = 1.0 - s;
        return 3.0 * inv * inv * s * p1 + 3.0 * inv * s * s * p2 + s * s * s;
    }

    // B'(s)
    private static double bezierDerivative(double s, double p1, double p2) {
        double inv = 1.0 - s;
        return 3.0 * inv * inv * p1 + 6.0 * inv * s * (p2 - p1) + 3.0 * s * s * (1.0 - p2);
    }
}
