package net.eca.blender.animation;

import net.eca.blender.animation.controller.BlenderAnimationClip;
import net.eca.blender.animation.controller.BlenderCancellationReason;
import net.eca.blender.animation.controller.BlenderControllerContext;
import net.eca.blender.animation.controller.BlenderControllerSet;
import net.eca.blender.animation.controller.BlenderSkillController;
import net.eca.blender.animation.controller.BlenderSkillDefinition;
import net.eca.blender.animation.controller.BlenderSkillMarker;
import net.eca.util.EcaLogger;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

/** Server-owned single-body arbitration. Same-entity mutations from callbacks are rejected. */
public final class BlenderControllers {
    private enum Mode { NONE, BASE, HURT, SKILL, MANUAL, DEATH }
    private static final Map<EntityType<?>, BlenderControllerSet> DEFINITIONS = new ConcurrentHashMap<>();
    private static final Map<UUID, Session> SESSIONS = new ConcurrentHashMap<>();
    private static final Set<UUID> RESOLVING = new HashSet<>();
    private static final Set<Class<?>> LOGGED_FAILURES = ConcurrentHashMap.newKeySet();
    private static final AtomicLong EXECUTIONS = new AtomicLong();
    private static volatile Function<LivingEntity, BlenderControllerSet> fallback = entity -> null;
    private static boolean shuttingDown;

    private BlenderControllers() { }

    public static boolean register(EntityType<?> type, BlenderControllerSet controllers) {
        return DEFINITIONS.putIfAbsent(Objects.requireNonNull(type), Objects.requireNonNull(controllers)) == null;
    }

    public static boolean unregister(EntityType<?> type, BlenderControllerSet controllers) {
        return DEFINITIONS.remove(type, controllers);
    }

    public static void setFallbackResolver(Function<LivingEntity, BlenderControllerSet> resolver) {
        fallback = Objects.requireNonNull(resolver);
    }

    private static boolean serverEntity(LivingEntity entity) {
        return !shuttingDown && entity != null && entity.level() instanceof ServerLevel level
            && level.getServer().isSameThread() && !entity.isRemoved();
    }

    private static Session session(LivingEntity entity) {
        if (!serverEntity(entity) || !RESOLVING.add(entity.getUUID())) return null;
        try {
            Session old = SESSIONS.get(entity.getUUID());
            if (old != null && old.busy) return old;
            BlenderControllerSet definition = DEFINITIONS.get(entity.getType());
            if (definition == null) definition = fallback.apply(entity);
            if (old != null && (old.entity != entity || !Objects.equals(old.controllers, definition))) {
                dispose(old, BlenderCancellationReason.CONTROLLER_CHANGED);
                old = null;
            }
            if (definition == null || (definition.lifecycle() == null && definition.skills() == null)) return null;
            if (old != null) return old;
            Session created = new Session(entity, definition);
            if (BlenderAnimations.state(entity) != null) created.mode = Mode.MANUAL;
            SESSIONS.put(entity.getUUID(), created);
            return created;
        } catch (Throwable failure) {
            log(fallback.getClass(), failure);
            Session old = SESSIONS.get(entity.getUUID());
            if (old != null) dispose(old, BlenderCancellationReason.ERROR);
            return null;
        } finally {
            RESOLVING.remove(entity.getUUID());
        }
    }

    public static void discover(LivingEntity entity) { session(entity); }

    public static void queueHurt(LivingEntity entity, BooleanSupplier accepted) {
        Session session = session(entity);
        if (session != null && !session.disabled && accepted != null) session.hurts.add(accepted);
    }

    public static boolean triggerSkill(LivingEntity entity, String skillId) {
        if (skillId == null || skillId.isBlank()) return false;
        Session session = session(entity);
        if (session == null || session.busy || session.disabled || session.mode == Mode.MANUAL
            || session.controllers.skills() == null) return false;
        session.busy = true;
        try {
            if (dead(session) || session.mode == Mode.DEATH) return false;
            BlenderSkillController controller = session.controllers.skills();
            BlenderSkillDefinition skill = controller.findSkill(context(session), skillId);
            if (skill == null || !skill.id().equals(skillId) || !controller.canStartSkill(context(session), skill)) return false;
            if (session.run != null) {
                if (session.run.skill.id().equals(skill.id()) && !skill.restartable()) return false;
                if (!controller.canInterrupt(context(session), session.run.skill, skill)) return false;
                if (!valid(session)) return false;
                if (dead(session)) { enterDeath(session); return false; }
                cancelRun(session, BlenderCancellationReason.REPLACED);
            }
            if (!valid(session) || dead(session)) return false;
            session.run = new Run(EXECUTIONS.incrementAndGet(), skill, controller);
            transition(session, Mode.SKILL, skill.clip(), true);
            controller.onSkillStarted(context(session));
            advanceSkill(session);
            return true;
        } catch (Throwable failure) {
            fail(session, failure);
            return false;
        } finally {
            release(session);
        }
    }

