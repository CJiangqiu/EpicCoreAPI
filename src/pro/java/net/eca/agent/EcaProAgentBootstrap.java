package net.eca.agent;

import java.io.InputStream;
import java.lang.instrument.Instrumentation;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

public final class EcaProAgentBootstrap {
    private static JarFile bootstrapHelperJar;

    private EcaProAgentBootstrap() {
    }

    public static boolean install(String arguments, Instrumentation instrumentation) {
        try {
            Map<String, String> values = parseArguments(arguments);
            if (!Boolean.parseBoolean(values.getOrDefault("coremodBan", "false"))) {
                AgentLogWriter.info("[EcaProAgent] Early service interception disabled");
                return false;
            }

            System.setProperty("net.eca.bootstrap.coremodWhitelist",
                    decode(values.getOrDefault("coremodWhitelist", "")));
            System.setProperty("net.eca.bootstrap.trustedModPath", decodeRequired(values, "trustedModPath"));
            System.setProperty("net.eca.bootstrap.trustedModSha256", required(values, "trustedModSha256"));

            CoremodServiceTransformer transformer = new CoremodServiceTransformer(instrumentation);
            Path helper = createBootstrapHelperJar();
            bootstrapHelperJar = new JarFile(helper.toFile());
            instrumentation.appendToBootstrapClassLoaderSearch(bootstrapHelperJar);
            Class<?> filter = Class.forName(CoremodServiceFilter.class.getName(), false, null);
            transformer.setBootstrapHelperModule(filter.getModule());
            instrumentation.addTransformer(transformer, false);
            AgentLogWriter.info("[EcaProAgent] Early service interception armed");
            return true;
        } catch (Throwable t) {
            AgentLogWriter.error("[EcaProAgent] Early service interception failed", t);
            return false;
        }
    }

    private static Path createBootstrapHelperJar() throws Exception {
        Path directory = Path.of(System.getProperty("user.dir"), "ECA", "runtime", "pro", "bootstrap");
        Files.createDirectories(directory);
        Path archive = Files.createTempFile(directory, "eca-service-filter-", ".jar");
        archive.toFile().deleteOnExit();
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(archive))) {
            addBootstrapClass(output, CoremodServiceFilter.class);
            for (Class<?> nested : CoremodServiceFilter.class.getDeclaredClasses()) {
                addBootstrapClass(output, nested);
            }
        }
        return archive;
    }

    private static void addBootstrapClass(JarOutputStream output, Class<?> type) throws Exception {
        String resource = "/" + type.getName().replace('.', '/') + ".class";
        try (InputStream input = EcaProAgentBootstrap.class.getResourceAsStream(resource)) {
            if (input == null) throw new IllegalStateException("bootstrap_helper_missing");
            output.putNextEntry(new JarEntry(resource.substring(1)));
            input.transferTo(output);
            output.closeEntry();
        }
    }

    private static Map<String, String> parseArguments(String arguments) {
        Map<String, String> values = new HashMap<>();
        for (String part : arguments.split(",")) {
            int separator = part.indexOf('=');
            if (separator > 0) values.put(part.substring(0, separator), part.substring(separator + 1));
        }
        return values;
    }

    private static String decodeRequired(Map<String, String> values, String key) {
        return decode(required(values, key));
    }

    private static String required(Map<String, String> values, String key) {
        String value = values.get(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("missing_" + key);
        return value;
    }

    private static String decode(String value) {
        if (value == null || value.isEmpty()) return "";
        return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
    }
}
