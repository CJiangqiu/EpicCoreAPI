package net.eca.pro;

import forgevm.core.ForgeVM;
import forgevm.core.ForgeVMOptions;
import forgevm.jvm.RelaunchException;
import forgevm.jvm.RelaunchSpec;
import net.eca.agent.AgentLogWriter;

import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.CodeSource;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.function.Predicate;

public final class EcaProRuntime {
    private static final String AGENT_RESOURCE = "/net/eca/agent/agent.jar";
    private static final String INTRO_CALLBACK_KEY = "net.eca.pro.intro.callback";
    private static final Predicate<Object> INTRO_CALLBACK = EcaProRuntime::renderIntro;
    private static volatile EcaProConfig config;
    private static volatile Path trustedAgent;
    private static volatile boolean active;
    private static volatile boolean interceptionInstalled;

    private EcaProRuntime() {
    }

    public static synchronized void prepareEarly() {
        if (config != null) return;
        config = EcaProConfig.load();
        if (config.introEnabled && EcaProIntroRenderer.prepareResources()) {
            System.getProperties().put(INTRO_CALLBACK_KEY, INTRO_CALLBACK);
            AgentLogWriter.info("[EcaProIntro] Animation callback registered");
        } else {
            System.getProperties().remove(INTRO_CALLBACK_KEY);
        }
        if (!config.enabled) {
            AgentLogWriter.info("[EcaPro] Disabled by configuration");
            return;
        }
        ForgeVM.LaunchResult result = ForgeVM.launch(ForgeVMOptions.builder()
                .window(config.statusWindow)
                .lockJvm(config.lockJvm)
                .build());
        active = result.nativeDllActive() && ForgeVM.isAgentActive();
        if (!active) {
            AgentLogWriter.info("[EcaPro] Runtime controller unavailable: " + result.reason());
            return;
        }
        AgentLogWriter.info("[EcaPro] Runtime controller active: " + result.agentStatus());
        if (config.coremod && !config.relaunch) {
            AgentLogWriter.info("[EcaPro] Transformation-service interception requires trusted JVM relaunch");
        }
        Path relaunchedAgent = Path.of(System.getProperty("user.dir"),
                "ECA", "runtime", "pro", "eca-agent.jar").toAbsolutePath().normalize();
        if (ForgeVM.relaunchGeneration() > 0 && Files.isRegularFile(relaunchedAgent)) {
            trustedAgent = relaunchedAgent;
        }
        if (config.relaunch && ForgeVM.relaunchGeneration() == 0) relaunch();
    }

    public static synchronized void afterAgentReady() {
        if (!active || interceptionInstalled || config == null) return;
        EcaProInterception.install(config, trustedAgent);
        interceptionInstalled = true;
    }

    public static synchronized void refreshInterception() {
        if (!active || !interceptionInstalled || config == null) return;
        EcaProInterception.refreshSourceFilters(config);
    }

    public static boolean renderIntro(Object displayContext) {
        EcaProConfig current = config;
        if (current == null || !current.introEnabled) return false;
        try {
            boolean rendered = EcaProIntroRenderer.render(displayContext);
            if (!rendered) System.getProperties().remove(INTRO_CALLBACK_KEY, INTRO_CALLBACK);
            return rendered;
        } catch (Throwable t) {
            System.getProperties().remove(INTRO_CALLBACK_KEY, INTRO_CALLBACK);
            AgentLogWriter.info("[EcaProIntro] Animation callback failed: " + t.getMessage());
            return false;
        }
    }

    static boolean isActive() {
        return active && ForgeVM.isAgentActive();
    }

