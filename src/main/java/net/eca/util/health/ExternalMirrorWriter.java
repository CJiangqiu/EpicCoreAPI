package net.eca.util.health;

import net.eca.util.EcaLogger;
import net.eca.util.reflect.ReflectUtil;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.DimensionDataStorage;
import net.minecraftforge.common.util.DummySavedData;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/*
 * 外部扫描第三阶段：实体自身的存储写对了、当场校验也过了，却在下一 tick 被改回去——
 * 说明真实血量另有一份实体之外的镜像，防护逻辑每 tick 拿它覆盖实体存储。
 * 本阶段在世界存档与全局静态字段中按"值与当前血量吻合"定位这类镜像并联写。
 * 判据只能是值吻合：镜像对血量的作用延迟一个 tick，微扰当场测不出斜率，数值反演那套定位不到它。
 * 写入的是会存盘的世界级数据，因此逐单元记账并快照，成败交由下一 tick 的延迟复查裁定。
 */
public final class ExternalMirrorWriter {

    private ExternalMirrorWriter() {}

    private static final long TIME_BUDGET_NANOS = 100_000_000L;
    private static final int MAX_CELLS = 8192;

    /* 值匹配容差自严到宽逐层放宽，取第一层命中且数量可控的结果。
       镜像与实体血量之间常有取整、单 tick 扣减等偏差，容差过紧会漏；但血量本身较小时，
       宽容差会把世界存档里的计数器、计时器一并囊括进来，必须先给精确匹配机会。 */
    private static final float[] MATCH_TOLERANCES = {0.5f, 5.0f, 20.0f};

    /* 单次最多联写的单元数。命中过多说明判据已经失准，此时写下去等于拿目标值
       覆盖一批不相干的世界数据，宁可放弃本次。 */
    private static final int MAX_WRITE_CELLS = 16;

    /* 候选单元连同归属的存档对象。归属必须逐单元记录：只有确实被写入的存档才能置脏，
       全量置脏会把 Forge 的 DummySavedData 占位也标记出去，而它的 save 按设计返回 null，
       序列化时直接抛 Invalid null NBT value。 */
    private record Candidate(NumericInverter.Cell cell, SavedData owner) {}

    /* 本次联写的单元与快照，按验证票据记账，等对应代次的延迟复查裁定后提交或撤销。 */
    private record Written(List<NumericInverter.Cell> cells, Object[] snapshot) {}

    private static final Map<DelayedHealthVerifier.Ticket, Written> PENDING = new ConcurrentHashMap<>();
    private static final Set<String> DIAG_DUMPED = ConcurrentHashMap.newKeySet();

    private static void diag(Class<?> cls, String reason) {
        if (DIAG_DUMPED.add(cls.getName() + "|" + reason)) {
            EcaLogger.info("[ExternalMirror] {} entity={}", reason, cls.getName());
        }
    }

    /* 联写外部镜像。before 取本次写入之前的锚点读数——镜像此刻仍持有该值，正是匹配依据。 */
    static boolean write(LivingEntity entity, float before, float target, DelayedHealthVerifier.Ticket ticket) {
        if (entity == null || !Float.isFinite(before) || !Float.isFinite(target)) return false;
        if (ticket == null || entity.getId() != ticket.entityId()
                || !entity.getUUID().equals(ticket.entityUuid())) return false;
        if (!(entity.level() instanceof ServerLevel level)) return false;
        Class<?> cls = entity.getClass();
        long deadline = System.nanoTime() + TIME_BUDGET_NANOS;

        List<Candidate> candidates = collectCandidates(entity, level, deadline);
        List<Candidate> matched = matchByValue(candidates, before, cls, entity);
        if (matched.isEmpty()) return false;

        List<NumericInverter.Cell> cells = new ArrayList<>(matched.size());
        for (Candidate candidate : matched) cells.add(candidate.cell());
        Object[] snapshot = new Object[cells.size()];
        for (int i = 0; i < cells.size(); i++) snapshot[i] = cells.get(i).snapshot();

        List<String> labels = new ArrayList<>(matched.size());
        Set<SavedData> touched = Collections.newSetFromMap(new IdentityHashMap<>());
        int written = 0;
        for (Candidate candidate : matched) {
            double old = candidate.cell().read();
            if (!candidate.cell().write(target)) continue;
            written++;
            labels.add(candidate.cell().label() + " " + old + "->" + target);
            if (candidate.owner() != null) touched.add(candidate.owner());
        }
        if (written == 0) {
            restore(cells, snapshot);
            diag(cls, "matched external cells are not writable");
            return false;
        }

        // 存档数据改了必须置脏才会落盘；只置真正被写入的那些
        for (SavedData data : touched) data.setDirty();

        /* 同一实体在一个 tick 内被改血多次时必须追加而非覆盖：后一次的快照拍摄于前一次写入之后，
           单独持有它只能还原到中间态。合并后按逆序撤销，同一单元最终落回最早的原值。 */
        PENDING.merge(ticket, new Written(cells, snapshot), ExternalMirrorWriter::append);
        EcaLogger.info("[ExternalMirror] co-wrote entity={} before={} target={} cells={}",
                cls.getName(), before, target, labels);
        return true;
    }

