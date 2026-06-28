package net.eca.coremod;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/*
 * 运行期字节码提供器：类首次加载时由永久捕获器(排在 transformer 链末尾)自动缓存最终字节码，
 * get() 直接从缓存返回，不触发 retransform，消除按需捕获造成的卡顿。
 * 捕获器由 EcaClassTransformer 的 register()/init() 在 transformer 注册后、retransformLoadedClasses 前注册，
 * 确保已加载类的批量重转换也能被截获。
 *
 * 激进防御开启时额外注册 JVM TI 捕获函数（排在 transformFunctions 列表末尾），
 * 使 JVM TI 层的变换结果也进入缓存。
 */
public final class RuntimeBytecodeProvider {

    private RuntimeBytecodeProvider() {}

    private static final Map<String, byte[]> RUNTIME_BYTES = new ConcurrentHashMap<>();
    private static volatile boolean captureRegistered = false;
    private static volatile boolean jvmTiRegistered = false;

    //注册永久捕获器：排在链尾，所有类加载/retransform 时自动截获 post-all-transforms(含 Mixin)最终字节码
    public static void registerPermanentCapture(Instrumentation inst) {
        if (captureRegistered) return;
        captureRegistered = true;
        inst.addTransformer(new ClassFileTransformer() {
            @Override
            public byte[] transform(ClassLoader loader, String name,
                    Class<?> beingRedefined, ProtectionDomain pd, byte[] buf) {
                if (name != null && buf != null) {
                    RUNTIME_BYTES.putIfAbsent(name, buf.clone());
                }
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
        if (className != null && bytes != null) {
            RUNTIME_BYTES.putIfAbsent(className, bytes.clone());
        }
        return null;   // 只读不改
    }

    //取该类运行期字节码；未缓存时返回 null(调用方回退磁盘字节码)
    public static byte[] get(Class<?> clazz) {
        if (clazz == null) return null;
        return RUNTIME_BYTES.get(clazz.getName().replace('.', '/'));
    }
}
