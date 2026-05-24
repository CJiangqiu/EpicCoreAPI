package net.eca.util.item_extension;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;

import java.util.ArrayList;
import java.util.List;

//链式文字效果构建器，通过 ItemUtil.of(...) 创建，addEffect 附加效果，build() 生成组件
public final class EcaText {

    private final String text;
    private final List<TextEffect> effects = new ArrayList<>();

    private static final long DEFAULT_PERIOD = 3000L;

    EcaText(String text) {
        this.text = text != null ? text : "";
    }

    //效果附加子命名空间，每个方法附加一个效果后返回 EcaText 继续链式调用
    public final EffectAdder addEffect = new EffectAdder();

    public final class EffectAdder {

        //颜色层：双色渐变，默认周期
        public EcaText GRADIENT(int color1, int color2) {
            effects.add(new TextEffect.Gradient(DEFAULT_PERIOD, new int[]{color1, color2}));
            return EcaText.this;
        }

        //颜色层：自定义周期多色渐变
        public EcaText GRADIENT(long periodMs, int... colors) {
            if (colors.length < 2) throw new IllegalArgumentException("GRADIENT requires at least 2 colors");
            effects.add(new TextEffect.Gradient(periodMs, colors));
            return EcaText.this;
        }

        //颜色层：彩虹，默认周期
        public EcaText RAINBOW() {
            effects.add(new TextEffect.Rainbow(DEFAULT_PERIOD));
            return EcaText.this;
        }

        //颜色层：自定义周期彩虹
        public EcaText RAINBOW(long periodMs) {
            effects.add(new TextEffect.Rainbow(periodMs));
            return EcaText.this;
        }

        //颜色层：纯色
        public EcaText SOLID(int color) {
            effects.add(new TextEffect.Solid(color));
            return EcaText.this;
        }

        //调制层：随机字符变亮，density 为可触发字符比例
        public EcaText SHIMMER(float density) {
            effects.add(new TextEffect.Shimmer(density));
            return EcaText.this;
        }

        //调制层：随机字符乱码，chance 为单字符触发概率
        public EcaText GLITCH(float chance) {
            effects.add(new TextEffect.Glitch(chance));
            return EcaText.this;
        }

        //样式层
        public EcaText BOLD() {
            effects.add(new TextEffect.Bold());
            return EcaText.this;
        }

        public EcaText ITALIC() {
            effects.add(new TextEffect.Italic());
            return EcaText.this;
        }

        public EcaText UNDERLINE() {
            effects.add(new TextEffect.Underline());
            return EcaText.this;
        }

        public EcaText STRIKETHROUGH() {
            effects.add(new TextEffect.Strikethrough());
            return EcaText.this;
        }
    }

    //合成组件，需在客户端调用（依赖系统时间做动画）
    public MutableComponent build() {
        if (text.isEmpty()) return Component.empty();

        //归集效果状态，颜色层取最后一个
        TextEffect colorEffect = null;
        List<TextEffect.Shimmer> shimmers = new ArrayList<>();
        List<TextEffect.Glitch>  glitches = new ArrayList<>();
        boolean bold = false, italic = false, underline = false, strikethrough = false;

        for (TextEffect effect : effects) {
            if (effect instanceof TextEffect.Gradient
                    || effect instanceof TextEffect.Rainbow
                    || effect instanceof TextEffect.Solid) {
                colorEffect = effect;
            } else if (effect instanceof TextEffect.Shimmer sh) {
                shimmers.add(sh);
            } else if (effect instanceof TextEffect.Glitch gl) {
                glitches.add(gl);
            } else if (effect instanceof TextEffect.Bold) {
                bold = true;
            } else if (effect instanceof TextEffect.Italic) {
                italic = true;
            } else if (effect instanceof TextEffect.Underline) {
                underline = true;
            } else if (effect instanceof TextEffect.Strikethrough) {
                strikethrough = true;
            }
        }

        int  len = text.length();
        long now = System.currentTimeMillis();

        final boolean fBold   = bold;
        final boolean fItalic = italic;
        final boolean fUnder  = underline;
        final boolean fStrike = strikethrough;

        MutableComponent result = Component.empty();

        for (int i = 0; i < len; i++) {
            char c = text.charAt(i);

            //基础颜色
            Integer charColor = null;
            if (colorEffect instanceof TextEffect.Gradient g) {
                charColor = computeGradient(i, len, now, g.periodMs(), g.colors());
            } else if (colorEffect instanceof TextEffect.Rainbow r) {
                charColor = computeRainbow(i, len, now, r.periodMs());
            } else if (colorEffect instanceof TextEffect.Solid s) {
                charColor = s.color();
            }

            //闪烁调制
            if (charColor != null) {
                for (TextEffect.Shimmer sh : shimmers) {
                    charColor = applyShimmer(charColor, i, now, sh.density());
                }
            }

            //乱码判定
            boolean glitched = false;
            for (TextEffect.Glitch gl : glitches) {
                if (isGlitched(i, now, gl.chance())) { glitched = true; break; }
            }

            Style style = Style.EMPTY;
            if (charColor  != null) style = style.withColor(TextColor.fromRgb(charColor));
            if (fBold)              style = style.withBold(true);
            if (fItalic)            style = style.withItalic(true);
            if (fUnder)             style = style.withUnderlined(true);
            if (fStrike)            style = style.withStrikethrough(true);
            if (glitched)           style = style.withObfuscated(true);

            result.append(Component.literal(String.valueOf(c)).setStyle(style));
        }

        return result;
    }

