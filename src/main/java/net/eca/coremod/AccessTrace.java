package net.eca.coremod;

import java.util.ArrayList;
import java.util.List;

/*
 * 运行时访问轨迹记录器：被 AccessProbeTransformer 注入的字节码在每次字段/数组读取后调用，
 * 记录 getHealth 执行期间实际读取的 (容器对象, 槽位, 值)。动态分析靠它穿透 MethodHandle /
 * 不透明容器 / 反射过滤——因为这里观测的是真实执行，不是字节码符号。
 * 必须与 LivingEntityHook 同包：被插桩的 mod 类要能从自身 ClassLoader 解析到这些静态方法。
 * 只在 begin()/end() 之间的线程窗口内记录，避免常态开销。
 */
public final class AccessTrace {

    private AccessTrace() {}

    public static final class Entry {
        public final String site;      // 读取点描述："ownerClass#method owner.field:desc" 或 "...#method []"
        public final Object container;  // 容器对象（静态字段读为 null；数组读为数组对象）
        public final long index;        // 数组下标（字段读为 -1）
        public final Object value;      // 读到的值（基本类型已装箱）
        Entry(String site, Object container, long index, Object value) {
            this.site = site;
            this.container = container;
            this.index = index;
            this.value = value;
        }
    }

    //方法入口记录：用于通用求解器 seed 分析解码方法(拿到真实 this + 实参，如解码方法的密钥参数)
    public static final class MethodEntry {
        public final String site;      // "ownerClass#method"
        public final Object receiver;   // 实例方法的 this；静态方法为 null
        public final Object[] args;     // 实参（基本类型已装箱）
        MethodEntry(String site, Object receiver, Object[] args) {
            this.site = site;
            this.receiver = receiver;
            this.args = args;
        }
    }

    private static final ThreadLocal<List<Entry>> READS = new ThreadLocal<>();
    private static final ThreadLocal<List<MethodEntry>> METHODS = new ThreadLocal<>();

    //开始记录（本线程）
    public static void begin() {
        READS.set(new ArrayList<>());
        METHODS.set(new ArrayList<>());
    }

    public static boolean isActive() {
        return READS.get() != null;
    }

    //取回本线程当前的读取轨迹（不清空，需在 finish 前调用）
    public static List<Entry> reads() {
        List<Entry> b = READS.get();
        return b == null ? new ArrayList<>() : b;
    }

    //取回本线程当前的方法入口轨迹
    public static List<MethodEntry> methods() {
        List<MethodEntry> m = METHODS.get();
        return m == null ? new ArrayList<>() : m;
    }

    //结束记录，清空本线程缓冲
    public static void finish() {
        READS.remove();
        METHODS.remove();
    }

    //被注入字节码调用：方法入口
    public static void enter(String site, Object receiver, Object[] args) {
        List<MethodEntry> m = METHODS.get();
        if (m != null) {
            m.add(new MethodEntry(site, receiver, args));
        }
    }

    private static void add(String site, Object container, long index, Object value) {
        List<Entry> b = READS.get();
        if (b != null) {
            b.add(new Entry(site, container, index, value));
        }
    }

    // ==================== 字段读（实例）====================
    public static void fieldO(Object c, Object v, String s) { add(s, c, -1, v); }
    public static void fieldI(Object c, int v, String s)    { add(s, c, -1, v); }
    public static void fieldJ(Object c, long v, String s)   { add(s, c, -1, v); }
    public static void fieldF(Object c, float v, String s)  { add(s, c, -1, v); }
    public static void fieldD(Object c, double v, String s) { add(s, c, -1, v); }

    // ==================== 字段读（静态，无容器）====================
    public static void staticO(Object v, String s) { add(s, null, -1, v); }
    public static void staticI(int v, String s)     { add(s, null, -1, v); }
    public static void staticJ(long v, String s)    { add(s, null, -1, v); }
    public static void staticF(float v, String s)   { add(s, null, -1, v); }
    public static void staticD(double v, String s)  { add(s, null, -1, v); }

    // ==================== 数组读 ====================
    public static void arrO(Object a, int i, Object v, String s) { add(s, a, i, v); }
    public static void arrI(Object a, int i, int v, String s)    { add(s, a, i, v); }
    public static void arrJ(Object a, int i, long v, String s)   { add(s, a, i, v); }
    public static void arrF(Object a, int i, float v, String s)  { add(s, a, i, v); }
    public static void arrD(Object a, int i, double v, String s) { add(s, a, i, v); }
}
