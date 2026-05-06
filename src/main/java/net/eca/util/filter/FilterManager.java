package net.eca.util.filter;

import net.eca.EcaMod;
import net.eca.network.FilterSyncPacket;
import net.eca.network.NetworkHandler;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Mod.EventBusSubscriber(modid = EcaMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class FilterManager {

    private static final Map<UUID, Set<FilterType>> ACTIVE_FILTERS = new ConcurrentHashMap<>();

    public static void enable(ServerPlayer player, FilterType filter) {
        ACTIVE_FILTERS.computeIfAbsent(player.getUUID(), k -> EnumSet.noneOf(FilterType.class)).add(filter);
        NetworkHandler.sendToPlayer(new FilterSyncPacket(filter, true), player);
    }

    public static void disable(ServerPlayer player, FilterType filter) {
        Set<FilterType> filters = ACTIVE_FILTERS.get(player.getUUID());
        if (filters != null) {
            filters.remove(filter);
            if (filters.isEmpty()) {
                ACTIVE_FILTERS.remove(player.getUUID());
            }
        }
        NetworkHandler.sendToPlayer(new FilterSyncPacket(filter, false), player);
    }

    public static boolean isEnabled(ServerPlayer player, FilterType filter) {
        Set<FilterType> filters = ACTIVE_FILTERS.get(player.getUUID());
        return filters != null && filters.contains(filter);
    }

    public static Set<FilterType> getActiveFilters(ServerPlayer player) {
        Set<FilterType> filters = ACTIVE_FILTERS.get(player.getUUID());
        return filters != null ? Collections.unmodifiableSet(EnumSet.copyOf(filters)) : Collections.emptySet();
    }

    @SubscribeEvent
    public static void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        ACTIVE_FILTERS.remove(event.getEntity().getUUID());
    }
}
