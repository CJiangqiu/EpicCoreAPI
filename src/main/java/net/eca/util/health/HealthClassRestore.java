package net.eca.util.health;

import net.eca.api.EcaAPI;
import net.eca.coremod.RestoreManager;
import net.eca.util.EntityUtil;
import net.eca.util.EcaLogger;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.LivingEntity;

public final class HealthClassRestore {

    private HealthClassRestore() {}

    public static boolean write(LivingEntity entity, float target) {
        if (entity == null) return false;
        if (!EcaAPI.restoreEntity(entity)) {
            EcaLogger.info("[HealthClassRestore] restore failed entity={}", entity.getClass().getName());
            return false;
        }

        EntityUtil.setBasicHealth(entity, target);
        boolean ok = EcaSetHealthManager.verify(entity, target);
        if (!ok && RestoreManager.forceRetransform(entity)) {
            EntityUtil.setBasicHealth(entity, target);
            ok = EcaSetHealthManager.verify(entity, target);
        }
        EcaLogger.info("[HealthClassRestore] entity={} target={} actual={} basic={} restored={} result={}",
                entity.getClass().getName(), target, EcaSetHealthManager.safeGetHealth(entity),
                getBasicHealth(entity), RestoreManager.isRestored(entity), ok ? "OK" : "FAIL");
        return ok;
    }

    private static float getBasicHealth(LivingEntity entity) {
        if (entity == null) return Float.NaN;
        try {
            SynchedEntityData data = entity.getEntityData();
            return data.get(LivingEntity.DATA_HEALTH_ID);
        } catch (Throwable t) {
            if (t instanceof VirtualMachineError e) throw e;
            return Float.NaN;
        }
    }
}
