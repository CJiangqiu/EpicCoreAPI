package net.eca.api;

// 日志委托接口 - 其他mod实现此接口将日志委托给ECA
/**
 * Logger delegate interface for other mods to delegate logging to ECA.
 * Implement this interface and register with EcaAPI to use ECA's logging system.
 */
public interface ILoggerDelegate {

    // 获取mod id
    /**
     * Get the mod id for this logger.
     * @return the mod id
     */
    String getModId();

    // 获取日志前缀（可选覆盖，默认返回mod id）
    /**
     * Get the display prefix for log messages.
     * Override this to customize the prefix, defaults to mod id.
     * @return the prefix to display in log messages
     */
    default String getLogPrefix() {
        return getModId();
    }
}
