package net.eca.coremod;

import org.objectweb.asm.ClassReader;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/*
 * 运行期字节码提供器：类首次加载与重转换时由永久捕获器自动缓存主转换链输出，
 * get() 直接从缓存返回，不触发 retransform，消除按需捕获造成的卡顿。
 * 捕获器由 EcaClassTransformer 的 register()/init() 在 transformer 注册后、retransformLoadedClasses 前注册，
 * 确保已加载类的批量重转换也能被截获；按需末端健康变换由独立回执器验证。
 *
 * 激进防御开启时额外注册 JVM TI 捕获函数（排在 transformFunctions 列表末尾），
 * 使 JVM TI 层的变换结果也进入缓存。
 */
public final class RuntimeBytecodeProvider {

    private RuntimeBytecodeProvider() {}

    private static final Map<String, byte[]> RUNTIME_BYTES = new ConcurrentHashMap<>();
    private static final Map<String, byte[]> ANALYSIS_BYTES = new ConcurrentHashMap<>();
    private static volatile boolean captureRegistered = false;
    private static volatile boolean jvmTiRegistered = false;

    // 注册永久捕获器：截获主转换器之后的运行期字节码，并使该类旧健康变换回执失效
    public static void registerPermanentCapture(Instrumentation inst) {
        if (captureRegistered) return;
        captureRegistered = true;
        inst.addTransformer(new ClassFileTransformer() {
            @Override
            public byte[] transform(ClassLoader loader, String name,
                    Class<?> beingRedefined, ProtectionDomain pd, byte[] buf) {
                capture(name, buf);
                return null;   // 只读不改
            }
        }, true);   // 支持 retransform，使已加载类批量重转换时也能截获
    }

    /* 注册 JVM TI 层字节码捕获函数（排在列表末尾，接收前序变换后的最终字节码）。
       激进防御激活时由 EcaMod 调用。 */
    public static void registerJvmTiCapture() {
        if (jvmTiRegistered) return;
        jvmTiRegistered = true;
        JvmTiChannel.addTransformFunction(RuntimeBytecodeProvider::captureStatic);
    }

    /* 供 JVM TI 回调调用的静态捕获函数——仅捕获，不修改字节码 */
    static byte[] captureStatic(String className, byte[] bytes) {
        capture(className, bytes);
        return null;   // 只读不改
    }

    /* ECA 转换器入口已经包含先于 ECA 执行的外部转换，且尚未混入 ECA 自己的 hook。 */
    public static void captureAnalysisInput(String className, byte[] bytes) {
        capture(ANALYSIS_BYTES, className, bytes, false);
    }

    /* 隐藏类的 JVM TI 名称可为空；以 classfile 内部名建立稳定别名，供 /0x... 运行时类名回查。 */
    private static void capture(String className, byte[] bytes) {
        capture(RUNTIME_BYTES, className, bytes, true);
    }

    private static void capture(Map<String, byte[]> target, String className, byte[] bytes, boolean replace) {
        if (bytes == null) return;
        try {
            String internalName = className;
            if (internalName == null || internalName.isEmpty()) {
                internalName = new ClassReader(bytes).getClassName();
            }
            if (internalName == null || internalName.isEmpty()) return;
            EcaTransformerManager.invalidateHealthTransformReceipt(internalName);
            byte[] copy = bytes.clone();
            put(target, internalName.replace('.', '/'), copy, replace);
            int hiddenSuffix = internalName.indexOf("/0x");
            if (hiddenSuffix > 0) put(target, internalName.substring(0, hiddenSuffix), copy, replace);
        } catch (Throwable ignored) {
            // 捕获器不能因异常影响类定义；调用方会回退到其他字节码来源。
        }
    }

    private static void put(Map<String, byte[]> target, String key, byte[] bytes, boolean replace) {
        if (replace) target.put(key, bytes);
        else target.putIfAbsent(key, bytes);
    }

    //取该类运行期字节码；未缓存时返回 null(调用方回退磁盘字节码)
    public static byte[] get(Class<?> clazz) {
        return get(RUNTIME_BYTES, clazz);
    }

    // 协议分析只能读取 ECA 注入前的视图，避免把健康锁等自身状态识别成实体协议。
    public static byte[] getAnalysis(Class<?> clazz) {
        return get(ANALYSIS_BYTES, clazz);
    }

    private static byte[] get(Map<String, byte[]> source, Class<?> clazz) {
        if (clazz == null) return null;
        String internalName = clazz.getName().replace('.', '/');
        byte[] bytes = source.get(internalName);
        if (bytes != null) return bytes;
        int hiddenSuffix = internalName.indexOf("/0x");
        return hiddenSuffix > 0 ? source.get(internalName.substring(0, hiddenSuffix)) : null;
    }
}
