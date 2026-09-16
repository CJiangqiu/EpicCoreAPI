# 构建版本

执行 `./gradlew build` 会在 `build/libs` 生成：

| 文件 | 用途 |
| --- | --- |
| `epic-core-api-<版本>.jar` | 普通发布版，保留 Agent / Coremod 流程，不包含 JVMTI 扩展或额外 JNA 依赖。 |
| `epic-core-api-<版本>_1.jar` | 同一发布版加上可选 JVMTI 后备通道。 |
| `epic-core-api-<版本>-dev.jar` | 原有开发产物，不包含 JVMTI 扩展。 |

两个发布版使用相同模组 ID，安装时选择其中一个。`eca_pro` 不在本次构建范围内。

`jvmtiJar` 任务复用 `reobfJarJar` 完成后的普通版内容，保留其清单、Mixin、资源和内嵌依赖，
仅添加 `net/eca/optional/jvmti-backend.jar`。扩展来自 `src/jvmti/java`，只依赖 JDK 与 JNA，
没有 Minecraft 成员引用，因此无需再次重混淆。JNA 沿用旧版的 compileOnly 方式，
两个发布包都不内嵌 JNA 类或其原生库。扩展以游戏现有 JNA 的实际加载器为父级，复用运行环境依赖。
早期层无法找到 JNA 时，允许游戏层稍后重试；运行环境仍不可用时记录日志并保留原有后端。

## JVMTI 的启用与边界

`_1` 版沿用 `config/eca.toml` 的 `[Defence]` 下 `"Enable Radical Logic"` 开关，
默认关闭；当前配置系统对激进防御的既有联动规则仍适用。强制兼容模式禁止启用后备通道。
要在 Agent 不可用时获得尽可能完整的类集合，应在启动游戏前启用激进防御。

启动期只准备当前 JVM 的连接和 ClassPrepare 收集器，不执行游戏字节码转换。
收集器按实际 Class 对象保存弱引用，避免同名类混淆以及永久持有所有 ClassLoader。
转换回调在需要补救时才注册；有 Agent 时优先使用 Agent，没有 Agent 时保留加载期 Coremod。
原生重转换回调仅处理 ECA 当前请求中的类，复用当前白名单、转换算法与防重入机制。
无 Agent 时，通道还处理后续自然加载的非系统类；加载期 Coremod 已覆盖的系统目标不重复注入。

JNI 全局引用只在一次重转换请求期间保留，并在结束后释放。
原生转换需同时满足原生返回码成功与回调观察成功；血量请求还需通过现有字节码验证。
调用桥接继续逐类检查转换结果。回执证明本次观察到的转换结果，不保证后续其他转换不会覆盖它。

当前原生位域适配范围为 64 位小端 JVM，使用 JDK 17 的 JVMTI 布局。
不支持的布局、缺失的原生库或未获准的重转换能力会记录日志并保留现有后端。
无 Agent 时，枚举覆盖收集器启用后准备的类；已知 Class 的显式重转换不依赖早期收集。
构建与包内容检查不等同于游戏内 JVMTI 运行验证。

实现依据：[JVMTI 规范](https://docs.oracle.com/en/java/javase/17/docs/specs/jvmti.html)、
[JNA 对 Java 对象参数和返回值的实现](https://github.com/java-native-access/jna/blob/5.12.1/src/com/sun/jna/Function.java)。
