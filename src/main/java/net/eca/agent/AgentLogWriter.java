package net.eca.agent;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

// Agent专用日志写入器
/**
 * Agent log file writer.
 * Writes agent logs directly to a separate file since SLF4J may not be available during agent initialization.
 */
public final class AgentLogWriter {

    private static BufferedWriter writer = null;
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    private static boolean initialized = false;

    private static final String LOG_FILE_NAME = "EcaAgent.log";

    // 初始化日志文件
    /**
     * Initialize the log file.
     * Creates the logs directory if it doesn't exist.
     */
    private static synchronized void initialize() {
        if (initialized) return;
        initialized = true;

        try {
            Path logsDir = Paths.get("logs");
            if (!Files.exists(logsDir)) {
                Files.createDirectories(logsDir);
            }

            Path logFile = logsDir.resolve(LOG_FILE_NAME);
            writer = new BufferedWriter(new FileWriter(logFile.toFile(), false));

            writer.write("========================================\n");
            writer.write("EpicCoreAPI - Agent Log\n");
            writer.write("Started at: " + LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME) + "\n");
            writer.write("========================================\n\n");
            writer.flush();

        } catch (IOException e) {
            writer = null;
        }
    }

    // 写入日志
    /**
     * Write a log message.
     * @param level the log level (INFO, WARN, ERROR, DEBUG)
     * @param message the message to log
     */
    public static synchronized void log(String level, String message) {
        initialize();

        if (writer == null) return;

        try {
            String timestamp = LocalDateTime.now().format(TIME_FORMATTER);
            writer.write(String.format("[%s] [%s] %s\n", timestamp, level, message));
            writer.flush();
        } catch (IOException e) {
            // Ignore write errors
        }
    }

    // 写入带异常的日志
    /**
     * Write a log message with throwable.
     * @param level the log level
     * @param message the message to log
     * @param throwable the exception to log
     */
    public static synchronized void log(String level, String message, Throwable throwable) {
        log(level, message);

        if (writer == null || throwable == null) return;

        try {
            writer.write("  Exception: " + throwable.getClass().getName() + ": " + throwable.getMessage() + "\n");

            StackTraceElement[] stackTrace = throwable.getStackTrace();
            int limit = Math.min(stackTrace.length, 10);
            for (int i = 0; i < limit; i++) {
                writer.write("    at " + stackTrace[i].toString() + "\n");
            }
            if (stackTrace.length > 10) {
                writer.write("    ... " + (stackTrace.length - 10) + " more\n");
            }

            writer.flush();
        } catch (IOException e) {
            // Ignore write errors
        }
    }

    // INFO级别日志
    /**
     * Log an info message.
     * @param message the message to log
     */
    public static void info(String message) {
        log("INFO", message);
    }

    // WARN级别日志
    /**
     * Log a warning message.
     * @param message the message to log
     */
    public static void warn(String message) {
        log("WARN", message);
    }

    // ERROR级别日志
    /**
     * Log an error message.
     * @param message the message to log
     */
    public static void error(String message) {
        log("ERROR", message);
    }

    // ERROR级别日志（带异常）
    /**
     * Log an error message with throwable.
     * @param message the message to log
     * @param throwable the exception to log
     */
    public static void error(String message, Throwable throwable) {
        log("ERROR", message, throwable);
    }

    // DEBUG级别日志
    /**
     * Log a debug message.
     * @param message the message to log
     */
    public static void debug(String message) {
        log("DEBUG", message);
    }

    // 关闭日志文件
    /**
     * Close the log file.
     */
    public static synchronized void close() {
        if (writer != null) {
            try {
                writer.write("\n========================================\n");
                writer.write("Agent log closed at: " + LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME) + "\n");
                writer.write("========================================\n");
                writer.close();
            } catch (IOException e) {
                // Ignore close errors
            }
            writer = null;
        }
    }

    private AgentLogWriter() {}
}
