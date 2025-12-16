package net.eca.util;

import com.mojang.logging.LogUtils;
import net.eca.api.ILoggerDelegate;
import org.slf4j.Logger;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

// ECA日志系统 - 支持统一前缀和多mod委托
/**
 * ECA logging system with unified prefix and multi-mod delegation support.
 * Other mods can implement ILoggerDelegate and register to use ECA's logging system.
 */
public final class EcaLogger {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String ECA_PREFIX = "[ECA]";

    // 已注册的委托日志器缓存 (modId -> ModLogger)
    private static final Map<String, ModLogger> MOD_LOGGERS = new ConcurrentHashMap<>();

    // ==================== ECA主日志方法 ====================

    // 获取原始Logger
    /**
     * Get the underlying SLF4J logger.
     * @return the SLF4J logger instance
     */
    public static Logger getLogger() {
        return LOGGER;
    }

    // INFO级别日志
    /**
     * Log an info message with [ECA] prefix.
     * @param msg the message to log
     */
    public static void info(String msg) {
        LOGGER.info("{} {}", ECA_PREFIX, msg);
    }

    // INFO级别日志（带参数）
    /**
     * Log an info message with [ECA] prefix and arguments.
     * @param fmt the format string
     * @param args the arguments
     */
    public static void info(String fmt, Object... args) {
        LOGGER.info(ECA_PREFIX + " " + fmt, args);
    }

    // WARN级别日志
    /**
     * Log a warning message with [ECA] prefix.
     * @param msg the message to log
     */
    public static void warn(String msg) {
        LOGGER.warn("{} {}", ECA_PREFIX, msg);
    }

    // WARN级别日志（带参数）
    /**
     * Log a warning message with [ECA] prefix and arguments.
     * @param fmt the format string
     * @param args the arguments
     */
    public static void warn(String fmt, Object... args) {
        LOGGER.warn(ECA_PREFIX + " " + fmt, args);
    }

    // ERROR级别日志
    /**
     * Log an error message with [ECA] prefix.
     * @param msg the message to log
     */
    public static void error(String msg) {
        LOGGER.error("{} {}", ECA_PREFIX, msg);
    }

    // ERROR级别日志（带参数）
    /**
     * Log an error message with [ECA] prefix and arguments.
     * @param fmt the format string
     * @param args the arguments
     */
    public static void error(String fmt, Object... args) {
        LOGGER.error(ECA_PREFIX + " " + fmt, args);
    }

    // ERROR级别日志（带异常）
    /**
     * Log an error message with [ECA] prefix and throwable.
     * @param msg the message to log
     * @param throwable the exception to log
     */
    public static void error(String msg, Throwable throwable) {
        LOGGER.error("{} {}", ECA_PREFIX, msg, throwable);
    }

    // DEBUG级别日志
    /**
     * Log a debug message with [ECA] prefix.
     * @param msg the message to log
     */
    public static void debug(String msg) {
        LOGGER.debug("{} {}", ECA_PREFIX, msg);
    }

    // DEBUG级别日志（带参数）
    /**
     * Log a debug message with [ECA] prefix and arguments.
     * @param fmt the format string
     * @param args the arguments
     */
    public static void debug(String fmt, Object... args) {
        LOGGER.debug(ECA_PREFIX + " " + fmt, args);
    }

    // TRACE级别日志
    /**
     * Log a trace message with [ECA] prefix.
     * @param msg the message to log
     */
    public static void trace(String msg) {
        LOGGER.trace("{} {}", ECA_PREFIX, msg);
    }

    // TRACE级别日志（带参数）
    /**
     * Log a trace message with [ECA] prefix and arguments.
     * @param fmt the format string
     * @param args the arguments
     */
    public static void trace(String fmt, Object... args) {
        LOGGER.trace(ECA_PREFIX + " " + fmt, args);
    }

    // ==================== Mod委托注册 ====================

    // 通过委托接口注册mod日志器
    /**
     * Register a mod logger using the ILoggerDelegate interface.
     * The prefix will be delegate.getLogPrefix(), defaults to mod id if not overridden.
     * @param delegate the logger delegate implementation
     * @return the ModLogger instance for this mod
     */
    public static ModLogger register(ILoggerDelegate delegate) {
        String modId = delegate.getModId();
        String prefix = delegate.getLogPrefix();
        return MOD_LOGGERS.computeIfAbsent(modId, k -> new ModLogger(prefix));
    }

    // 通过mod id和自定义前缀注册
    /**
     * Register a mod logger with custom prefix.
     * @param modId the mod id (used as key)
     * @param displayPrefix the display prefix for log messages
     * @return the ModLogger instance
     */
    public static ModLogger register(String modId, String displayPrefix) {
        return MOD_LOGGERS.computeIfAbsent(modId, k -> new ModLogger(displayPrefix));
    }

