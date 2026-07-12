package net.eca.util.health;

import net.eca.config.EcaConfiguration;
import net.eca.coremod.EcaTransformerManager;
import net.eca.util.EcaLogger;
import net.eca.util.health.HealthDataflowAnalyzer.AnalysisResult;
import net.eca.util.health.HealthDataflowAnalyzer.ConstOverrideSource;
import net.eca.util.health.HealthDataflowAnalyzer.ConstProvenance;
import net.eca.util.health.HealthDataflowAnalyzer.Source;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/*
 * 常数覆写模块：把返回常数的 getHealth(及内联链)里的常数加载点精准 patch 为 resolveHealth(this, 原常数)，
 * 运行期改读按持有者身份的覆写表——命中返回覆写值，否则原样返回原常数。自成一体：
 *   覆写表(resolveHealth/setOverride/...) + 字节码改写器(registerSite/transform) + 安装(install: 登记 + retransform 烤入)。
 * install 在 warmup/惰性无条件执行(默认转换)；运行期是否改血由 resolveHealth 的配置双门控决定，关闭时返回原常数、无害。
 */
public final class ConstOverride {

    private ConstOverride() {}

    // ==================== 覆写表 ====================

    /* holder 对象 → 覆写血量。WeakHashMap 弱引用键随 holder 回收自动清理；非线程安全，统一加锁包装。
       resolveHealth 仅在双门控开启时才访问本表，功能关闭(默认)时在入口短路，不触锁。 */
    private static final Map<Object, Float> OVERRIDES = Collections.synchronizedMap(new WeakHashMap<>());

    /* 首次经过覆写的 holder 类打印一次诊断，确认 patch 生效 */
    private static final Set<String> DIAG_DUMPED = ConcurrentHashMap.newKeySet();

    /* 由 patched 字节码在常数加载点调用。holder 是持有该常数的方法的 this，original 是原字节码常数值。
       双门控(激进攻击逻辑 + 常数覆写)任一关闭 → 直接返回 original(零行为变化)；命中 holder 覆写则返回覆写值。 */
    public static float resolveHealth(Object holder, float original) {
        if (!EcaConfiguration.getAttackEnableRadicalLogicSafely()
                || !EcaConfiguration.getAttackSetHealthEnableConstOverrideSafely()) {
            return original;
        }
        if (holder == null) return original;
        Float override = OVERRIDES.get(holder);
        if (override == null) return original;
        if (DIAG_DUMPED.add(holder.getClass().getName())) {
            EcaLogger.info("[ConstOverride] resolveHealth holder={} original={} override={}",
                    holder.getClass().getName(), original, override);
        }
        return override;
    }

    /* 写入 holder 的覆写血量。由数据流写入侧(dispatchWrite 的 ConstOverrideSource 分支)调用。 */
    public static void setOverride(Object holder, float value) {
        if (holder != null) OVERRIDES.put(holder, value);
    }

    /* 读取 holder 当前覆写值，无则返回 null。供 ConstOverrideSource.read() 经注入查表。 */
    public static Float getOverride(Object holder) {
        return holder == null ? null : OVERRIDES.get(holder);
    }

    public static void removeOverride(Object holder) {
        if (holder != null) OVERRIDES.remove(holder);
    }

    public static void clear() {
        OVERRIDES.clear();
    }

    // ==================== 字节码改写器 ====================

    /* 待 patch 的常数点：类内某方法某指令下标处加载 original 浮点常数。 */
    public record Site(String methodName, String methodDesc, int insnIndex, float original) {}
    private record PatchTarget(MethodNode method, AbstractInsnNode insn, boolean fallback) {}

    private static final Map<String, List<Site>> SPECS = new ConcurrentHashMap<>();
    private static final String SELF_INTERNAL = "net/eca/util/health/ConstOverride";
    private static final Set<String> INSTALL_DUMPED = ConcurrentHashMap.newKeySet();
    private static final Set<String> PATCH_DUMPED = ConcurrentHashMap.newKeySet();
    private static final Set<String> MISS_DUMPED = ConcurrentHashMap.newKeySet();

    /* 登记一个待 patch 的常数点。classInternal 为持有者类内部名。同一点重复登记幂等。 */
    public static void registerSite(String classInternal, String methodName, String methodDesc,
                                    int insnIndex, float original) {
        if (classInternal == null || methodName == null || insnIndex < 0) return;
        Site site = new Site(methodName, methodDesc, insnIndex, original);
        List<Site> list = SPECS.computeIfAbsent(classInternal, k -> new CopyOnWriteArrayList<>());
        if (!list.contains(site)) list.add(site);
    }

