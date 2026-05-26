package net.eca.coremod;

import net.eca.agent.AgentLogWriter;
import net.eca.agent.EcaAgent;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/*
 * 运行期字节码提供器：返回某个类「当前内存中、已被 mixin/coremod 等转换后」的字节码，
 * 而非磁盘上的原始 .class。用于让血量分析看见 mixin 对 getHealth 等方法的修改
 * (getResourceAsStream 只能读到磁盘原始字节，看不到加载期注入)。结果按类缓存。
 */
public final class RuntimeBytecodeProvider {

    private RuntimeBytecodeProvider() {}

    private static final Map<String, byte[]> CACHE = new ConcurrentHashMap<>();
    private static final byte[] NONE = new byte[0];

    //取该类运行期字节码；不可得时返回 null(调用方回退磁盘字节码)
    public static byte[] get(Class<?> clazz) {
        if (clazz == null) return null;
        String internal = clazz.getName().replace('.', '/');
        byte[] cached = CACHE.get(internal);
        if (cached != null) return cached == NONE ? null : cached;

        byte[] captured = capture(clazz, internal);
        CACHE.put(internal, captured == null ? NONE : captured);
        return captured;
    }

    private static byte[] capture(Class<?> clazz, String internal) {
        Instrumentation inst = EcaAgent.getInstrumentation();
        if (inst == null || !inst.isModifiableClass(clazz)) return null;

        byte[][] holder = new byte[1][];
        ClassFileTransformer capturer = new ClassFileTransformer() {
            @Override
            public byte[] transform(ClassLoader loader, String name, Class<?> beingRedefined,
                                    ProtectionDomain pd, byte[] buf) {
                //retransform 链跑完(含 mixin/coremod)后本捕获器拿到的 buf 即运行期字节码
                if (internal.equals(name) && buf != null && holder[0] == null) {
                    holder[0] = buf.clone();
                }
                return null;   // 只读不改
            }
        };

        try {
            inst.addTransformer(capturer, true);
            inst.retransformClasses(clazz);
        } catch (Throwable t) {
            AgentLogWriter.error("[RuntimeBytecode] capture failed: " + internal, t);
        } finally {
            inst.removeTransformer(capturer);
        }
        return holder[0];
    }
}
