package net.eca.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.eca.EcaMod;
import net.minecraft.client.KeyMapping;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.settings.KeyConflictContext;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;

//ECA 客户端按键绑定注册
@Mod.EventBusSubscriber(modid = EcaMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class BossShowKeyBindings {

    public static final String CATEGORY = "key.categories.eca";

    //J = 开始/恢复录制
    public static final KeyMapping REC_START = new KeyMapping(
        "key.eca.bossshow.rec_start",
        KeyConflictContext.UNIVERSAL,
        InputConstants.Type.KEYSYM.getOrCreate(GLFW.GLFW_KEY_J),
        CATEGORY
    );

    //K = 添加 marker
    public static final KeyMapping ADD_MARKER = new KeyMapping(
        "key.eca.bossshow.add_marker",
        KeyConflictContext.UNIVERSAL,
        InputConstants.Type.KEYSYM.getOrCreate(GLFW.GLFW_KEY_K),
        CATEGORY
    );

    //I = 暂停录制
    public static final KeyMapping REC_PAUSE = new KeyMapping(
        "key.eca.bossshow.rec_pause",
        KeyConflictContext.UNIVERSAL,
        InputConstants.Type.KEYSYM.getOrCreate(GLFW.GLFW_KEY_I),
        CATEGORY
    );

    private BossShowKeyBindings() {}

    @SubscribeEvent
    public static void onRegisterKeys(RegisterKeyMappingsEvent event) {
        event.register(REC_START);
        event.register(ADD_MARKER);
        event.register(REC_PAUSE);
    }
}
