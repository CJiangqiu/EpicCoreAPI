package net.eca.coremod;

import cpw.mods.modlauncher.Launcher;
import cpw.mods.modlauncher.api.IEnvironment;
import cpw.mods.modlauncher.api.IModuleLayerManager;
import cpw.mods.modlauncher.api.ITransformationService;
import cpw.mods.modlauncher.api.ITransformer;
import net.eca.agent.AgentLoader;
import net.eca.agent.AgentLogWriter;
import org.jetbrains.annotations.NotNull;
import sun.misc.Unsafe;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.lang.module.Configuration;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.*;

/**
 * Earliest entry point via ITransformationService SPI.
 * Responsibilities: attach Agent, prevent dual loading, register ClassFileTransformer.
 */
@SuppressWarnings("unchecked")
public class EcaTransformationService implements ITransformationService {

    // 预加载 CoreMod 阶段需要的所有类（必须在双加载移除之前，包括内部类）
    // 双加载移除后 module layer 不可用，未预加载的类会 NoClassDefFoundError
    private static final Class<?>[] PRELOADED = preloadAll(
        // ITransformer
        "net.eca.coremod.EcaCoreTransformer",
        // LoadingScreenTransformer + 全部内部类
        "net.eca.coremod.LoadingScreenTransformer",
        "net.eca.coremod.LoadingScreenTransformer$DisplayWindowVisitor",
        "net.eca.coremod.LoadingScreenTransformer$PaintMethodVisitor",
        "net.eca.coremod.LoadingScreenTransformer$SafeWriter"
    );

    static {
        AgentLogWriter.resetForNewSession();
        AgentLoader.enableSelfAttach();
        AgentLoader.loadAgent();
        enableEcaDualLoading();
        initLoadingScreenTransformer();
    }

    private static Class<?>[] preloadAll(String... names) {
        ClassLoader cl = EcaTransformationService.class.getClassLoader();
        Class<?>[] result = new Class<?>[names.length];
        for (int i = 0; i < names.length; i++) {
            try {
                result[i] = Class.forName(names[i], true, cl);
            } catch (Throwable t) {
                result[i] = null;
            }
        }
        return result;
    }

    //在 CoreMod 阶段注册加载屏幕 Transformer（直接用预加载的类实例，无匿名类）
    private static void initLoadingScreenTransformer() {
        try {
            if (PRELOADED[1] == null || !LoadingScreenTransformer.ENABLED) {
                log("[CoreMod] Loading screen transformer disabled or unavailable");
                return;
            }

            java.lang.instrument.Instrumentation inst = net.eca.agent.EcaAgent.getInstrumentation();
            if (inst == null) {
                log("[CoreMod] No Instrumentation, skipping loading screen transformer");
                return;
            }

            // 直接用预加载的类实例注册（无匿名类，避免双加载后 NoClassDefFoundError）
            LoadingScreenTransformer transformer = new LoadingScreenTransformer();
            inst.addTransformer(transformer, true);

            // 重转换已加载的 DisplayWindow
            for (Class<?> clazz : inst.getAllLoadedClasses()) {
                if (clazz.getName().equals("net.minecraftforge.fml.earlydisplay.DisplayWindow")) {
                    inst.retransformClasses(clazz);
                    log("[CoreMod] Retransformed DisplayWindow");
                    break;
                }
            }

            log("[CoreMod] Loading screen transformer registered");
        } catch (Throwable t) {
            log("[CoreMod] Failed to init loading screen transformer: " + t.getMessage());
        }
    }

    private static final String SERVICE_NAME = "eca_coremod";
    private static final String ARCHIVES_NAME = "epic-core-api";
    private static String ecaModuleName;
    private static Path ecaJarPath;

    @Override
    public @NotNull String name() {
        return SERVICE_NAME;
    }

    @Override
    public void onLoad(@NotNull IEnvironment env, @NotNull Set<String> otherServices) {
    }

