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

@SuppressWarnings("unchecked")
public class EcaTransformationService implements ITransformationService {

    static {
        attachAgentEarly();
        enableEcaDualLoading();
    }

    private static final String SERVICE_NAME = "eca_coremod";

    // 与 gradle.properties 的 archives_name 保持一致
    private static final String ARCHIVES_NAME = "epic-core-api";

    private static String ecaModuleName;

    public EcaTransformationService() {
        log("TransformationService constructor called");
    }

    @Override
    public @NotNull String name() {
        return SERVICE_NAME;
    }

    @Override
    public void onLoad(@NotNull IEnvironment env, @NotNull Set<String> otherServices) {
        log("onLoad - other services count: " + otherServices.size());
    }

    private static void attachAgentEarly() {
        try {
            ClassLoader coreLoader = EcaTransformationService.class.getClassLoader();
            Class<?> agentLoaderClass = Class.forName("net.eca.agent.AgentLoader", true, coreLoader);
            boolean selfAttachEnabled = (boolean) agentLoaderClass.getMethod("enableSelfAttach").invoke(null);
            log("Self-attach enabled: " + selfAttachEnabled);

            boolean agentLoaded = (boolean) agentLoaderClass.getMethod("loadAgent", Class.class).invoke(null, new Object[]{null});
            log("Agent early attach: " + agentLoaded);
        } catch (ClassNotFoundException e) {
            log("AgentLoader not found in early layer, fallback to mod init stage");
        } catch (Throwable t) {
            log("Failed to attach agent early: " + t.getMessage());
        }
    }

    @Override
    public void initialize(@NotNull IEnvironment environment) {
        log("initialize");
    }

    @Override
    public @NotNull List<Resource> beginScanning(@NotNull IEnvironment environment) {
        log("beginScanning");
        return List.of();
    }

    @Override
    public @NotNull List<Resource> completeScan(@NotNull IModuleLayerManager layerManager) {
        log("completeScan");
        return List.of();
    }

    @Override
    public @NotNull List<ITransformer> transformers() {
        log("transformers() called");
        return List.of();
    }

    // ==================== 双重加载 ====================

    public static void enableEcaDualLoading() {
        ecaModuleName = EcaTransformationService.class.getModule().getName();
        log("[DualLoading] ecaModuleName = " + ecaModuleName + ", archivesName = " + ARCHIVES_NAME);
        removeEcaFromTransformerDiscovery();
        removeEcaFromModuleLayer();
        log("[DualLoading] Dual loading completed");
    }

    private static void removeEcaFromTransformerDiscovery() {
        try {
            Class<?> discovererClass = Class.forName(
                    "net.minecraftforge.fml.loading.ModDirTransformerDiscoverer");

            VarHandle foundHandle = MethodHandles
                    .privateLookupIn(discovererClass, MethodHandles.lookup())
                    .findStaticVarHandle(discovererClass, "found", List.class);

            List<Object> found = (List<Object>) foundHandle.get();

            log("[DualLoading] found list size = " + found.size());
            for (Object namedPath : found) {
                try {
                    String name = (String) namedPath.getClass().getMethod("name").invoke(namedPath);
                    Path[] paths = (Path[]) namedPath.getClass().getMethod("paths").invoke(namedPath);
                    log("[DualLoading] found entry: name=" + name + ", path=" + paths[0]);
                } catch (Exception e) {
                    log("[DualLoading] found entry: " + namedPath);
                }
            }

            found.removeIf(namedPath -> {
                try {
                    Path[] paths = (Path[]) namedPath.getClass().getMethod("paths").invoke(namedPath);
                    String fileName = paths[0].getFileName().toString();
                    boolean isEca = fileName.startsWith(ARCHIVES_NAME);

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
            Class<?> logClass = Class.forName("net.eca.agent.AgentLogWriter",
                    false, EcaTransformationService.class.getClassLoader());
            logClass.getMethod("info", String.class).invoke(null, "[CoreMod] " + message);
        } catch (Throwable ignored) {
        }
    }

    private static Unsafe getEcaUnsafe() throws Exception {
        Field unsafeField = Unsafe.class.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        return (Unsafe) unsafeField.get(null);
    }

}
