package net.eca.util.item_extension;

//文字效果：颜色层（Gradient/Rainbow/Solid）互斥取最后一个，调制层与样式层叠加
public sealed interface TextEffect permits
        TextEffect.Gradient,
        TextEffect.Rainbow,
        TextEffect.Solid,
        TextEffect.Shimmer,
        TextEffect.Glitch,
        TextEffect.Bold,
        TextEffect.Italic,
        TextEffect.Underline,
        TextEffect.Strikethrough {

    //多色渐变，色带随时间滑动
    record Gradient(long periodMs, int[] colors) implements TextEffect {}

    //HSV 色轮彩虹
    record Rainbow(long periodMs) implements TextEffect {}

    //纯色
    record Solid(int color) implements TextEffect {}

    //随机字符周期性变亮
    record Shimmer(float density) implements TextEffect {}

    //随机字符替换为乱码 §k
    record Glitch(float chance) implements TextEffect {}

    record Bold() implements TextEffect {}
    record Italic() implements TextEffect {}
    record Underline() implements TextEffect {}
    record Strikethrough() implements TextEffect {}
}