    //延迟复查确认写入留住了：丢弃快照，不再撤销
    static void commit(DelayedHealthVerifier.Ticket ticket) {
        if (ticket != null) PENDING.remove(ticket);
    }

    /* 延迟复查仍报回滚：撤销本次联写。打不穿是可以接受的，留下一批被改坏的世界数据不行。 */
    static void revert(DelayedHealthVerifier.Ticket ticket) {
        if (ticket == null) return;
        Written written = PENDING.remove(ticket);
        if (written == null) return;
        restore(written.cells(), written.snapshot());
        EcaLogger.info("[ExternalMirror] reverted {} external cell(s): rollback persisted", written.cells().size());
    }

    static void supersede(DelayedHealthVerifier.Ticket previous, DelayedHealthVerifier.Ticket next) {
        if (previous == null || next == null || previous.equals(next)) return;
        Written written = PENDING.remove(previous);
        if (written != null) PENDING.merge(next, written, ExternalMirrorWriter::append);
    }

    static void clear() {
        for (Written written : PENDING.values()) restore(written.cells(), written.snapshot());
        PENDING.clear();
    }

    /* 逐层放宽容差取匹配集：命中且不超过写入上限即采用。
       所有层都过宽说明该血量值在世界数据里太常见，判据无法定位镜像，放弃优于乱写。 */
    private static List<Candidate> matchByValue(List<Candidate> candidates, float before, Class<?> cls,
                                                LivingEntity entity) {
        for (float tolerance : MATCH_TOLERANCES) {
            List<Candidate> matched = new ArrayList<>();
            for (Candidate candidate : candidates) {
                double value = candidate.cell().read();
                if (Double.isFinite(value) && Math.abs(value - before) <= tolerance) matched.add(candidate);
            }
            // 空集说明这一层太严，放宽再试
            if (matched.isEmpty()) continue;
            int strongestAssociation = 0;
            for (Candidate candidate : matched) {
                strongestAssociation = Math.max(strongestAssociation,
                        candidate.cell().associationScore(entity));
            }
            if (strongestAssociation > 0) {
                int requiredScore = strongestAssociation;
                matched.removeIf(candidate -> candidate.cell().associationScore(entity) < requiredScore);
            }
            if (matched.size() <= MAX_WRITE_CELLS) return matched;
            // 更宽的容差只会命中更多，无需再试
            diag(cls, "value " + before + " too common in world data ("
                    + matched.size() + " cells within " + tolerance + ")");
            return List.of();
        }
        diag(cls, "no external cell matches health " + before + " (scanned " + candidates.size() + ")");
        return List.of();
    }

    /* 逐个存档对象分别遍历，使每个单元都带着归属；静态根无归属，写入后无需置脏。
       cellCap 是全局预算，逐根扣减，避免第一个大图吃光配额。 */
    private static List<Candidate> collectCandidates(LivingEntity entity, ServerLevel level, long deadline) {
        List<Candidate> candidates = new ArrayList<>();
        for (SavedData data : collectSavedData(level)) {
            int remaining = MAX_CELLS - candidates.size();
            if (remaining <= 0) break;
            for (NumericInverter.Cell cell : NumericInverter.collectCells(List.of(data), deadline, remaining)) {
                candidates.add(new Candidate(cell, data));
            }
        }
        for (NumericInverter.Cell cell : staticNumericCells(entity)) candidates.add(new Candidate(cell, null));
        int remaining = MAX_CELLS - candidates.size();
        if (remaining > 0) {
            for (NumericInverter.Cell cell :
                    NumericInverter.collectCells(staticReferenceRoots(entity), deadline, remaining)) {
                candidates.add(new Candidate(cell, null));
            }
        }
        return candidates;
    }

