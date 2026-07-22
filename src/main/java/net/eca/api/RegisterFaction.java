package net.eca.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a class as a faction definition for automatic registration.
 *
 * <p>The annotated class must extend {@link net.eca.util.faction.FactionDefinition}.
 * ECA scans all mods during {@code FMLLoadCompleteEvent}, instantiates each annotated
 * class, and registers the resulting faction.</p>
 *
 * <p>If two mods define factions with the same {@code getId()}, the first one scanned
 * wins — later duplicates are logged and skipped.</p>
 *
 * <p><b>Example</b></p>
 * <pre>{@code
 * @RegisterFaction
 * public class UndeadFaction extends FactionDefinition {
 *
 *     @Override public String getId() { return "undead_legion"; }
 *     @Override public String getDisplayName() { return "faction.mymod.undead.name"; }
 *     @Override public int getColor() { return 0xFF884400; }
 *
 *     @Override public FactionRelation getDefaultRelation() { return FactionRelation.NEUTRAL; }
 *     @Override public String[] getHostileTo() { return new String[]{"village_guard"}; }
 *     @Override public String[] getFriendlyTo() { return new String[]{"lich_coven"}; }
 * }
 * }</pre>
 *
 * @see net.eca.util.faction.FactionDefinition
 * @see net.eca.util.faction.FactionManager
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface RegisterFaction {
}
