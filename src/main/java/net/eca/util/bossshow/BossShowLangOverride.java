package net.eca.util.bossshow;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.eca.EcaMod;
import net.eca.util.EcaLogger;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;

//客户端字幕翻译的整合包覆盖层：读 config/eca/bossshow/lang/<locale>.json
//优先级在 vanilla I18n 之上；当前 locale 未命中 → 返回 null，由调用方回退到 I18n
@OnlyIn(Dist.CLIENT)
@Mod.EventBusSubscriber(modid = EcaMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class BossShowLangOverride {

    private static final Path LANG_DIR = Paths.get("config", "eca", "bossshow", "lang");

    //locale（全小写）→ key → value
    private static final Map<String, Map<String, String>> OVERRIDES = new ConcurrentHashMap<>();

    private BossShowLangOverride() {}

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(BossShowLangOverride::load);
    }

    public static void load() {
        OVERRIDES.clear();
        if (!Files.isDirectory(LANG_DIR)) return;

        int fileCount = 0;
        int keyCount = 0;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(LANG_DIR)) {
            for (Path path : stream) {
                if (Files.isDirectory(path)) continue;
                String name = path.getFileName().toString();
                String lower = name.toLowerCase();
                if (!lower.endsWith(".json")) continue;

                String locale = lower.substring(0, lower.length() - 5);
                Map<String, String> map = parseLangFile(path);
                if (map == null) continue;

                OVERRIDES.put(locale, map);
                fileCount++;
                keyCount += map.size();
            }
        } catch (IOException e) {
            EcaLogger.error("Failed to scan BossShow lang override directory: {}", e.getMessage());
            return;
        }
        if (fileCount > 0) {
            EcaLogger.info("Loaded BossShow lang overrides: {} locale(s), {} key(s)", fileCount, keyCount);
        }
    }

    private static Map<String, String> parseLangFile(Path file) {
        try {
            String content = Files.readString(file, StandardCharsets.UTF_8);
            JsonElement root = JsonParser.parseString(content);
            if (!root.isJsonObject()) {
                EcaLogger.error("BossShow lang file {} root must be an object", file);
                return null;
            }
            JsonObject obj = root.getAsJsonObject();
            Map<String, String> out = new HashMap<>();
            for (Entry<String, JsonElement> e : obj.entrySet()) {
                JsonElement v = e.getValue();
                if (v.isJsonPrimitive()) {
                    out.put(e.getKey(), v.getAsString());
                }
            }
            return out;
        } catch (Throwable t) {
            EcaLogger.error("Failed to parse BossShow lang file {}: {}", file, t.getMessage());
            return null;
        }
    }

    //返回当前 locale 下 key 对应的 override 文本；未命中返回 null（调用方回退到 I18n）
    public static String lookup(String key) {
        if (key == null || key.isEmpty() || OVERRIDES.isEmpty()) return null;
        String locale = currentLocale();
        if (locale == null) return null;
        Map<String, String> map = OVERRIDES.get(locale);
        if (map == null) return null;
        return map.get(key);
    }

    private static String currentLocale() {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null || mc.getLanguageManager() == null) return null;
            String sel = mc.getLanguageManager().getSelected();
            return sel != null ? sel.toLowerCase() : null;
        } catch (Throwable t) {
            return null;
        }
    }
}
