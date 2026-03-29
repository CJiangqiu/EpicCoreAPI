package net.eca.coremod;

import cpw.mods.modlauncher.Launcher;
import cpw.mods.modlauncher.api.IEnvironment;
import cpw.mods.modlauncher.api.IModuleLayerManager;
import cpw.mods.modlauncher.api.ITransformationService;
import cpw.mods.modlauncher.api.ITransformer;
import org.jetbrains.annotations.NotNull;
import sun.misc.Unsafe;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.lang.module.Configuration;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.*;

/**
 * ECA CoreMod （目前测试ing：启动agent来替代原来的主类构造函数启动agent）
 */
@SuppressWarnings("unchecked")
public class EcaTransformationService implements ITransformationService {

    static {
        System.out.println("[ECA CoreMod] Static block : I'm first?");
        // 先尝试启动 Agent，避免双加载调整后影响 AgentLoader 可见性
        attachAgentEarly();
        // 再执行双加载，确保后续阶段按预期识别
        enableEcaDualLoading();
    }

    private static final String SERVICE_NAME = "eca_coremod";

    // 当前 JAR 的绝对路径（在双加载时懒加载解析）
    private static volatile Path ecaJarPath;

    public EcaTransformationService() {
        System.out.println("[ECA CoreMod] TransformationService constructor called");
    }

    @Override
    public @NotNull String name() {
        return SERVICE_NAME;
    }

    @Override
    public void onLoad(@NotNull IEnvironment env, @NotNull Set<String> otherServices) {
        System.out.println("[ECA CoreMod] onLoad - other services count: " + otherServices.size());
    }

    /**
     * 在 CoreMod 阶段提前挂载 Java Agent
     * 此时游戏窗口尚未创建，Agent 的 ClassFileTransformer 能拦截所有后续类加载
     */
    private static void attachAgentEarly() {
        try {
            ClassLoader coreLoader = EcaTransformationService.class.getClassLoader();
            Class<?> agentLoaderClass = Class.forName("net.eca.agent.AgentLoader", true, coreLoader);
            boolean selfAttachEnabled = (boolean) agentLoaderClass.getMethod("enableSelfAttach").invoke(null);
            System.out.println("[ECA CoreMod] Self-attach enabled: " + selfAttachEnabled);

            boolean agentLoaded = (boolean) agentLoaderClass.getMethod("loadAgent", Class.class).invoke(null, new Object[]{null});
            System.out.println("[ECA CoreMod] Agent early attach: " + agentLoaded);
        } catch (ClassNotFoundException e) {
            System.err.println("[ECA CoreMod] AgentLoader not found in early layer, fallback to mod init stage");
        } catch (Throwable t) {
            System.err.println("[ECA CoreMod] Failed to attach agent early: " + t.getMessage());
            t.printStackTrace();
        }
    }

    @Override
    public void initialize(@NotNull IEnvironment environment) {
        System.out.println("[ECA CoreMod] initialize");
    }

    @Override
    public @NotNull List<Resource> beginScanning(@NotNull IEnvironment environment) {
        System.out.println("[ECA CoreMod] beginScanning");
        return List.of();
    }

    @Override
    public @NotNull List<Resource> completeScan(@NotNull IModuleLayerManager layerManager) {
        System.out.println("[ECA CoreMod] completeScan");
        return List.of();
    }

    @Override
    public @NotNull List<ITransformer> transformers() {
        System.out.println("[ECA CoreMod] transformers() called");
        return List.of();
    }

    // ==================== 核心：启用双重加载 ====================

    /**
     * 启用 ECA 双重加载模式
     */
    public static void enableEcaDualLoading() {
        ecaJarPath = resolveJarPath();
        removeEcaFromTransformerDiscovery();
        removeEcaFromModuleLayer();
        System.out.println("[ECA CoreMod] Dual loading enabled successfully");
    }

    /**
     * 从 ModDirTransformerDiscoverer.found 列表中移除 ECA
     */
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
                    Path[] paths = (Path[]) namedPath.getClass()
                            .getMethod("paths")
                            .invoke(namedPath);

                    Path jarPath = paths[0];
                    boolean isEca = ecaJarPath != null && isSameFile(jarPath, ecaJarPath);

                    if (isEca) {
                        System.out.println("[ECA CoreMod] Removed from transformer discovery: " + jarPath);
                    }
                    return isEca;
                } catch (Exception e) {
                    return false;
                }
            });

        } catch (Exception e) {
            System.err.println("[ECA CoreMod] Failed to remove from transformer discovery: " + e.getMessage());
        }
    }

    /**
     * 从 ModuleLayer 配置中移除 ECA 模块
     */
    private static void removeEcaFromModuleLayer() {
        try {
            Unsafe unsafe = getEcaUnsafe();

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
                System.out.println("[ECA CoreMod] Module name is null, skipping module layer removal");
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
                        System.out.println("[ECA CoreMod] Removed module from layer: " + moduleName);
                    }

                    unsafe.putObject(config, nameToModuleOffset, newNameToModule);
                    unsafe.putObject(config, modulesOffset, newModules);
                }
            }

        } catch (Exception e) {
            System.err.println("[ECA CoreMod] Failed to remove from module layer: " + e.getMessage());
        }
    }

    // ==================== 工具方法 ====================

    private static Path resolveJarPath() {
        try {
            java.security.CodeSource cs = EcaTransformationService.class.getProtectionDomain().getCodeSource();
            if (cs != null && cs.getLocation() != null) {
                Path resolved = Path.of(cs.getLocation().toURI()).toAbsolutePath().normalize();
                System.out.println("[ECA CoreMod] Resolved JAR path: " + resolved);
                return resolved;
            }
        } catch (Throwable t) {
            System.err.println("[ECA CoreMod] Failed to resolve JAR path: " + t.getMessage());
        }
        return null;
    }

    private static boolean isSameFile(Path a, Path b) {
        try {
            return java.nio.file.Files.isSameFile(a, b);
        } catch (Throwable t) {
            return a.toAbsolutePath().normalize().equals(b.toAbsolutePath().normalize());
        }
    }

    private static Unsafe getEcaUnsafe() throws Exception {
        Field unsafeField = Unsafe.class.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        return (Unsafe) unsafeField.get(null);
    }

}