    // 通过mod id注册（前缀使用mod id）
    /**
     * Register a mod logger using mod id as prefix.
     * @param modId the mod id (used as both key and prefix)
     * @return the ModLogger instance
     */
    public static ModLogger register(String modId) {
        return register(modId, modId);
    }

    // 获取已注册的mod日志器
    /**
     * Get a registered mod logger by mod id.
     * @param modId the mod id
     * @return the ModLogger instance, or null if not registered
     */
    public static ModLogger get(String modId) {
        return MOD_LOGGERS.get(modId);
    }

    // 获取或注册mod日志器
    /**
     * Get or register a mod logger by mod id.
     * If not registered, will register with mod id as prefix.
     * @param modId the mod id
     * @return the ModLogger instance
     */
    public static ModLogger getOrRegister(String modId) {
        return MOD_LOGGERS.computeIfAbsent(modId, ModLogger::new);
    }

    // 检查mod是否已注册
    /**
     * Check if a mod logger is registered.
     * @param modId the mod id
     * @return true if registered
     */
    public static boolean isRegistered(String modId) {
        return MOD_LOGGERS.containsKey(modId);
    }

    // 注销mod日志器
    /**
     * Unregister a mod logger.
     * @param modId the mod id
     */
    public static void unregister(String modId) {
        MOD_LOGGERS.remove(modId);
    }

    // ==================== ModLogger委托日志器 ====================

    // Mod委托日志器
    /**
     * A logger that delegates to ECA's log stream with a custom mod prefix.
     * Output format: [ECA/Prefix] message
     */
    public static final class ModLogger {

        private final String prefix;

        private ModLogger(String displayPrefix) {
            this.prefix = "[ECA/" + displayPrefix + "]";
        }

        // 获取前缀
        /**
         * Get the full prefix for this mod logger.
         * @return the prefix string like "[ECA/ModName]"
         */
        public String getPrefix() {
            return prefix;
        }

        // INFO级别日志
        /**
         * Log an info message.
         * @param msg the message to log
         */
        public void info(String msg) {
            LOGGER.info("{} {}", prefix, msg);
        }

        // INFO级别日志（带参数）
        /**
         * Log an info message with arguments.
         * @param fmt the format string
         * @param args the arguments
         */
        public void info(String fmt, Object... args) {
            LOGGER.info(prefix + " " + fmt, args);
        }

        // WARN级别日志
        /**
         * Log a warning message.
         * @param msg the message to log
         */
        public void warn(String msg) {
            LOGGER.warn("{} {}", prefix, msg);
        }

        // WARN级别日志（带参数）
        /**
         * Log a warning message with arguments.
         * @param fmt the format string
         * @param args the arguments
         */
        public void warn(String fmt, Object... args) {
            LOGGER.warn(prefix + " " + fmt, args);
        }

        // ERROR级别日志
        /**
         * Log an error message.
         * @param msg the message to log
         */
        public void error(String msg) {
            LOGGER.error("{} {}", prefix, msg);
        }

        // ERROR级别日志（带参数）
        /**
         * Log an error message with arguments.
         * @param fmt the format string
         * @param args the arguments
         */
        public void error(String fmt, Object... args) {
            LOGGER.error(prefix + " " + fmt, args);
        }

        // ERROR级别日志（带异常）
        /**
         * Log an error message with throwable.
         * @param msg the message to log
         * @param throwable the exception to log
         */
        public void error(String msg, Throwable throwable) {
            LOGGER.error("{} {}", prefix, msg, throwable);
        }

        // DEBUG级别日志
        /**
         * Log a debug message.
         * @param msg the message to log
         */
        public void debug(String msg) {
            LOGGER.debug("{} {}", prefix, msg);
        }

        // DEBUG级别日志（带参数）
        /**
         * Log a debug message with arguments.
         * @param fmt the format string
         * @param args the arguments
         */
        public void debug(String fmt, Object... args) {
            LOGGER.debug(prefix + " " + fmt, args);
        }

        // TRACE级别日志
        /**
         * Log a trace message.
         * @param msg the message to log
         */
        public void trace(String msg) {
            LOGGER.trace("{} {}", prefix, msg);
        }

        // TRACE级别日志（带参数）
        /**
         * Log a trace message with arguments.
         * @param fmt the format string
         * @param args the arguments
         */
        public void trace(String fmt, Object... args) {
            LOGGER.trace(prefix + " " + fmt, args);
        }
    }

    private EcaLogger() {}
}