    @Override
    public void initialize(@NotNull IEnvironment environment) {
    }

    @Override
    public @NotNull List<Resource> beginScanning(@NotNull IEnvironment environment) {
        return List.of();
    }

    @Override
    public @NotNull List<Resource> completeScan(@NotNull IModuleLayerManager layerManager) {
        return List.of();
    }

    @Override
    @SuppressWarnings("rawtypes")
    public @NotNull List<ITransformer> transformers() {
        if (PRELOADED[0] == null) {
            log("[CoreMod] EcaCoreTransformer not available, skipping");
            return List.of();
        }
        try {
            ITransformer<?> transformer = (ITransformer<?>) PRELOADED[0].getDeclaredConstructor().newInstance();
            return List.of(transformer);
        } catch (Throwable t) {
            log("[CoreMod] Failed to create EcaCoreTransformer: " + t.getMessage());
            return List.of();
        }
    }

    // 双重加载防护
    private static void enableEcaDualLoading() {
        ecaModuleName = EcaTransformationService.class.getModule().getName();
        ecaJarPath = resolveOwnJarPath();
        log("[DualLoading] ecaModuleName = " + ecaModuleName + ", ecaJarPath = " + ecaJarPath);
        removeEcaFromTransformerDiscovery();
        removeEcaFromModuleLayer();
        log("[DualLoading] Dual loading completed");
    }

    // 通过类资源 URL 解析自身 JAR 路径（兼容 ModLauncher union module 环境）
    private static Path resolveOwnJarPath() {
        try {
            String className = EcaTransformationService.class.getName().replace('.', '/') + ".class";
            java.net.URL url = EcaTransformationService.class.getClassLoader().getResource(className);
            log("[DualLoading] Resource URL: " + url);
            if (url != null) {
                String urlStr = url.toString();
                // union: 协议格式：union:/D:/path/to/mod.jar%23NNN!/pkg/Class.class
                // jar:   协议格式：jar:file:/D:/path/to/mod.jar!/pkg/Class.class
                int separator = urlStr.indexOf('!');
                if (separator != -1) {
                    String prefix = urlStr.substring(0, separator);
                    String rawPath;
                    if (prefix.startsWith("union:")) {
                        // union:/D:/... → 取 union: 之后的部分，去掉 %23NNN 后缀
                        rawPath = prefix.substring("union:".length());
                        int hashIdx = rawPath.lastIndexOf("%23");
                        if (hashIdx != -1) rawPath = rawPath.substring(0, hashIdx);
                    } else {
                        // jar:file:/D:/... → 取 file: 之后的部分
                        int fileIdx = prefix.indexOf("file:");
                        if (fileIdx == -1) return null;
                        rawPath = prefix.substring(fileIdx + "file:".length());
                    }
                    String decoded = java.net.URLDecoder.decode(rawPath, "UTF-8");
                    // Windows 路径：/D:/... → D:/...
                    if (decoded.length() > 2 && decoded.charAt(0) == '/' && decoded.charAt(2) == ':') {
                        decoded = decoded.substring(1);
                    }
                    return Path.of(decoded).normalize();
                }
            }
        } catch (Exception e) {
            log("[DualLoading] ClassLoader resource lookup failed: " + e.getMessage());
        }
        return null;
    }

    private static void removeEcaFromTransformerDiscovery() {
        try {
            Class<?> discovererClass = Class.forName(
                    "net.minecraftforge.fml.loading.ModDirTransformerDiscoverer");

            VarHandle foundHandle = MethodHandles
                    .privateLookupIn(discovererClass, MethodHandles.lookup())
                    .findStaticVarHandle(discovererClass, "found", List.class);

            List<Object> found = (List<Object>) foundHandle.get();

            found.removeIf(namedPath -> {
                try {
                    Path[] paths = (Path[]) namedPath.getClass().getMethod("paths").invoke(namedPath);
                    Path normalized = paths[0].normalize();
                    boolean isEca = (ecaJarPath != null && normalized.equals(ecaJarPath))
                            || normalized.getFileName().toString().contains(ARCHIVES_NAME);
                    if (isEca) {
                        log("[DualLoading] Removed from transformer discovery: " + paths[0]);
                    }
                    return isEca;
                } catch (Exception e) {
                    return false;
                }
            });

        } catch (Exception e) {
            log("[DualLoading] Failed to remove from transformer discovery: " + e.getMessage());
        }
    }

