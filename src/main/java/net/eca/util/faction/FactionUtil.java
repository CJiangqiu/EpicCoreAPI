package net.eca.util.faction;

import net.eca.api.EcaAPI;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.scores.Team;

/*
 * 阵营攻击判断工具 — 统一入口，所有 Mixin 和外部调用都通过此函数判断是否可以攻击。
 *
 * 判断优先级：
 *   1. 目标为创造/旁观 → false（不可攻击）
 *   2. ECA 阵营同阵营    → false
 *   3. ECA 阵营友好关系  → false
 *   4. 原版队伍同盟      → false
 *   5. ECA 无敌保护      → false
 *   6. 其余             → true（可攻击）
 *
 * TLS 参考：areOriginalAllies + areWraithAllies → 整合为阵营名比较 + 关系表查询
 */
public class FactionUtil {

    // 判断 attacker 是否可以对 target 造成伤害或设为目标
    /**
     * Central attack permission check. Returns false if the attacker is forbidden from
     * harming or targeting {@code target} due to faction rules, vanilla team rules,
     * ECA invulnerability, or creative/spectator immunity.
     * <p>
     * All faction-related mixins delegate to this single entry point.
     *
     * @param attacker the attacking/targeting entity (may be null for sourceless damage)
     * @param target   the target entity
     * @return true if the attack/targeting is permitted
     */
    public static boolean canAttack(Entity attacker, Entity target) {
        if (target == null) return false;

        // 创造/旁观模式豁免
        if (target instanceof Player player && (player.isCreative() || player.isSpectator())) {
            return false;
        }

        if (attacker == null) return true;

        // 同阵营 → 完全禁止
        if (FactionManager.areSameFaction(attacker, target)) {
            return false;
        }

        // 阵营间友好关系
        FactionRelation rel = FactionManager.getEffectiveRelation(attacker, target);
        if (rel == FactionRelation.FRIENDLY || rel == FactionRelation.SAME_FACTION) {
            return false;
        }

        // 原版队伍同盟
        if (areVanillaAllies(attacker, target)) {
            return false;
        }

        // ECA 无敌实体不可被攻击
        if (target instanceof LivingEntity && EcaAPI.isInvulnerable(target)) {
            return false;
        }

        return true;
    }

    // 判断两个实体之间是否存在原版同盟关系（同队、宠物主从）
    /**
     * Check whether two entities are vanilla allies (same team, owner-pet, etc.).
     * Does NOT include ECA faction checks.
     *
     * @param a first entity
     * @param b second entity
     * @return true if vanilla rules consider them allies
     */
    public static boolean areVanillaAllies(Entity a, Entity b) {
        if (a == null || b == null) return false;
        if (a.getUUID().equals(b.getUUID())) return true;

        // 原版队伍同盟
        Team teamA = a.getTeam();
        Team teamB = b.getTeam();
        if (teamA != null && teamB != null && teamA.isAlliedTo(teamB)) {
            return true;
        }

        // 宠物关系：主人 ↔ 宠物
        if (a instanceof Player playerA && b instanceof TamableAnimal petB) {
            if (petB.isOwnedBy(playerA)) return true;
            LivingEntity ownerB = petB.getOwner();
            if (ownerB instanceof Player ownerBPlayer
                    && playerA.getTeam() != null
                    && playerA.getTeam() == ownerBPlayer.getTeam()) {
                return true;
            }
        }

        if (a instanceof TamableAnimal petA && b instanceof Player playerB) {
            if (petA.isOwnedBy(playerB)) return true;
            LivingEntity ownerA = petA.getOwner();
            if (ownerA instanceof Player ownerAPlayer
                    && ownerAPlayer.getTeam() != null
                    && ownerAPlayer.getTeam() == playerB.getTeam()) {
                return true;
            }
        }

        // 宠物关系：宠物 ↔ 宠物（同一主人或主人同队）
        if (a instanceof TamableAnimal petA && b instanceof TamableAnimal petB) {
            LivingEntity ownerA = petA.getOwner();
            LivingEntity ownerB = petB.getOwner();
            if (ownerA != null && ownerB != null && ownerA.getUUID().equals(ownerB.getUUID())) {
                return true;
            }
            if (ownerA instanceof Player pA && ownerB instanceof Player pB
                    && pA.getTeam() != null && pA.getTeam() == pB.getTeam()) {
                return true;
            }
        }

        return false;
    }
}
