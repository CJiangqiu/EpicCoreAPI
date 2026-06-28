package net.eca.coremod;

import com.sun.jna.Callback;
import com.sun.jna.CallbackReference;
import com.sun.jna.Function;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;
import net.eca.agent.AgentLogWriter;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiFunction;

/*
 * JVM TI 原生通道——通过 JNA 直接操作 JVM TI 函数表，注册 ClassFileLoadHook 回调。
 * 回调运行在 Java Instrumentation API 之下的原生层，Java 代码无法拦截/篡改。
 *
 * 生命周期：
 *   prepare() → 获取 jvmtiEnv*（CoreMod 阶段，最早执行）
 *   activate() → 注册 ClassFileLoadHook（激进防御开启时调用）
 *   deactivate() → 注销回调
 *
 * 配置关闭时：prepare() 仍执行但仅保存指针（零开销），activate() 不调用（零回调）。
 * 配置开启时：ECA 的核心 transform 同时走 JVM TI 原生层 + Instrumentation Java 层，双通道互补。
 */
public final class JvmTiChannel {

    private static volatile Pointer jvmtiEnv;
    private static volatile boolean active;
    private static final List<BiFunction<String, byte[], byte[]>> transformFunctions =
            new CopyOnWriteArrayList<>();

    /* JVM TI 常量 */
    private static final int JVMTI_VERSION_1_2 = 0x30010200;
    private static final int JVMTI_EVENT_CLASS_FILE_LOAD_HOOK = 54;
    private static final int JVMTI_ENABLE = 1;
    private static final int JVMTI_DISABLE = 0;

    /* JNI 常量 */
    private static final int JNI_VERSION_1_6 = 0x00010006;

    /* JNI InvokeInterface vtable 索引 */
    private static final int JNI_GETENV_INDEX = 6;

    /* jvmtiInterface_1_ 函数表索引（0-based） */
    private static final int JVMTI_SET_EVENT_NOTIFICATION_MODE = 1;
    private static final int JVMTI_SET_EVENT_CALLBACKS = 121;
    private static final int JVMTI_ALLOCATE = 45;
    private static final int JVMTI_RETRANSFORM_CLASSES = 151;

    /* JNI 函数表索引（0-based） */
    private static final int JNI_FIND_CLASS = 6;

    /* ClassFileLoadHook 在 jvmtiEventCallbacks 结构体中的字段偏移（字段数） */
    private static final int CLASS_FILE_LOAD_HOOK_FIELD_INDEX = 4;

    private static volatile Pointer javaVM;
    private static volatile ClassFileLoadHookCallback activeCallback;

    private JvmTiChannel() {}

    // ==================== 公共 API ====================

    /* 获取 JVM TI 环境指针。仅在 CoreMod 静态初始化阶段调用一次。
       不注册回调，不产生任何运行时开销。 */
    public static void prepare() {
        try {
            Function getCreatedVMs = Function.getFunction("jvm", "JNI_GetCreatedJavaVMs");
            Pointer[] vmBuf = new Pointer[1];
            IntByReference count = new IntByReference();
            int jniResult = getCreatedVMs.invokeInt(new Object[]{vmBuf, 1, count.getPointer()});
            if (jniResult != 0 || vmBuf[0] == null) {
                AgentLogWriter.info("[JvmTiChannel] JNI_GetCreatedJavaVMs failed, code=" + jniResult);
                return;
            }

            javaVM = vmBuf[0];
            Pointer functions = javaVM.getPointer(0);
            int ptrSize = Native.POINTER_SIZE;
            Pointer getEnvPtr = functions.getPointer(JNI_GETENV_INDEX * ptrSize);
            Function getEnv = Function.getFunction(getEnvPtr, Function.C_CONVENTION);

            Pointer[] envBuf = new Pointer[1];
            int result = getEnv.invokeInt(new Object[]{javaVM, envBuf, JVMTI_VERSION_1_2});
            if (result != 0 || envBuf[0] == null) {
                AgentLogWriter.info("[JvmTiChannel] GetEnv failed, code=" + result);
                return;
            }

            jvmtiEnv = envBuf[0];
            AgentLogWriter.info("[JvmTiChannel] JVM TI environment acquired");
        } catch (Throwable t) {
            AgentLogWriter.info("[JvmTiChannel] prepare failed: " + t.getMessage());
        }
    }

    /* 注册 transform 函数供 JVM TI 回调调用。
       每个需要 JVM TI 保护的组件独立注册自己的 transform 逻辑。
       fn 签名为 (className, classfileBuffer) → transformedBytes 或 null（不变换）。
       回调按注册顺序依次调用；任一函数返回非 null 即视为已变换，后续函数接收变换后的字节码。 */
    public static void addTransformFunction(BiFunction<String, byte[], byte[]> fn) {
        if (fn != null) transformFunctions.add(fn);
    }