    private static Written append(Written existing, Written added) {
        List<NumericInverter.Cell> cells = new ArrayList<>(existing.cells());
        cells.addAll(added.cells());
        Object[] snapshot = new Object[cells.size()];
        System.arraycopy(existing.snapshot(), 0, snapshot, 0, existing.snapshot().length);
        System.arraycopy(added.snapshot(), 0, snapshot, existing.snapshot().length, added.snapshot().length);
        return new Written(cells, snapshot);
    }

    private static void restore(List<NumericInverter.Cell> cells, Object[] snapshot) {
        for (int i = cells.size() - 1; i >= 0; i--) {
            try { cells.get(i).restore(snapshot[i]); }
            catch (Throwable t) { if (t instanceof VirtualMachineError e) throw e; }
        }
    }

    /* 当前维度与主世界的存档数据。模组的世界变量通常挂在主世界，跨维度实体也要能找到。 */
    private static List<SavedData> collectSavedData(ServerLevel level) {
        List<SavedData> out = new ArrayList<>();
        Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        collectFrom(level.getDataStorage(), out, seen);
        MinecraftServer server = level.getServer();
        if (server != null && server.overworld() != level) {
            collectFrom(server.overworld().getDataStorage(), out, seen);
        }
        return out;
    }

    private static void collectFrom(DimensionDataStorage storage, List<SavedData> out, Set<Object> seen) {
        if (storage == null || !seen.add(storage)) return;
        Field cacheField = ReflectUtil.getField(DimensionDataStorage.class, "DimensionDataStorage.cache");
        if (cacheField == null) return;
        try {
            Object cache = cacheField.get(storage);
            if (!(cache instanceof Map<?, ?> map)) return;
            /* 缓存里存 null 表示该名字尚未读盘，DummySavedData.DUMMY 表示读盘失败的占位——
               后者的 save 按设计返回 null，一旦被置脏就会在序列化时抛异常，绝不能纳入。 */
            for (Object value : map.values()) {
                if (!(value instanceof SavedData data) || data == DummySavedData.DUMMY) continue;
                if (seen.add(data)) out.add(data);
            }
        } catch (Throwable t) {
            if (t instanceof VirtualMachineError e) throw e;
            EcaLogger.info("[ExternalMirror] saved data enumeration failed: {}", t.toString());
        }
    }

    /* 实体类层次上的静态数值字段：全局单例持血量的写法很常见。
       原版类的静态字段与血量无关，跳过以免把注册表之类的大图拖进来。 */
    private static List<NumericInverter.Cell> staticNumericCells(LivingEntity entity) {
        List<NumericInverter.Cell> cells = new ArrayList<>();
        for (Field field : modStaticFields(entity)) {
            if (!isNumeric(field.getType())) continue;
            // static final 数值可能已被 JIT 内联成常量，改它只留副作用不产生效果
            if (Modifier.isFinal(field.getModifiers())) continue;
            cells.add(NumericInverter.staticFieldCell(field));
        }
        return cells;
    }

    // 静态引用字段指向的对象作为遍历根，镜像可能藏在单例的成员里
    private static List<Object> staticReferenceRoots(LivingEntity entity) {
        List<Object> roots = new ArrayList<>();
        for (Field field : modStaticFields(entity)) {
            if (field.getType().isPrimitive() || isNumeric(field.getType())) continue;
            try {
                Object value = field.get(null);
                if (value != null) roots.add(value);
            } catch (Throwable t) {
                if (t instanceof VirtualMachineError e) throw e;
            }
        }
        return roots;
    }

    private static List<Field> modStaticFields(LivingEntity entity) {
        List<Field> out = new ArrayList<>();
        for (Class<?> k = entity.getClass(); k != null && k != Object.class; k = k.getSuperclass()) {
            if (k.getName().startsWith("net.minecraft.")) continue;
            for (Field field : k.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers())) continue;
                try {
                    field.setAccessible(true);
                    out.add(field);
                } catch (Throwable t) {
                    if (t instanceof VirtualMachineError e) throw e;
                }
            }
        }
        return out;
    }

    private static boolean isNumeric(Class<?> type) {
        return type == int.class || type == long.class || type == float.class || type == double.class
                || type == short.class || type == byte.class || Number.class.isAssignableFrom(type);
    }
}
