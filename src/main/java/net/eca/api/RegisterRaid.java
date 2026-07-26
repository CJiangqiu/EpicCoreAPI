package net.eca.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a class as a raid definition for automatic registration.
 *
 * <p>The annotated class must extend {@link net.eca.util.raid.RaidDefinition}.
 * ECA scans all mods during {@code FMLLoadCompleteEvent}, instantiates each annotated
 * class, and registers the resulting raid. Scanning runs after faction scanning, so a
 * raid definition may freely reference faction ids registered via
 * {@link RegisterFaction}.</p>
 *
 * <p>If two mods define raids with the same {@code getId()}, the first one scanned
 * wins — later duplicates are logged and skipped.</p>
 *
 * <p>Registering a definition does not start anything. Raids are started explicitly
 * through {@link EcaAPI#startRaid} so that any trigger condition can drive them —
 * entering a region, using an item, a command, a scheduled event, and so on.</p>
 *
 * <p><b>Example</b></p>
 * <pre>{@code
 * @RegisterRaid
 * public class UndeadSiege extends RaidDefinition {
 *
 *     @Override public String getId() { return "undead_siege"; }
 *     @Override public String getDisplayName() { return "raid.mymod.undead_siege"; }
 *     @Override public String getRaiderFactionId() { return "undead_legion"; }
 *
 *     @Override public ResourceKey<Structure> getTargetStructure() {
 *         return ResourceKey.create(Registries.STRUCTURE, new ResourceLocation("minecraft", "village_plains"));
 *     }
 *
 *     @Override public List<RaidWave> getWaves() {
 *         return List.of(
 *             new RaidWave().addEntry(EntityType.ZOMBIE, 6),
 *             new RaidWave().addFaction("undead_legion", 10)
 *         );
 *     }
 *
 *     @Override public void onVictory(RaidContext ctx) {
 *         for (ServerPlayer player : ctx.getNearbyPlayers()) {
 *             player.giveExperiencePoints(500);
 *         }
 *     }
 * }
 * }</pre>
 *
 * @see net.eca.util.raid.RaidDefinition
 * @see net.eca.util.raid.RaidManager
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface RegisterRaid {
}