    //空间渐变 + 色带整体滑动，与旧 gradient 实现一致
    private static int computeGradient(int i, int len, long now, long periodMs, int[] colors) {
        int n = colors.length;
        if (n < 2) return colors[0];
        double phase   = (now % periodMs) / (double) periodMs;
        double shift   = (phase <= 0.5 ? phase * 2.0 : 2.0 - phase * 2.0);
        double charPos = (double) i / Math.max(len - 1, 1);
        double pos     = (charPos + shift * 0.5) * (n - 1);
        pos = Math.max(0, Math.min(pos, n - 1.0 - 1e-9));
        int idx = Math.min((int) pos, n - 2);
        return lerpColor(colors[idx], colors[idx + 1], pos - idx);
    }

    //彩虹：字符散布在 70% 色轮上，整体随时间旋转
    private static int computeRainbow(int i, int len, long now, long periodMs) {
        double phase   = (now % periodMs) / (double) periodMs;
        double charPos = (double) i / Math.max(len - 1, 1);
        float hue = (float) ((charPos * 0.7 + phase) % 1.0);
        return hsvToRgb(hue, 1.0f, 1.0f);
    }

    //按字符与时间窗口哈希决定是否变亮，窗口 120ms 避免逐帧闪
    private static int applyShimmer(int color, int i, long now, float density) {
        long window = now / 120L;
        long seed   = (long) i * 2654435769L ^ window * 1013904223L;
        seed ^= (seed >>> 17) ^ (seed >>> 31);
        float v = (seed & 0xFFFFFFFFL) / (float) 0x100000000L;
        if (v < density) {
            float t = 0.4f + (v / density) * 0.6f;
            return lerpColor(color, 0xFFFFFF, t);
        }
        return color;
    }

    //按字符与时间窗口哈希决定是否乱码，窗口 80ms
    private static boolean isGlitched(int i, long now, float chance) {
        long window = now / 80L;
        long seed   = ((long)(i + 1)) * 1234567891L ^ window * 987654321L;
        seed ^= (seed >>> 13) ^ (seed >>> 27);
        float v = (seed & 0xFFFFFFFFL) / (float) 0x100000000L;
        return v < chance;
    }

    private static int hsvToRgb(float h, float s, float v) {
        int   hi = (int)(h * 6) % 6;
        float f  = h * 6 - (int)(h * 6);
        float p  = v * (1 - s);
        float q  = v * (1 - f * s);
        float t  = v * (1 - (1 - f) * s);
        float r, g, b;
        switch (hi) {
            case 0  -> { r = v; g = t; b = p; }
            case 1  -> { r = q; g = v; b = p; }
            case 2  -> { r = p; g = v; b = t; }
            case 3  -> { r = p; g = q; b = v; }
            case 4  -> { r = t; g = p; b = v; }
            default -> { r = v; g = p; b = q; }
        }
        return ((int)(r * 255) << 16) | ((int)(g * 255) << 8) | (int)(b * 255);
    }

    private static int lerpColor(int c1, int c2, double t) {
        int r = (int)(((c1 >> 16) & 0xFF) * (1 - t) + ((c2 >> 16) & 0xFF) * t);
        int g = (int)(((c1 >>  8) & 0xFF) * (1 - t) + ((c2 >>  8) & 0xFF) * t);
        int b = (int)(( c1        & 0xFF) * (1 - t) + ( c2        & 0xFF) * t);
        return (r << 16) | (g << 8) | b;
    }
}
