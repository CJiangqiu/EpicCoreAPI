package net.eca.agent;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.RandomAccessFile;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public final class CoremodServiceFilter {
    private static final String DESCRIPTOR =
            "META-INF/services/cpw.mods.modlauncher.api.ITransformationService";
    private static final String ECA_PREFIX = "net.eca.";
    private static final List<String> PLATFORM_PREFIXES = List.of(
            "cpw.mods.modlauncher.",
            "net.minecraftforge.",
            "org.spongepowered.",
            "com.mojang.",
            "net.minecraft."
    );
    private static final AtomicBoolean READY_SIGNALED = new AtomicBoolean();
    private static final Set<Path> BLOCKED_PATHS = ConcurrentHashMap.newKeySet();

    private CoremodServiceFilter() {
    }

    public static List<?> filter(List<?> candidates) {
        List<Object> kept = new ArrayList<>(candidates.size());
        int removed = 0;
        for (Object candidate : candidates) {
            Inspection inspection = inspect(candidate);
            if (inspection.blockedProviders().isEmpty()) {
                kept.add(candidate);
            } else {
                removed++;
                BLOCKED_PATHS.addAll(inspection.paths());
                log("Blocked transformation-service candidate paths=" + inspection.paths()
                        + " providers=" + inspection.blockedProviders());
            }
        }
        log("Transformation-service filter complete input=" + candidates.size()
                + " kept=" + kept.size() + " removed=" + removed);
        signalPolicyApplied();
        return kept;
    }

    public static List<Path> filterExcluded(List<Path> excluded) {
        if (excluded.isEmpty() || BLOCKED_PATHS.isEmpty()) return excluded;
        List<Path> kept = new ArrayList<>(excluded.size());
        int restored = 0;
        for (Path path : excluded) {
            Path normalized = path.toAbsolutePath().normalize();
            if (BLOCKED_PATHS.contains(normalized)) {
                restored++;
            } else {
                kept.add(path);
            }
        }
        if (restored > 0) log("Restored " + restored + " blocked service archive(s) to normal mod discovery");
        return kept;
    }

    private static Inspection inspect(Object candidate) {
        Set<Path> paths = new LinkedHashSet<>();
        Set<String> blocked = new LinkedHashSet<>();
        try {
            paths.addAll(candidatePaths(candidate));
            for (Path path : paths) {
                for (String provider : providers(path)) {
                    if (!isAllowedProvider(provider, path)) blocked.add(provider);
                }
            }
        } catch (Throwable t) {
            blocked.add("inspection_failed:" + t.getClass().getSimpleName());
        }
        return new Inspection(paths, blocked);
    }

    private static Set<Path> candidatePaths(Object candidate) throws Exception {
        Set<Path> paths = new LinkedHashSet<>();
        if (candidate == null) return paths;
        Method accessor = candidate.getClass().getMethod("paths");
        Object value = accessor.invoke(candidate);
        if (value instanceof Path[] array) {
            for (Path path : array) if (path != null) paths.add(path.toAbsolutePath().normalize());
        } else if (value instanceof Iterable<?> iterable) {
            for (Object item : iterable) {
                if (item instanceof Path path) paths.add(path.toAbsolutePath().normalize());
            }
        }
        return paths;
    }

    private static Set<String> providers(Path path) throws Exception {
        Set<String> providers = new LinkedHashSet<>();
        if (Files.isDirectory(path)) {
            Path descriptor = path.resolve(DESCRIPTOR);
            if (Files.isRegularFile(descriptor)) {
                try (BufferedReader reader = Files.newBufferedReader(descriptor, StandardCharsets.UTF_8)) {
                    readProviders(reader, providers);
                }
            }
        } else if (Files.isRegularFile(path)) {
            try (ZipFile archive = new ZipFile(path.toFile())) {
                ZipEntry entry = archive.getEntry(DESCRIPTOR);
                if (entry != null) {
                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                            archive.getInputStream(entry), StandardCharsets.UTF_8))) {
                        readProviders(reader, providers);
                    }
                }
            }
        }
        return providers;
    }

    private static void readProviders(BufferedReader reader, Set<String> providers) throws Exception {
        String line;
        while ((line = reader.readLine()) != null) {
            int comment = line.indexOf('#');
            if (comment >= 0) line = line.substring(0, comment);
            line = line.trim();
            if (!line.isEmpty()) providers.add(line);
        }
    }

    private static boolean isAllowedProvider(String provider, Path candidatePath) {
        if (provider.startsWith(ECA_PREFIX)) return isTrustedEca(candidatePath);
        for (String prefix : PLATFORM_PREFIXES) {
            if (provider.startsWith(prefix)) return true;
        }
        return configuredPrefixes().stream().anyMatch(provider::startsWith);
    }

    private static List<String> configuredPrefixes() {
        String value = System.getProperty("net.eca.bootstrap.coremodWhitelist", "");
        if (value.isBlank()) return List.of();
        return Arrays.stream(value.split("\\R"))
                .map(String::trim)
                .filter(prefix -> !prefix.isEmpty())
                .map(CoremodServiceFilter::normalizePrefix)
                .toList();
    }

    private static String normalizePrefix(String prefix) {
        String normalized = prefix.replace('/', '.');
        while (normalized.endsWith("*")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private static boolean isTrustedEca(Path candidatePath) {
        try {
            Path expected = Path.of(System.getProperty("net.eca.bootstrap.trustedModPath"))
                    .toAbsolutePath().normalize();
            String expectedHash = System.getProperty("net.eca.bootstrap.trustedModSha256", "");
            return Files.isSameFile(expected, candidatePath)
                    && expectedHash.equalsIgnoreCase(sha256(candidatePath));
        } catch (Throwable t) {
            return false;
        }
    }

    private static String sha256(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (var input = Files.newInputStream(path)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void signalPolicyApplied() {
        if (!READY_SIGNALED.compareAndSet(false, true)) return;
        String pipe = System.getProperty("forgevm.relaunch.readyPipe");
        String nonce = System.getProperty("forgevm.relaunch.readyNonce");
        if (pipe == null || pipe.isBlank() || nonce == null || nonce.isBlank()) {
            READY_SIGNALED.set(false);
            log("Policy handoff properties unavailable");
            return;
        }
        try (RandomAccessFile ready = new RandomAccessFile(pipe, "rw")) {
            ready.write((nonce + "\n").getBytes(StandardCharsets.UTF_8));
            System.clearProperty("forgevm.relaunch.readyNonce");
            log("Trusted relaunch policy handoff completed");
        } catch (Throwable t) {
            READY_SIGNALED.set(false);
            log("Trusted relaunch policy handoff failed: " + t.getClass().getSimpleName());
        }
    }

    private static synchronized void log(String message) {
        try {
            Path log = Path.of(System.getProperty("user.dir"), "logs", "EcaAgent.log");
            Files.createDirectories(log.getParent());
            Files.writeString(log, LocalDateTime.now() + " [Bootstrap] " + message + System.lineSeparator(),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Throwable ignored) {
        }
    }

    private record Inspection(Set<Path> paths, Set<String> blockedProviders) {
    }
}
