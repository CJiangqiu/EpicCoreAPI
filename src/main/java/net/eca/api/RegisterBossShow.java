package net.eca.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a class as a BossShow (cinematic cutscene) for automatic registration.
 *
 * <p><b>Overview</b></p>
 * The BossShow system provides cinematic camera cutscenes that can be triggered automatically
 * when players enter a configured range of a target entity, or manually via the API. Cutscenes
 * are defined either:
 * <ul>
 *   <li>Purely in JSON (under {@code data/<modid>/bossshow/<name>.json}) - no Java class needed</li>
 *   <li>In Java by extending {@link net.eca.util.bossshow.BossShow}, which allows server-side
 *       keyframe event callbacks (e.g., summoning particles, triggering sounds, modifying
 *       the target entity). The JSON definition is auto-loaded from the declared id.</li>
 * </ul>
 *
 * <p><b>Config override</b></p>
 * Files under {@code config/eca/bossshow/<namespace>/<name>.json} take precedence over mod-bundled
 * assets, allowing modpack authors to tune cutscenes without repacking.
 *
 * <p><b>Registration Process</b></p>
 * <ol>
 *   <li>Annotate your class with {@code @RegisterBossShow}</li>
 *   <li>Extend {@link net.eca.util.bossshow.BossShow}</li>
 *   <li>Register your instance in a static initializer block via
 *       {@code BossShowManager.register(new YourBossShow())}</li>
 * </ol>
 *
 * @see net.eca.util.bossshow.BossShow
 * @see net.eca.util.bossshow.BossShowManager
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface RegisterBossShow {
}
