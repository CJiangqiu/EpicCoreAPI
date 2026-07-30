package net.eca.coremod;

import com.sun.jna.Callback;
import com.sun.jna.CallbackReference;
import com.sun.jna.Function;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;
import net.eca.agent.AgentLogWriter;
import org.objectweb.asm.ClassReader;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiFunction;

/*
 * JVM TI 原生通道——通过 JNA 直接操作 JVM TI 函数表，注册 ClassFileLoadHook 回调。
 * 回调运行在 Java Instrumentation API 之下的原生层，Java 代码无法拦截/篡改。
 *
 * 生命周期：
 *   prepare(boolean) → 获取 jvmtiEnv*，并按需注册 ClassPrepare 引用收集器（CoreMod 阶段，最早执行）
 *   activate() → 保留收集器并注册 ClassFileLoadHook（激进防御开启时调用）
 *   deactivate() → 注销字节码变换回调，保留类引用收集器
 *
 * 配置关闭时：prepare() 仅保存环境指针，不注册回调。
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
    private static final int JVMTI_EVENT_CLASS_PREPARE = 56;
    private static final int JVMTI_ENABLE = 1;
    private static final int JVMTI_DISABLE = 0;

    /* JNI 常量 */
    private static final int JNI_VERSION_1_6 = 0x00010006;

    /* JNI InvokeInterface vtable 索引 */
    private static final int JNI_GETENV_INDEX = 6;

    /* jvmtiInterface_1_ 函数表索引（0-based，= 规范函数号 - 1） */
    private static final int JVMTI_SET_EVENT_NOTIFICATION_MODE = 1;
    private static final int JVMTI_SET_EVENT_CALLBACKS = 121;
    private static final int JVMTI_ALLOCATE = 45;
    private static final int JVMTI_DEALLOCATE = 46;          // 规范函数 47 Deallocate
    private static final int JVMTI_GET_CLASS_SIGNATURE = 47; // 规范函数 48 GetClassSignature
    private static final int JVMTI_IS_MODIFIABLE_CLASS = 44; // 规范函数 45 IsModifiableClass
    private static final int JVMTI_ADD_CAPABILITIES = 141;   // 规范函数 142 AddCapabilities
    private static final int JVMTI_GET_CAPABILITIES = 88;    // 规范函数 89 GetCapabilities（注意：并非紧邻 AddCapabilities）
    private static final int JVMTI_RETRANSFORM_CLASSES = 151;

    /* jvmtiCapabilities 位偏移（bit 序号，见 JVMTI 规范结构体字段顺序） */
    private static final int CAP_CAN_REDEFINE_CLASSES = 9;
    private static final int CAP_CAN_GENERATE_ALL_CLASS_HOOK_EVENTS = 26;
    private static final int CAP_CAN_RETRANSFORM_CLASSES = 37;
    /* jvmtiCapabilities 结构体字节数（规范定义为一组位域，实占 16 字节；用 16 字节覆盖 JDK17 布局） */
    private static final int CAPABILITIES_STRUCT_SIZE = 16;

    /* JNI 函数表索引（0-based） */
    private static final int JNI_IS_ASSIGNABLE_FROM = 11;
    private static final int JNI_NEW_GLOBAL_REF = 21;
    private static final int JNI_DELETE_GLOBAL_REF = 22;

    /* ClassFileLoadHook 在 jvmtiEventCallbacks 结构体中的字段偏移（字段数） */
    private static final int CLASS_FILE_LOAD_HOOK_FIELD_INDEX = 4;
    private static final int CLASS_PREPARE_FIELD_INDEX = 6;

    private static final String PREPARED_CLASSES_KEY = "net.eca.coremod.JvmTiChannel.preparedClasses";
    private static volatile ConcurrentMap<String, Long> preparedClasses;

    private static volatile Pointer javaVM;
    private static volatile ClassFileLoadHookCallback activeCallback;
    private static volatile ClassPrepareCallback classPrepareCallback;

    private JvmTiChannel() {}

    @SuppressWarnings("unchecked")
    private static ConcurrentMap<String, Long> preparedClasses() {
        ConcurrentMap<String, Long> local = preparedClasses;
        if (local != null) return local;
        synchronized (System.getProperties()) {
            Object existing = System.getProperties().get(PREPARED_CLASSES_KEY);
            if (existing instanceof ConcurrentMap<?, ?> map) {
                preparedClasses = (ConcurrentMap<String, Long>) map;
                return preparedClasses;
            }
            ConcurrentMap<String, Long> created = new ConcurrentHashMap<>();
            System.getProperties().put(PREPARED_CLASSES_KEY, created);
            preparedClasses = created;
            return preparedClasses;
        }
    }

    @SuppressWarnings("unchecked")
    private static ConcurrentMap<String, Long> existingPreparedClasses() {
        ConcurrentMap<String, Long> local = preparedClasses;
        if (local != null) return local;
        Object existing = System.getProperties().get(PREPARED_CLASSES_KEY);
        if (existing instanceof ConcurrentMap<?, ?> map) {
            preparedClasses = (ConcurrentMap<String, Long>) map;
            return preparedClasses;
        }
        return null;
    }

    public record LoadedClassInfo(String internalName, boolean modifiable,
                                  boolean livingEntity, boolean entityOnly) {}

    @FunctionalInterface
    public interface LoadedClassMatcher {
        boolean test(LoadedClassInfo info);
    }

    @FunctionalInterface
    public interface LoadedClassConsumer {
        void accept(LoadedClassInfo info);
    }

    // ==================== 公共 API ====================

    /* 获取 JVM TI 环境指针，不启用类引用收集。 */
    public static void prepare() {
        prepare(false);
    }

    public static void prepare(boolean collectPreparedClasses) {
        if (jvmtiEnv != null) {
            if (collectPreparedClasses) activateClassCollector();
            return;
        }
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

            // 申请 retransform/redefine/all-class-hook 能力，并回读确认 live phase 是否授予
            addAndLogCapabilities();
            if (collectPreparedClasses) activateClassCollector();
        } catch (Throwable t) {
            AgentLogWriter.info("[JvmTiChannel] prepare failed: " + t.getMessage());
        }
    }

    /* jvmtiEnv 是"指向函数表指针的指针"：调用第 index 个函数须先解引用一层拿到函数表，再按索引取函数指针。
       现有 activate/retransformClasses 等历史代码漏了这层解引用（此前 env 恒为 null 从未执行到，bug 潜伏）。 */
    private static Function jvmtiFunction(int index) {
        Pointer functions = jvmtiEnv.getPointer(0);
        Pointer fn = functions.getPointer((long) index * Native.POINTER_SIZE);
        return Function.getFunction(fn, Function.C_CONVENTION);
    }

    /* ClassPrepare 回调在 CoreMod 阶段启用，使每个 jclass 都能在其 JNI 局部引用有效时提升为全局引用。 */
    private static void activateClassCollector() {
        if (jvmtiEnv == null || classPrepareCallback != null) return;
        try {
            ClassPrepareCallback prepareCallback = new ClassPrepareCallback();
            Pointer callbacksStruct = buildCallbacksStruct(null, prepareCallback);
            Function setCallbacks = jvmtiFunction(JVMTI_SET_EVENT_CALLBACKS);
            int callbackCode = setCallbacks.invokeInt(new Object[]{
                    jvmtiEnv, callbacksStruct, Integer.valueOf(callbacksStructSize())
            });
            if (callbackCode != 0) {
                AgentLogWriter.info("[JvmTiChannel] ClassPrepare SetEventCallbacks failed, code=" + callbackCode);
                return;
            }
            Function setNotify = jvmtiFunction(JVMTI_SET_EVENT_NOTIFICATION_MODE);
            int notifyCode = setNotify.invokeInt(new Object[]{
                    jvmtiEnv, JVMTI_ENABLE, JVMTI_EVENT_CLASS_PREPARE, Pointer.NULL
            });
            if (notifyCode != 0) {
                AgentLogWriter.info("[JvmTiChannel] ClassPrepare notification failed, code=" + notifyCode);
                return;
            }
            classPrepareCallback = prepareCallback;
            AgentLogWriter.info("[JvmTiChannel] ClassPrepare global-reference collector activated");
        } catch (Throwable t) {
            AgentLogWriter.info("[JvmTiChannel] ClassPrepare collector failed: " + t.getMessage());
        }
    }

    /* 申请 can_retransform_classes / can_redefine_classes / can_generate_all_class_hook_events，
       记录 AddCapabilities 返回码，并用 GetCapabilities 回读三个关键 bit 自校验。
       诊断用途：判定当前 JVM 的 live phase 是否允许这些能力（规范允许实现仅在 OnLoad phase 授予）。 */
    private static void addAndLogCapabilities() {
        if (jvmtiEnv == null) return;
        try {
            Pointer request = new Memory(CAPABILITIES_STRUCT_SIZE);
            request.clear(CAPABILITIES_STRUCT_SIZE);
            setCapabilityBit(request, CAP_CAN_RETRANSFORM_CLASSES);
            setCapabilityBit(request, CAP_CAN_REDEFINE_CLASSES);
            setCapabilityBit(request, CAP_CAN_GENERATE_ALL_CLASS_HOOK_EVENTS);

            Function add = jvmtiFunction(JVMTI_ADD_CAPABILITIES);
            int addCode = add.invokeInt(new Object[]{jvmtiEnv, request});
            AgentLogWriter.info("[JvmTiChannel] AddCapabilities code=" + addCode);

            Pointer current = new Memory(CAPABILITIES_STRUCT_SIZE);
            current.clear(CAPABILITIES_STRUCT_SIZE);
            Function get = jvmtiFunction(JVMTI_GET_CAPABILITIES);
            int getCode = get.invokeInt(new Object[]{jvmtiEnv, current});
            if (getCode == 0) {
                AgentLogWriter.info("[JvmTiChannel] GetCapabilities: retransform="
                        + getCapabilityBit(current, CAP_CAN_RETRANSFORM_CLASSES)
                        + ", redefine=" + getCapabilityBit(current, CAP_CAN_REDEFINE_CLASSES)
                        + ", allClassHook=" + getCapabilityBit(current, CAP_CAN_GENERATE_ALL_CLASS_HOOK_EVENTS));
            } else {
                AgentLogWriter.info("[JvmTiChannel] GetCapabilities failed, code=" + getCode);
            }
        } catch (Throwable t) {
            AgentLogWriter.info("[JvmTiChannel] addAndLogCapabilities failed: " + t.getMessage());
        }
    }

    /* 在 jvmtiCapabilities 位域结构体中置位（小端：bit n 落在 byte n/8 的第 n%8 位） */
    private static void setCapabilityBit(Pointer struct, int bit) {
        int byteIndex = bit / 8;
        int bitInByte = bit % 8;
        byte b = struct.getByte(byteIndex);
        struct.setByte(byteIndex, (byte) (b | (1 << bitInByte)));
    }

    private static boolean getCapabilityBit(Pointer struct, int bit) {
        int byteIndex = bit / 8;
        int bitInByte = bit % 8;
        return (struct.getByte(byteIndex) & (1 << bitInByte)) != 0;
    }

    /* 用 ClassPrepare 阶段保存的全局引用验证重转换，避免跨 JNA 调用复用 JVMTI 返回的局部引用。 */
    public static void verifyRetransformViaLoadedClasses(String targetSignature) {
        if (jvmtiEnv == null || targetSignature == null) {
            AgentLogWriter.info("[JvmTiChannel] verify prepared class skipped: unavailable target");
            return;
        }
        String internalName = targetSignature.startsWith("L") && targetSignature.endsWith(";")
                ? targetSignature.substring(1, targetSignature.length() - 1) : targetSignature;
        ConcurrentMap<String, Long> classes = existingPreparedClasses();
        Long pointerValue = classes == null ? null : classes.get(internalName);
        if (pointerValue == null || pointerValue == 0L) {
            AgentLogWriter.info("[JvmTiChannel] prepared class not found: " + targetSignature);
            return;
        }
        boolean result = retransformOne(new Pointer(pointerValue), "prepared verification");
        AgentLogWriter.info("[JvmTiChannel] prepared verification result=" + result + " target=" + targetSignature);
    }

    /* JVMTI Deallocate：释放 GetClassSignature 等函数分配的原生内存 */
    private static void deallocate(Pointer mem) {
        if (mem == null) return;
        try {
            jvmtiFunction(JVMTI_DEALLOCATE).invokeInt(new Object[]{jvmtiEnv, mem});
        } catch (Throwable ignored) {}
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
            activateClassCollector();
            ClassFileLoadHookCallback callback = new ClassFileLoadHookCallback();
            ClassPrepareCallback prepareCallback = classPrepareCallback;
            if (prepareCallback == null) {
                prepareCallback = new ClassPrepareCallback();
            }
            Pointer callbacksStruct = buildCallbacksStruct(callback, prepareCallback);

            Function setCallbacks = jvmtiFunction(JVMTI_SET_EVENT_CALLBACKS);
            int result = setCallbacks.invokeInt(new Object[]{
                    jvmtiEnv, callbacksStruct,
                    Integer.valueOf(callbacksStructSize())
            });
            if (result != 0) {
                AgentLogWriter.info("[JvmTiChannel] SetEventCallbacks failed, code=" + result);
                return;
            }

            Function setNotify = jvmtiFunction(JVMTI_SET_EVENT_NOTIFICATION_MODE);
            result = setNotify.invokeInt(new Object[]{
                    jvmtiEnv, JVMTI_ENABLE, JVMTI_EVENT_CLASS_FILE_LOAD_HOOK, Pointer.NULL
            });
            if (result != 0) {
                AgentLogWriter.info("[JvmTiChannel] SetEventNotificationMode failed, code=" + result);
                return;
            }

            activeCallback = callback;
            classPrepareCallback = prepareCallback;
            active = true;
            ConcurrentMap<String, Long> classes = preparedClasses();
            System.getProperties().remove(PREPARED_CLASSES_KEY, classes);
            AgentLogWriter.info("[JvmTiChannel] ClassFileLoadHook activated");
        } catch (Throwable t) {
            AgentLogWriter.info("[JvmTiChannel] activate failed: " + t.getMessage());
        }
    }

    /* 仅注销 ClassFileLoadHook；ClassPrepare 收集器继续服务于晚加载类。 */
    public static void deactivate() {
        if (jvmtiEnv == null || !active) return;

        try {
            Function setNotify = jvmtiFunction(JVMTI_SET_EVENT_NOTIFICATION_MODE);
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

    public static boolean isAvailable() {
        return active && jvmtiEnv != null;
    }

    public static boolean retransformLoadedClasses(LoadedClassMatcher matcher) {
        if (!isAvailable() || matcher == null) return false;
        List<LoadedEntry> entries = preparedClassesSnapshot();
        if (entries.isEmpty()) return false;
        List<Pointer> matched = new ArrayList<>();
        for (LoadedEntry entry : entries) {
            LoadedClassInfo info = new LoadedClassInfo(entry.internalName, entry.modifiable,
                    entry.livingEntity, entry.entityOnly);
            if (entry.modifiable && matcher.test(info)) {
                matched.add(entry.jclass);
            }
        }
        return retransformJclasses(matched, "matched prepared classes");
    }

    public static boolean retransformInternalName(String internalName) {
        if (internalName == null || internalName.isEmpty()) return false;
        return retransformLoadedClasses(info -> internalName.equals(info.internalName()));
    }

    public static boolean forEachLoadedClass(LoadedClassConsumer consumer) {
        if (!isAvailable() || consumer == null) return false;
        List<LoadedEntry> entries = preparedClassesSnapshot();
        if (entries.isEmpty()) return false;
        for (LoadedEntry entry : entries) {
            consumer.accept(new LoadedClassInfo(entry.internalName, entry.modifiable,
                    entry.livingEntity, entry.entityOnly));
        }
        return true;
    }

    /* 通过 ClassPrepare 保存的全局引用重转换显式 Java Class 目标。 */
    public static void retransformClasses(Class<?>... classes) {
        if (!active || jvmtiEnv == null || classes == null || classes.length == 0) return;
        ConcurrentMap<String, Long> prepared = existingPreparedClasses();
        if (prepared == null) return;
        List<Pointer> matched = new ArrayList<>();
        for (Class<?> clazz : classes) {
            if (clazz == null) continue;
            Long pointer = prepared.get(clazz.getName().replace('.', '/'));
            if (pointer != null && pointer != 0L) {
                matched.add(new Pointer(pointer));
            }
        }
        retransformJclasses(matched, "explicit prepared classes");
    }

    // ==================== JNI 工具 ====================

    /* 获取当前线程的 JNIEnv* */
    private static boolean retransformJclasses(List<Pointer> classes, String reason) {
        if (classes == null || classes.isEmpty()) return false;
        int ptrSize = Native.POINTER_SIZE;
        int batchSize = 32;
        boolean anySucceeded = false;
        int successCount = 0;
        for (int start = 0; start < classes.size(); start += batchSize) {
            int end = Math.min(start + batchSize, classes.size());
            Memory arr = new Memory((long) (end - start) * ptrSize);
            for (int i = start; i < end; i++) {
                arr.setPointer((long) (i - start) * ptrSize, classes.get(i));
            }
            try {
                Function retransform = jvmtiFunction(JVMTI_RETRANSFORM_CLASSES);
                int result = retransform.invokeInt(new Object[]{jvmtiEnv, end - start, arr});
                if (result == 0) {
                    anySucceeded = true;
                    successCount += end - start;
                } else {
                    AgentLogWriter.info("[JvmTiChannel] RetransformClasses failed, code=" + result
                            + " (" + reason + ")");
                    for (int i = start; i < end; i++) {
                        if (retransformOne(classes.get(i), reason)) {
                            anySucceeded = true;
                            successCount++;
                        }
                    }
                }
            } catch (Throwable t) {
                AgentLogWriter.info("[JvmTiChannel] retransform batch error: " + t.getMessage());
                for (int i = start; i < end; i++) {
                    if (retransformOne(classes.get(i), reason)) {
                        anySucceeded = true;
                        successCount++;
                    }
                }
            }
        }
        if (successCount > 0) {
            AgentLogWriter.info("[JvmTiChannel] Retransformed " + successCount
                    + " classes via JVM TI (" + reason + ")");
        }
        return anySucceeded;
    }

    private static boolean retransformOne(Pointer jclass, String reason) {
        if (jclass == null) return false;
        try {
            Memory arr = new Memory(Native.POINTER_SIZE);
            arr.setPointer(0, jclass);
            Function retransform = jvmtiFunction(JVMTI_RETRANSFORM_CLASSES);
            int result = retransform.invokeInt(new Object[]{jvmtiEnv, 1, arr});
            if (result == 0) {
                return true;
            }
            AgentLogWriter.info("[JvmTiChannel] RetransformClasses single failed, code=" + result
                    + " (" + reason + ")");
        } catch (Throwable t) {
            AgentLogWriter.info("[JvmTiChannel] retransform single error: " + t.getMessage());
        }
        return false;
    }

    private static final class LoadedEntry {
        final Pointer jclass;
        final String internalName;
        final boolean modifiable;
        boolean livingEntity;
        boolean entityOnly;

        LoadedEntry(Pointer jclass, String internalName, boolean modifiable) {
            this.jclass = jclass;
            this.internalName = internalName;
            this.modifiable = modifiable;
        }
    }

    private static List<LoadedEntry> preparedClassesSnapshot() {
        ConcurrentMap<String, Long> classes = existingPreparedClasses();
        if (classes == null || classes.isEmpty()) return List.of();
        Long livingPointer = classes.get("net/minecraft/world/entity/LivingEntity");
        Long entityPointer = classes.get("net/minecraft/world/entity/Entity");
        Pointer livingClass = livingPointer == null ? null : new Pointer(livingPointer);
        Pointer entityClass = entityPointer == null ? null : new Pointer(entityPointer);
        List<LoadedEntry> entries = new ArrayList<>(classes.size());
        for (Map.Entry<String, Long> prepared : classes.entrySet()) {
            Long pointerValue = prepared.getValue();
            if (pointerValue == null || pointerValue == 0L) continue;
            Pointer jclass = new Pointer(pointerValue);
            LoadedEntry entry = new LoadedEntry(jclass, prepared.getKey(), isModifiable(jclass));
            if (livingClass != null && isAssignableFrom(jclass, livingClass)) {
                entry.livingEntity = true;
            } else if (entityClass != null && isAssignableFrom(jclass, entityClass)) {
                entry.entityOnly = true;
            }
            entries.add(entry);
        }
        AgentLogWriter.info("[JvmTiChannel] Prepared-class snapshot: " + entries.size() + " global references");
        return entries;
    }

    private static String getInternalName(Pointer jclass) {
        try {
            Memory sigPtrPtr = new Memory(Native.POINTER_SIZE);
            Memory genericPtrPtr = new Memory(Native.POINTER_SIZE);
            Function getSig = jvmtiFunction(JVMTI_GET_CLASS_SIGNATURE);
            int code = getSig.invokeInt(new Object[]{jvmtiEnv, jclass, sigPtrPtr, genericPtrPtr});
            if (code != 0) return null;
            Pointer sigPtr = sigPtrPtr.getPointer(0);
            Pointer genericPtr = genericPtrPtr.getPointer(0);
            try {
                if (sigPtr == null) return null;
                String signature = sigPtr.getString(0);
                if (signature == null || !signature.startsWith("L") || !signature.endsWith(";")) {
                    return null;
                }
                return signature.substring(1, signature.length() - 1);
            } finally {
                deallocate(sigPtr);
                deallocate(genericPtr);
            }
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean isModifiable(Pointer jclass) {
        try {
            Memory out = new Memory(1);
            out.clear(1);
            Function fn = jvmtiFunction(JVMTI_IS_MODIFIABLE_CLASS);
            int code = fn.invokeInt(new Object[]{jvmtiEnv, jclass, out});
            return code == 0 && out.getByte(0) != 0;
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean isAssignableFrom(Pointer sub, Pointer sup) {
        if (sub == null || sup == null) return false;
        if (Pointer.nativeValue(sub) == Pointer.nativeValue(sup)) return true;
        Pointer jniEnv = getJniEnv();
        if (jniEnv == null) return false;
        try {
            int ptrSize = Native.POINTER_SIZE;
            Pointer functions = jniEnv.getPointer(0);
            Pointer fnPtr = functions.getPointer((long) JNI_IS_ASSIGNABLE_FROM * ptrSize);
            Function fn = Function.getFunction(fnPtr, Function.C_CONVENTION);
            int result = fn.invokeInt(new Object[]{jniEnv, sub, sup});
            return result != 0;
        } catch (Throwable t) {
            return false;
        }
    }

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

    private static Pointer newGlobalRef(Pointer jniEnv, Pointer localRef) {
        if (jniEnv == null || localRef == null) return null;
        try {
            int ptrSize = Native.POINTER_SIZE;
            Pointer functions = jniEnv.getPointer(0);
            Pointer functionPointer = functions.getPointer((long) JNI_NEW_GLOBAL_REF * ptrSize);
            Function function = Function.getFunction(functionPointer, Function.C_CONVENTION);
            Object result = function.invoke(Pointer.class, new Object[]{jniEnv, localRef});
            return (Pointer) result;
        } catch (Throwable t) {
            return null;
        }
    }

    private static void deleteGlobalRef(Pointer jniEnv, Pointer globalRef) {
        if (jniEnv == null || globalRef == null) return;
        try {
            int ptrSize = Native.POINTER_SIZE;
            Pointer functions = jniEnv.getPointer(0);
            Pointer functionPointer = functions.getPointer((long) JNI_DELETE_GLOBAL_REF * ptrSize);
            Function function = Function.getFunction(functionPointer, Function.C_CONVENTION);
            function.invokeVoid(new Object[]{jniEnv, globalRef});
        } catch (Throwable ignored) {
        }
    }

    // ==================== 内部实现 ====================

    /* 构建 jvmtiEventCallbacks 结构体，仅设置当前需要的回调。
       使用堆外内存手动布局，避免定义 JNA Structure（字段太多且跨 JDK 版本差异大）。 */
    private static Pointer buildCallbacksStruct(ClassFileLoadHookCallback loadHook,
                                                ClassPrepareCallback prepareCallback) {
        int structSize = callbacksStructSize();
        Pointer struct = new Memory(structSize);
        struct.clear(structSize);

        if (loadHook != null) {
            int loadHookOffset = CLASS_FILE_LOAD_HOOK_FIELD_INDEX * Native.POINTER_SIZE;
            struct.setPointer(loadHookOffset, CallbackReference.getFunctionPointer(loadHook));
        }
        if (prepareCallback != null) {
            int prepareOffset = CLASS_PREPARE_FIELD_INDEX * Native.POINTER_SIZE;
            struct.setPointer(prepareOffset, CallbackReference.getFunctionPointer(prepareCallback));
        }

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
            Function alloc = jvmtiFunction(JVMTI_ALLOCATE);
            Pointer[] memBuf = new Pointer[1];
            int result = alloc.invokeInt(new Object[]{jvmtiEnv, Long.valueOf(size), memBuf});
            if (result != 0 || memBuf[0] == null) return null;
            return memBuf[0];
        } catch (Throwable t) {
            return null;
        }
    }

    // ==================== JNA 回调实现 ====================

    private static class ClassPrepareCallback implements Callback {

        @SuppressWarnings("unused")
        public void callback(Pointer jvmtiEnv, Pointer jniEnv, Pointer thread, Pointer klass) {
            if (jniEnv == null || klass == null) return;
            try {
                String internalName = getInternalName(klass);
                if (internalName == null || internalName.isEmpty()) return;
                Pointer globalRef = newGlobalRef(jniEnv, klass);
                if (globalRef == null) return;
                long pointerValue = Pointer.nativeValue(globalRef);
                Long existing = preparedClasses().putIfAbsent(internalName, pointerValue);
                if (existing != null) {
                    deleteGlobalRef(jniEnv, globalRef);
                }
            } catch (Throwable ignored) {
                // 回调边界不能传播异常；失败的类由后续惰性路径处理。
            }
        }
    }

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
            if (classData == null) return;
            if (transformFunctions.isEmpty()) return;

            try {
                byte[] current = classData.getByteArray(0, classDataLen);
                String className = name == null ? new ClassReader(current).getClassName()
                        : name.replace('.', '/');
                if (className == null || className.isEmpty()) return;
                boolean anyTransformed = false;

                for (BiFunction<String, byte[], byte[]> fn : transformFunctions) {
                    try {
                        byte[] result = fn.apply(className, current);
                        if (result != null && result != current) {
                            current = result;
                            anyTransformed = true;
                        }
                    } catch (Throwable ignored) {
                        // 一个变换器拒绝隐藏类时，后续捕获器仍必须收到原始 classfile。
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