    public static boolean cancelSkill(LivingEntity entity) {
        if (!serverEntity(entity)) return false;
        Session session = SESSIONS.get(entity.getUUID());
        if (session == null || session.busy || session.run == null) return false;
        session.busy = true;
        try {
            cancelRun(session, BlenderCancellationReason.STOPPED);
            transition(session, Mode.NONE, null, true);
            return true;
        } finally {
            release(session);
        }
    }

    public static BlenderControllerContext currentSkill(LivingEntity entity) {
        if (!serverEntity(entity)) return null;
        Session session = SESSIONS.get(entity.getUUID());
        return session == null || session.run == null ? null : context(session);
    }

    public static void tick(ServerLevel level) {
        for (Session session : List.copyOf(SESSIONS.values())) {
            if (session.entity.level() != level || session.busy) continue;
            session.busy = true;
            try {
                if (!valid(session) || session.disabled) continue;
                boolean hurt = false;
                for (BooleanSupplier accepted : List.copyOf(session.hurts)) hurt |= accepted.getAsBoolean();
                session.hurts.clear();
                if (dead(session) || session.mode == Mode.DEATH) {
                    enterDeath(session);
                    continue;
                }
                if (session.mode == Mode.MANUAL) continue;
                if (hurt && session.controllers.lifecycle() != null) {
                    BlenderAnimationClip clip = session.controllers.lifecycle().hurtAnimation(context(session));
                    requireOneShot(clip);
                    if (!valid(session)) continue;
                    if (dead(session)) { enterDeath(session); continue; }
                    if (clip != null && (session.run == null || session.run.controller.canInterruptForHurt(context(session), session.run.skill))) {
                        if (!valid(session)) continue;
                        if (dead(session)) { enterDeath(session); continue; }
                        cancelRun(session, BlenderCancellationReason.HURT);
                        if (valid(session) && dead(session)) { enterDeath(session); continue; }
                        transition(session, Mode.HURT, clip, true);
                    }
                }
                if (session.run != null) advanceSkill(session);
                if (!valid(session) || session.disabled || session.run != null) continue;
                if (dead(session) || session.mode == Mode.DEATH) { enterDeath(session); continue; }
                if (session.mode == Mode.HURT && elapsed(session) < session.clip.durationTicks()) continue;
                BlenderAnimationClip base = session.controllers.lifecycle() == null ? null
                    : session.controllers.lifecycle().selectBaseAnimation(context(session));
                if (valid(session) && dead(session)) { enterDeath(session); continue; }
                transition(session, Mode.BASE, base, false);
            } catch (Throwable failure) {
                fail(session, failure);
            } finally {
                release(session);
            }
        }
    }

    private static void requireOneShot(BlenderAnimationClip clip) {
        if (clip != null && clip.loop()) throw new IllegalArgumentException("Lifecycle event animations must not loop");
    }

    private static void enterDeath(Session session) {
        if (session.mode == Mode.DEATH || !valid(session)) return;
        cancelRun(session, BlenderCancellationReason.DEATH);
        BlenderAnimationClip clip = session.controllers.lifecycle() == null ? null
            : session.controllers.lifecycle().deathAnimation(context(session));
        requireOneShot(clip);
        transition(session, Mode.DEATH, clip, true);
    }

    private static boolean dead(Session session) {
        return session.entity.isDeadOrDying() || (session.controllers.lifecycle() != null
            && session.controllers.lifecycle().isDead(context(session)));
    }

    private static float elapsed(Session session) {
        BlenderPlaybackState state = BlenderAnimations.state(session.entity);
        if (state == null) return session.run == null ? 0 : session.run.lastElapsed;
        float elapsed = Math.max(0, state.playbackTime(session.entity.level().getGameTime(), 0) * 20);
        if (session.run != null) session.run.lastElapsed = elapsed;
        return elapsed;
    }

    private static BlenderControllerContext context(Session session) {
        Run run = session.run;
        return new BlenderControllerContext(session.entity, session.entity.level().getGameTime(),
            run == null ? 0 : run.id, run == null ? null : run.skill,
            run == null ? 0 : Math.min(elapsed(session), run.skill.clip().durationTicks()));
    }

    private static void advanceSkill(Session session) {
        Run run = session.run;
        if (run == null || !valid(session)) return;
        if (dead(session)) { enterDeath(session); return; }
        float time = elapsed(session);
        List<BlenderSkillMarker> markers = run.skill.markers();
        while (run.nextMarker < markers.size() && markers.get(run.nextMarker).tick() <= time) {
            BlenderSkillMarker marker = markers.get(run.nextMarker++);
            run.controller.onSkillMarker(context(session), marker);
            if (!valid(session)) return;
            if (dead(session)) { enterDeath(session); return; }
        }
        if (time >= run.skill.clip().durationTicks()) {
            BlenderControllerContext completed = context(session);
            session.run = null;
            session.mode = Mode.NONE;
            try {
                run.controller.onSkillCompleted(completed);
            } catch (Throwable failure) {
                log(run.controller.getClass(), failure);
            }
        }
    }