    private static void relaunch() {
        try {
            trustedAgent = extractTrustedAgent();
            RelaunchSpec.Builder builder = RelaunchSpec.builder()
                    .existingAgents(config.dropExistingAgents
                            ? RelaunchSpec.ExistingAgentPolicy.DROP_ALL
                            : RelaunchSpec.ExistingAgentPolicy.PRESERVE)
                    .trustedJavaAgent(trustedAgent, sha256(trustedAgent), bootstrapArguments())
                    .sanitizeEnvironment(config.sanitizeEnvironment)
                    .rejectArgumentFiles(config.rejectArgumentFiles)
                    .handoff(RelaunchSpec.HandoffPoint.POLICY_APPLIED, Duration.ofSeconds(45));
            EcaProInterception.applyToRelaunch(builder, config, trustedAgent);
            AgentLogWriter.info("[EcaPro] Starting trusted JVM relaunch");
            ForgeVM.relaunch(builder.build());
        } catch (RelaunchException e) {
            AgentLogWriter.info("[EcaPro] Trusted JVM relaunch rejected: " + e.getMessage());
        } catch (Throwable t) {
            AgentLogWriter.info("[EcaPro] Trusted JVM relaunch failed: " + t);
        }
    }

    private static String bootstrapArguments() throws Exception {
        String whitelist = String.join("\n", config.coremodPackages);
        String encodedWhitelist = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(whitelist.getBytes(StandardCharsets.UTF_8));
        String arguments = "eca-bootstrap:coremodBan=" + config.coremod
                + ",coremodWhitelist=" + encodedWhitelist;
        if (!config.coremod) return arguments;

        Path archive = resolveOwnArchive();
        String encodedPath = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(archive.toString().getBytes(StandardCharsets.UTF_8));
        return arguments + ",trustedModPath=" + encodedPath + ",trustedModSha256=" + sha256(archive);
    }

    private static Path resolveOwnArchive() throws Exception {
        CodeSource codeSource = EcaProRuntime.class.getProtectionDomain().getCodeSource();
        if (codeSource != null && codeSource.getLocation() != null) {
            URI location = codeSource.getLocation().toURI();
            if ("file".equalsIgnoreCase(location.getScheme())) {
                Path direct = Path.of(location).toAbsolutePath().normalize();
                if (Files.isRegularFile(direct)) return direct;
            }
        }

        String resourceName = EcaProRuntime.class.getName().replace('.', '/') + ".class";
        URL resource = EcaProRuntime.class.getClassLoader().getResource(resourceName);
        if (resource == null) throw new IllegalStateException("trusted_mod_resource_missing");
        String value = resource.toString();
        int separator = value.indexOf('!');
        if (separator < 0) throw new IllegalStateException("trusted_mod_archive_unresolved");

        String prefix = value.substring(0, separator);
        String rawPath;
        if (prefix.startsWith("union:")) {
            rawPath = prefix.substring("union:".length());
            int archiveIndex = rawPath.lastIndexOf("%23");
            if (archiveIndex >= 0) rawPath = rawPath.substring(0, archiveIndex);
        } else {
            int fileIndex = prefix.indexOf("file:");
            if (fileIndex < 0) throw new IllegalStateException("trusted_mod_archive_unresolved");
            rawPath = prefix.substring(fileIndex + "file:".length());
        }

        String decoded = URLDecoder.decode(rawPath, StandardCharsets.UTF_8);
        if (decoded.length() > 2 && decoded.charAt(0) == '/' && decoded.charAt(2) == ':') {
            decoded = decoded.substring(1);
        }
        Path archive = Path.of(decoded).toAbsolutePath().normalize();
        if (!Files.isRegularFile(archive)) throw new IllegalStateException("trusted_mod_archive_unresolved");
        return archive;
    }

    private static Path extractTrustedAgent() throws Exception {
        Path directory = Path.of(System.getProperty("user.dir"), "ECA", "runtime", "pro");
        Files.createDirectories(directory);
        Path archive = directory.resolve("eca-agent.jar");
        try (InputStream input = EcaProRuntime.class.getResourceAsStream(AGENT_RESOURCE)) {
            if (input == null) throw new IllegalStateException("embedded_agent_missing");
            Files.copy(input, archive, StandardCopyOption.REPLACE_EXISTING);
        }
        return archive.toAbsolutePath().normalize();
    }

    private static String sha256(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(path)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