    public static boolean hasSites(String classInternal) {
        List<Site> list = SPECS.get(classInternal);
        return list != null && !list.isEmpty();
    }

    /* 对持有者类字节码施加全部已登记 patch；无 spec 或无命中返回 null。由 EcaClassTransformer.doTransform 链尾调用。
       指令下标由分析器读取的运行期最终字节码算出，故 patch 排在 hook 注入之后，且 retransform 每次从原始类文件重跑全链，下标稳定。 */
    public static byte[] transform(String classInternal, byte[] bytes) {
        List<Site> sites = SPECS.get(classInternal);
        if (sites == null || sites.isEmpty() || bytes == null) return null;
        try {
            ClassReader cr = new ClassReader(bytes);
            ClassNode cn = new ClassNode();
            cr.accept(cn, ClassReader.EXPAND_FRAMES);
            List<PatchTarget> targets = new ArrayList<>();
            Set<AbstractInsnNode> used = Collections.newSetFromMap(new IdentityHashMap<>());
            int missed = 0;
            for (Site site : sites) {
                MethodNode mn = findMethod(cn, site.methodName(), site.methodDesc());
                if (mn == null || (mn.access & Opcodes.ACC_STATIC) != 0) {
                    missed++;
                    continue;
                }
                PatchTarget target = findPatchTarget(mn, site);
                if (target == null || !used.add(target.insn())) {
                    missed++;
                    continue;
                }
                targets.add(target);
            }
            if (targets.isEmpty()) {
                if (!EcaSetHealthManager.isWarmupDiagnosticsSuppressed() && MISS_DUMPED.add(classInternal)) {
                    EcaLogger.info("[ConstOverride] transform missed class={} sites={} missed={}",
                            classInternal, sites.size(), missed);
                }
                return null;
            }
            int fallbackCount = 0;
            for (PatchTarget target : targets) {
                if (target.fallback()) fallbackCount++;
                insertResolveCall(target.method(), target.insn());
            }
            if (!EcaSetHealthManager.isWarmupDiagnosticsSuppressed() && PATCH_DUMPED.add(classInternal)) {
                EcaLogger.info("[ConstOverride] transform patched class={} sites={} patched={} fallback={} missed={}",
                        classInternal, sites.size(), targets.size(), fallbackCount, missed);
            }
            ClassWriter cw = new SafeClassWriter(cr, ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
            cn.accept(cw);
            return cw.toByteArray();
        } catch (Throwable t) {
            if (t instanceof VirtualMachineError e) throw e;
            return null;
        }
    }

    private static MethodNode findMethod(ClassNode cn, String name, String desc) {
        for (MethodNode mn : cn.methods) {
            if (mn.name.equals(name) && mn.desc.equals(desc)) return mn;
        }
        return null;
    }

    private static AbstractInsnNode instructionAt(MethodNode mn, int index) {
        if (index < 0 || index >= mn.instructions.size()) return null;
        return mn.instructions.get(index);
    }

    private static PatchTarget findPatchTarget(MethodNode mn, Site site) {
        AbstractInsnNode exact = instructionAt(mn, site.insnIndex());
        if (isPatchableFloatConst(exact, site.original())) {
            return new PatchTarget(mn, exact, false);
        }
        AbstractInsnNode fallback = nearestFloatConstLoad(mn, site);
        return fallback == null ? null : new PatchTarget(mn, fallback, true);
    }

    private static AbstractInsnNode nearestFloatConstLoad(MethodNode mn, Site site) {
        AbstractInsnNode best = null;
        int bestDistance = Integer.MAX_VALUE;
        int i = 0;
        for (AbstractInsnNode insn = mn.instructions.getFirst(); insn != null; insn = insn.getNext(), i++) {
            if (!isPatchableFloatConst(insn, site.original())) continue;
            int distance = Math.abs(i - site.insnIndex());
            if (distance < bestDistance) {
                best = insn;
                bestDistance = distance;
            }
        }
        return best;
    }

    private static boolean isPatchableFloatConst(AbstractInsnNode insn, float original) {
        if (!isFloatConstLoad(insn, original)) return false;
        return !isResolveCall(insn.getNext());
    }

    private static boolean isResolveCall(AbstractInsnNode insn) {
        return insn instanceof MethodInsnNode call
                && call.getOpcode() == Opcodes.INVOKESTATIC
                && SELF_INTERNAL.equals(call.owner)
                && "resolveHealth".equals(call.name)
                && "(Ljava/lang/Object;F)F".equals(call.desc);
    }

    private static void insertResolveCall(MethodNode mn, AbstractInsnNode target) {
        mn.instructions.insertBefore(target, new VarInsnNode(Opcodes.ALOAD, 0));
        mn.instructions.insert(target, new MethodInsnNode(Opcodes.INVOKESTATIC,
                SELF_INTERNAL, "resolveHealth", "(Ljava/lang/Object;F)F", false));
    }

    /* 校验目标指令确为压入指定浮点常数的指令，防止字节码漂移后误 patch。 */
    private static boolean isFloatConstLoad(AbstractInsnNode insn, float original) {
        if (insn == null) return false;
        int op = insn.getOpcode();
        if (op == Opcodes.FCONST_0) return original == 0f;
        if (op == Opcodes.FCONST_1) return original == 1f;
        if (op == Opcodes.FCONST_2) return original == 2f;
        if (op == Opcodes.LDC && insn instanceof LdcInsnNode ldc && ldc.cst instanceof Float f) {
            return f.floatValue() == original;
        }
        return false;
    }

    /* getCommonSuperClass 回退 Object，避免 COMPUTE_FRAMES 时加载未就绪的类。 */
    private static final class SafeClassWriter extends ClassWriter {
        private SafeClassWriter(ClassReader cr, int flags) { super(cr, flags); }
        @Override protected String getCommonSuperClass(String type1, String type2) {
            return "java/lang/Object";
        }
    }

    // ==================== 安装(登记 + retransform 烤入) ====================

    /* 把树内 ConstOverrideSource 的常数点登记并对其 owner 类触发 retransform 使 patch 生效。
       强制兼容模式或配置关闭时不转换(硬门——不登记 site、不 retransform)。仅含 DATAFLOW 源(无 ConstOverrideSource)的树收集不到 owner 时提前返回，零开销。 */
    public static void install(AnalysisResult tree) {
        if (tree == null || tree.sources.isEmpty()) return;
        // 强制兼容模式 → 跳过全部转换；配置关闭 → 不登记不 retransform
        if (EcaConfiguration.getForceCompatibilityModeSafely()
                || !EcaConfiguration.getAttackEnableRadicalLogicSafely()
                || !EcaConfiguration.getAttackSetHealthEnableConstOverrideSafely()) return;
        Set<String> ownerInternals = new HashSet<>();
        int siteCount = 0;
        for (Source s : tree.sources) {
            if (!(s instanceof ConstOverrideSource co)) continue;
            ConstProvenance p = co.provenance;
            registerSite(p.ownerInternal(), p.methodName(), p.methodDesc(), p.insnIndex(), co.original);
            ownerInternals.add(p.ownerInternal());
            siteCount++;
        }
        if (ownerInternals.isEmpty()) return;
        String installKey = ownerInternals + "|" + siteCount;
        if (!EcaSetHealthManager.isWarmupDiagnosticsSuppressed() && INSTALL_DUMPED.add(installKey)) {
            EcaLogger.info("[ConstOverride] install sites={} owners={}", siteCount, ownerInternals);
        }
        // 全部 site 登记完再逐 owner retransform：retransform 从原始字节码重跑全链，一次覆盖该类所有 site
        for (String internal : ownerInternals) {
            Class<?> owner = HealthDataflowAnalyzer.loadClass(internal);
            if (owner == null) {
                if (!EcaSetHealthManager.isWarmupDiagnosticsSuppressed()
                        && INSTALL_DUMPED.add("missing:" + internal)) {
                    EcaLogger.info("[ConstOverride] install skipped: owner missing {}", internal);
                }
                continue;
            }
            try {
                if (!EcaTransformerManager.retransformClass(owner)
                        && !EcaSetHealthManager.isWarmupDiagnosticsSuppressed()
                        && INSTALL_DUMPED.add("unmodifiable:" + internal)) {
                    EcaLogger.info("[ConstOverride] install skipped: owner retransform unavailable {}", owner.getName());
                }
            } catch (Throwable t) {
                if (t instanceof VirtualMachineError e) throw e;
                if (!EcaSetHealthManager.isWarmupDiagnosticsSuppressed())
                    EcaLogger.info("[ConstOverride] retransform failed owner={} msg={}", owner.getName(), t.toString());
            }
        }
    }
}