    private static void removeEcaFromModuleLayer() {
        try {
            Unsafe unsafe = getUnsafe();

            Class<?> launcherClass = Launcher.class;
            Class<?> moduleLayerHandlerClass = Class.forName(
                    "cpw.mods.modlauncher.ModuleLayerHandler");
            Class<?> layerInfoClass = Class.forName(
                    "cpw.mods.modlauncher.ModuleLayerHandler$LayerInfo");

            MethodHandles.Lookup lookup = MethodHandles.lookup();

            VarHandle instanceHandle = MethodHandles
                    .privateLookupIn(launcherClass, lookup)
                    .findStaticVarHandle(launcherClass, "INSTANCE", launcherClass);
            Object instance = instanceHandle.get();

            VarHandle handlerHandle = MethodHandles
                    .privateLookupIn(launcherClass, lookup)
                    .findVarHandle(launcherClass, "moduleLayerHandler", moduleLayerHandlerClass);
            Object moduleLayerHandler = handlerHandle.get(instance);

            VarHandle layersHandle = MethodHandles
                    .privateLookupIn(moduleLayerHandlerClass, lookup)
                    .findVarHandle(moduleLayerHandlerClass, "completedLayers", EnumMap.class);
            EnumMap<?, ?> completedLayers = (EnumMap<?, ?>) layersHandle.get(moduleLayerHandler);

            VarHandle layerHandle = MethodHandles
                    .privateLookupIn(layerInfoClass, lookup)
                    .findVarHandle(layerInfoClass, "layer", ModuleLayer.class);

            String moduleName = EcaTransformationService.class.getModule().getName();
            if (moduleName == null) {
                log("[DualLoading] Module name is null, skipping module layer removal");
                return;
            }

            for (Object layerInfo : completedLayers.values()) {
                ModuleLayer layer = (ModuleLayer) layerHandle.get(layerInfo);
                Configuration config = layer.configuration();

                Field nameToModuleField = Configuration.class.getDeclaredField("nameToModule");
                Field modulesField = Configuration.class.getDeclaredField("modules");

                long nameToModuleOffset = unsafe.objectFieldOffset(nameToModuleField);
                long modulesOffset = unsafe.objectFieldOffset(modulesField);

                Map<String, ?> nameToModule = (Map<String, ?>) unsafe.getObject(config, nameToModuleOffset);
                Set<?> modules = (Set<?>) unsafe.getObject(config, modulesOffset);

                if (nameToModule != null && nameToModule.containsKey(moduleName)) {
                    Map<String, Object> newNameToModule = new HashMap<>(nameToModule);
                    Set<Object> newModules = new HashSet<>(modules);

                    Object removed = newNameToModule.remove(moduleName);
                    if (removed != null) {
                        newModules.remove(removed);
                        log("[DualLoading] Removed module from layer: " + moduleName);
                    }

                    unsafe.putObject(config, nameToModuleOffset, newNameToModule);
                    unsafe.putObject(config, modulesOffset, newModules);
                }
            }

        } catch (Exception e) {
            log("[DualLoading] Failed to remove from module layer: " + e.getMessage());
        }
    }

    // ==================== 工具方法 ====================

    private static void log(String message) {
        try {
            AgentLogWriter.info("[CoreMod] " + message);
        } catch (Throwable ignored) {
        }
    }

    private static Unsafe getUnsafe() throws Exception {
        Field unsafeField = Unsafe.class.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        return (Unsafe) unsafeField.get(null);
    }
}