    private static void cancelRun(Session session, BlenderCancellationReason reason) {
        Run run = session.run;
        if (run == null) return;
        BlenderControllerContext cancelled = context(session);
        session.run = null;
        try {
            run.controller.onSkillCancelled(cancelled, reason);
        } catch (Throwable failure) {
            log(run.controller.getClass(), failure);
        }
    }

    private static void transition(Session session, Mode mode, BlenderAnimationClip clip, boolean restart) {
        if (!valid(session) || (!restart && session.mode == mode && Objects.equals(session.clip, clip))) return;
        session.mode = mode;
        session.clip = clip;
        if (clip == null) BlenderAnimations.stopControlled(session.entity);
        else BlenderAnimations.playControlled(session.entity, clip.animation(), clip.speed(), clip.loop());
    }

    static boolean beforeManualPlay(LivingEntity entity) {
        if (shuttingDown || RESOLVING.contains(entity.getUUID())) return false;
        Session session = session(entity);
        if (session == null) return true;
        if (session.busy || session.mode == Mode.DEATH) return false;
        session.busy = true;
        try {
            if (dead(session)) return false;
            cancelRun(session, BlenderCancellationReason.MANUAL_OVERRIDE);
            if (!valid(session) || dead(session)) return false;
            session.mode = Mode.MANUAL;
            session.clip = null;
            return true;
        } catch (Throwable failure) {
            fail(session, failure);
            return false;
        } finally {
            release(session);
        }
    }

    static boolean beforeManualStop(LivingEntity entity) {
        if (RESOLVING.contains(entity.getUUID())) return false;
        Session session = SESSIONS.get(entity.getUUID());
        if (session == null) return true;
        if (session.busy || session.mode == Mode.DEATH) return false;
        session.busy = true;
        try {
            cancelRun(session, BlenderCancellationReason.STOPPED);
            session.mode = Mode.NONE;
            session.clip = null;
            return valid(session);
        } finally {
            release(session);
        }
    }

    static boolean canChangePlayback(LivingEntity entity) {
        Session session = SESSIONS.get(entity.getUUID());
        return !RESOLVING.contains(entity.getUUID()) && (session == null || (!session.busy && session.mode != Mode.DEATH));
    }

    public static void onEntityLeave(LivingEntity entity) {
        Session session = SESSIONS.get(entity.getUUID());
        if (session != null && session.entity == entity) {
            elapsed(session);
            dispose(session, BlenderCancellationReason.ENTITY_REMOVED);
        }
    }

    public static void clear() {
        shuttingDown = true;
        try {
            for (Session session : List.copyOf(SESSIONS.values())) dispose(session, BlenderCancellationReason.SERVER_STOPPED);
            SESSIONS.clear();
            RESOLVING.clear();
            LOGGED_FAILURES.clear();
        } finally {
            shuttingDown = false;
        }
    }

    private static boolean valid(Session session) {
        return session.disposal == null && !session.entity.isRemoved() && SESSIONS.get(session.entity.getUUID()) == session;
    }

    private static void dispose(Session session, BlenderCancellationReason reason) {
        session.disposal = reason;
        if (session.busy) return;
        session.busy = true;
        release(session);
    }

    private static void release(Session session) {
        if (session.disposal != null || session.entity.isRemoved()) {
            cancelRun(session, session.disposal == null ? BlenderCancellationReason.ENTITY_REMOVED : session.disposal);
            if (session.mode != Mode.MANUAL) BlenderAnimations.stopControlled(session.entity);
            SESSIONS.remove(session.entity.getUUID(), session);
            session.hurts.clear();
        }
        session.busy = false;
    }

    private static void fail(Session session, Throwable failure) {
        Class<?> owner = session.run != null ? session.run.controller.getClass()
            : session.controllers.lifecycle() != null ? session.controllers.lifecycle().getClass()
            : session.controllers.skills().getClass();
        log(owner, failure);
        cancelRun(session, BlenderCancellationReason.ERROR);
        session.disabled = true;
        session.hurts.clear();
        if (session.mode != Mode.MANUAL) BlenderAnimations.stopControlled(session.entity);
    }

    private static void log(Class<?> owner, Throwable failure) {
        if (LOGGED_FAILURES.add(owner)) EcaLogger.error("Blender animation controller failed", failure);
    }

    private static final class Session {
        final LivingEntity entity;
        final BlenderControllerSet controllers;
        final List<BooleanSupplier> hurts = new ArrayList<>();
        Mode mode = Mode.NONE;
        BlenderAnimationClip clip;
        Run run;
        boolean busy;
        boolean disabled;
        BlenderCancellationReason disposal;

        Session(LivingEntity entity, BlenderControllerSet controllers) {
            this.entity = entity;
            this.controllers = controllers;
        }
    }

    private static final class Run {
        final long id;
        final BlenderSkillDefinition skill;
        final BlenderSkillController controller;
        int nextMarker;
        float lastElapsed;

        Run(long id, BlenderSkillDefinition skill, BlenderSkillController controller) {
            this.id = id;
            this.skill = skill;
            this.controller = controller;
        }
    }
}
