package net.eca.util;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;

//物品工具
public final class ItemUtil {

    private ItemUtil() {}

    //颜色变化周期
    private static final long DEFAULT_PERIOD = 3000L;

    //硬编码文本，默认周期
    public static MutableComponent gradient(String text, int color1, int color2) {
        return gradient(text, DEFAULT_PERIOD, color1, color2);
    }

    public static MutableComponent gradient(String text, int color1, int color2, int color3) {
        return gradient(text, DEFAULT_PERIOD, color1, color2, color3);
    }

    public static MutableComponent gradient(String text, int color1, int color2, int color3, int color4) {
        return gradient(text, DEFAULT_PERIOD, color1, color2, color3, color4);
    }

    //翻译键，默认周期
    public static MutableComponent gradient(Component translatable, int color1, int color2) {
        return gradient(translatable, DEFAULT_PERIOD, color1, color2);
    }

    public static MutableComponent gradient(Component translatable, int color1, int color2, int color3) {
        return gradient(translatable, DEFAULT_PERIOD, color1, color2, color3);
    }

    public static MutableComponent gradient(Component translatable, int color1, int color2, int color3, int color4) {
        return gradient(translatable, DEFAULT_PERIOD, color1, color2, color3, color4);
    }

    //翻译键，自定义周期
    public static MutableComponent gradient(Component translatable, long periodMs, int... colors) {
        return gradient(translatable.getString(), periodMs, colors);
    }

    //核心实现：整段文字保持空间渐变，色带整体平滑滑动
    public static MutableComponent gradient(String text, long periodMs, int... colors) {
        if (colors.length < 2 || text.isEmpty())
            return Component.literal(text);
        double phase = (System.currentTimeMillis() % periodMs) / (double) periodMs;
        double shift = (phase <= 0.5 ? phase * 2 : 2 - phase * 2);
        MutableComponent result = Component.empty();
        int len = text.length();
        int n = colors.length;
        for (int i = 0; i < len; i++) {
            //字符在文本中的空间位置0~1
            double charPos = (double) i / Math.max(len - 1, 1);
            //整体偏移：shift控制色带滑动幅度，乘以一个比例让滑动不过大
            double pos = (charPos + shift * 0.5) * (n - 1);
            //clamp到色带范围
            pos = Math.max(0, Math.min(pos, n - 1.0 - 1e-9));
            int idx = (int) pos;
            idx = Math.min(idx, n - 2);
            double t = pos - idx;
            int rgb = lerpColor(colors[idx], colors[idx + 1], t);
            result.append(Component.literal(String.valueOf(text.charAt(i)))
                    .setStyle(Style.EMPTY.withColor(TextColor.fromRgb(rgb))));
        }
        return result;
    }

    private static int lerpColor(int c1, int c2, double t) {
        int r = (int) (((c1 >> 16) & 0xFF) * (1 - t) + ((c2 >> 16) & 0xFF) * t);
        int g = (int) (((c1 >> 8) & 0xFF) * (1 - t) + ((c2 >> 8) & 0xFF) * t);
        int b = (int) ((c1 & 0xFF) * (1 - t) + (c2 & 0xFF) * t);
        return (r << 16) | (g << 8) | b;
    }
}