    /* 注册 ClassFileLoadHook 回调，激活 JVM TI 原生变换通道。
       仅在激进防御配置开启时调用。 */
    public static void activate() {
        if (jvmtiEnv == null) {
            AgentLogWriter.info("[JvmTiChannel] activate skipped: no JVM TI env");
            return;
        }
        if (active) return;

        try {
            ClassFileLoadHookCallback callback = new ClassFileLoadHookCallback();
            Pointer callbacksStruct = buildCallbacksStruct(callback);

            int ptrSize = Native.POINTER_SIZE;
            Pointer setCallbacksPtr = jvmtiEnv.getPointer(JVMTI_SET_EVENT_CALLBACKS * ptrSize);
            Function setCallbacks = Function.getFunction(setCallbacksPtr, Function.C_CONVENTION);
            int result = setCallbacks.invokeInt(new Object[]{
                    jvmtiEnv, callbacksStruct,
                    Integer.valueOf(callbacksStructSize())
            });
            if (result != 0) {
                AgentLogWriter.info("[JvmTiChannel] SetEventCallbacks failed, code=" + result);
                return;
            }

            Pointer setNotifyPtr = jvmtiEnv.getPointer(JVMTI_SET_EVENT_NOTIFICATION_MODE * ptrSize);
            Function setNotify = Function.getFunction(setNotifyPtr, Function.C_CONVENTION);
            result = setNotify.invokeInt(new Object[]{
                    jvmtiEnv, JVMTI_ENABLE, JVMTI_EVENT_CLASS_FILE_LOAD_HOOK, Pointer.NULL
            });
            if (result != 0) {
                AgentLogWriter.info("[JvmTiChannel] SetEventNotificationMode failed, code=" + result);
                return;
            }

            activeCallback = callback;
            active = true;
            AgentLogWriter.info("[JvmTiChannel] ClassFileLoadHook activated");
        } catch (Throwable t) {
            AgentLogWriter.info("[JvmTiChannel] activate failed: " + t.getMessage());
        }
    }

    /* 注销 ClassFileLoadHook 回调。 */
    public static void deactivate() {
        if (jvmtiEnv == null || !active) return;

        try {
            int ptrSize = Native.POINTER_SIZE;
            Pointer setNotifyPtr = jvmtiEnv.getPointer(JVMTI_SET_EVENT_NOTIFICATION_MODE * ptrSize);
            Function setNotify = Function.getFunction(setNotifyPtr, Function.C_CONVENTION);
            setNotify.invokeInt(new Object[]{
                    jvmtiEnv, JVMTI_DISABLE, JVMTI_EVENT_CLASS_FILE_LOAD_HOOK, Pointer.NULL
            });

            activeCallback = null;
            active = false;
            AgentLogWriter.info("[JvmTiChannel] ClassFileLoadHook deactivated");
        } catch (Throwable t) {
            AgentLogWriter.info("[JvmTiChannel] deactivate failed: " + t.getMessage());
        }
    }

    public static boolean isActive() {
        return active;
    }

    /* 通过 JVM TI 原生 RetransformClasses 重转换类。
       全部参数为 Java Class 对象，内部通过 JNI FindClass 转为 jclass 指针，
       完全绕过 InstrumentationImpl。失败时静默跳过（记日志）。 */
    public static void retransformClasses(Class<?>... classes) {
        if (!active || jvmtiEnv == null || classes == null || classes.length == 0) return;

        int ptrSize = Native.POINTER_SIZE;
        com.sun.jna.Memory jclassArray = new com.sun.jna.Memory((long) classes.length * ptrSize);
        int validCount = 0;

        for (int i = 0; i < classes.length; i++) {
            String internalName = classes[i].getName().replace('.', '/');
            Pointer jclass = findClass(internalName);
            if (jclass != null) {
                jclassArray.setPointer((long) validCount * ptrSize, jclass);
                validCount++;
            }
        }

        if (validCount == 0) return;

        try {
            Pointer retransformPtr = jvmtiEnv.getPointer(JVMTI_RETRANSFORM_CLASSES * ptrSize);
            Function retransform = Function.getFunction(retransformPtr, Function.C_CONVENTION);
            int result = retransform.invokeInt(new Object[]{jvmtiEnv, validCount, jclassArray});
            if (result == 0) {
                AgentLogWriter.info("[JvmTiChannel] Retransformed " + validCount + " classes via JVM TI");
            } else {
                AgentLogWriter.info("[JvmTiChannel] RetransformClasses failed, code=" + result);
            }
        } catch (Throwable t) {
            AgentLogWriter.info("[JvmTiChannel] retransformClasses error: " + t.getMessage());
        }
    }

    // ==================== JNI 工具 ====================

