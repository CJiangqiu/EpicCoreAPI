package net.eca.coremod;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/*
 * 运行期字节码提供器：类首次加载时由永久捕获器(排在 transformer 链末尾)自动缓存最终字节码，
 * get() 直接从缓存返回，不触发 retransform，消除按需捕获造成的卡顿。
 * 捕获器由 EcaClassTransformer.init() 在自身注册后、retransformLoadedClasses 前注册，
 * 确保已加载类的批量重转换也能被截获。
 */
public final class RuntimeBytecodeProvider {

    private RuntimeBytecodeProvider() {}

    private static final Map<String, byte[]> RUNTIME_BYTES = new ConcurrentHashMap<>();
    private static volatile boolean captureRegistered = false;

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

    //取该类运行期字节码；未缓存时返回 null(调用方回退磁盘字节码)
    public static byte[] get(Class<?> clazz) {
        if (clazz == null) return null;
        return RUNTIME_BYTES.get(clazz.getName().replace('.', '/'));
    }
}