    /* 获取当前线程的 JNIEnv* */
    private static Pointer getJniEnv() {
        if (javaVM == null) return null;
        try {
            Pointer functions = javaVM.getPointer(0);
            int ptrSize = Native.POINTER_SIZE;
            Pointer getEnvPtr = functions.getPointer(JNI_GETENV_INDEX * ptrSize);
            Function getEnv = Function.getFunction(getEnvPtr, Function.C_CONVENTION);
            Pointer[] envBuf = new Pointer[1];
            int result = getEnv.invokeInt(new Object[]{javaVM, envBuf, JNI_VERSION_1_6});
            if (result != 0 || envBuf[0] == null) return null;
            return envBuf[0];
        } catch (Throwable t) {
            return null;
        }
    }

    /* 通过 JNI FindClass 按内部名查找 jclass 句柄 */
    private static Pointer findClass(String internalName) {
        Pointer jniEnv = getJniEnv();
        if (jniEnv == null) return null;
        try {
            int ptrSize = Native.POINTER_SIZE;
            Pointer functions = jniEnv.getPointer(0);
            Pointer findClassPtr = functions.getPointer(JNI_FIND_CLASS * ptrSize);
            Function findClass = Function.getFunction(findClassPtr, Function.C_CONVENTION);
            Object result = findClass.invoke(Pointer.class, new Object[]{jniEnv, internalName});
            return (Pointer) result;
        } catch (Throwable t) {
            return null;
        }
    }

    // ==================== 内部实现 ====================

    /* 构建 jvmtiEventCallbacks 结构体：除 ClassFileLoadHook 外全部填 NULL。
       使用堆外内存手动布局，避免定义 JNA Structure（字段太多且跨 JDK 版本差异大）。 */
    private static Pointer buildCallbacksStruct(ClassFileLoadHookCallback callback) {
        int structSize = callbacksStructSize();
        Pointer struct = new com.sun.jna.Memory(structSize);
        struct.clear(structSize);                         // 全部清零

        int cbOffset = CLASS_FILE_LOAD_HOOK_FIELD_INDEX * Native.POINTER_SIZE;
        struct.setPointer(cbOffset, CallbackReference.getFunctionPointer(callback));

        return struct;
    }

    /* jvmtiEventCallbacks 结构体大小（字节）。
       使用 512 字节覆盖 JDK 17 所有回调字段；SetEventCallbacks 会忽略超出的尾部。 */
    private static int callbacksStructSize() {
        return 512;
    }

    /* 通过 JVM TI Allocate 分配原生内存，供 ClassFileLoadHook 返回变换后的字节码。
       JVM 拥有此内存的所有权并在回调返回后自动释放。 */
    private static Pointer jvmtiAllocate(long size) {
        try {
            int ptrSize = Native.POINTER_SIZE;
            Pointer allocPtr = jvmtiEnv.getPointer(JVMTI_ALLOCATE * ptrSize);
            Function alloc = Function.getFunction(allocPtr, Function.C_CONVENTION);
            Pointer[] memBuf = new Pointer[1];
            int result = alloc.invokeInt(new Object[]{jvmtiEnv, Long.valueOf(size), memBuf});
            if (result != 0 || memBuf[0] == null) return null;
            return memBuf[0];
        } catch (Throwable t) {
            return null;
        }
    }

    // ==================== JNA 回调实现 ====================

    /* JVM TI ClassFileLoadHook 回调。
       签名须与 jvmtiEventClassFileLoadHook typedef 完全一致。
       所有参数均为原生指针/基本类型；不抛异常（回调上下文不可恢复）。 */
    private static class ClassFileLoadHookCallback implements Callback {

        @SuppressWarnings("unused") // JNA 通过反射调用此方法
        public void callback(
                Pointer jvmti_env,
                Pointer jni_env,
                Pointer classBeingRedefined,
                Pointer loader,
                String name,
                Pointer protectionDomain,
                int classDataLen,
                Pointer classData,
                Pointer newClassDataLen,
                Pointer newClassData
        ) {
            if (name == null || classData == null) return;
            if (transformFunctions.isEmpty()) return;

            try {
                String className = name.replace('.', '/');
                byte[] current = classData.getByteArray(0, classDataLen);
                boolean anyTransformed = false;

                for (BiFunction<String, byte[], byte[]> fn : transformFunctions) {
                    byte[] result = fn.apply(className, current);
                    if (result != null && result != current) {
                        current = result;
                        anyTransformed = true;
                    }
                }

                if (anyTransformed) {
                    Pointer buf = jvmtiAllocate(current.length);
                    if (buf != null) {
                        buf.write(0, current, 0, current.length);
                        newClassDataLen.setInt(0, current.length);
                        newClassData.setPointer(0, buf);
                    }
                }
            } catch (Throwable ignored) {
                // 回调内任何异常都必须吞噬，否则会崩溃 JVM
            }
        }

    }
}
