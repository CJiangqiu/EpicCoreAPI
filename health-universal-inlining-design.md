# 真正通用的字节码数据流逆向分析设计

## 当前实现 vs 理想目标

当前 `HealthAnalyzer` 是基于白名单的代数重写引擎：
- mod 方法递归内联，`java/` 和 `net/minecraft/` 前缀跳过
- 只有 4 个 JDK 方法硬编码为可逆（Long.reverse / intBitsToFloat / floatToRawIntBits / Math.max）
- 其他所有方法调用变成 `UnresolvedCall` 黑盒 — solve 直接返回 null
- 兜底靠 sister-setter（在目标 class 找 setter 反射调用）

这离"万用数据流逆向"的核心差距：
1. 没有约束求解能力 — 对抗不了非线性/非精确逆运算
2. 递归内联跳过 JDK/MC — 加密如果借用任何库函数做中间步骤，链就断了
3. 没有运行时信息 — 依赖运行时状态的加密完全瞎
4. 虚调用只做静态 owner 判定 — 子类 override 会误判

## 目标方案：全量内联 → 纯原语树 → 通用反解

### 核心思路

递归内联所有方法调用（包括 JDK/MC），直到表达式树的内部节点只剩 JVM 原语操作、
叶子只剩字段/常量/数组元素/EntityData/Map entry。

此时每个内部节点都是已知语义的 JVM 原语，反解变成纯树遍历，不再需要"算法识别"。

### 实现步骤

#### Step 1 — 全量递归字节码内联

类似当前 `tryInlineInvoke`，但移除 `java/` 和 `net/minecraft/` 跳过逻辑。
任何 `<init>` 和 native 以外的方法全部内联展开。

关键改进：
- **虚调用运行时解析**：不再用声明的 owner，而是用 `entity.getClass()` 解析 `INVOKEVIRTUAL`
  的实际目标 class，然后把那个 concrete class 的版本内联进来。
  代价：分析结果变成 per-concrete-class 缓存，不再是 per-declared-class。
- **深度限制**仍然是必要的，但语义变了 — 不再是"跳过非 mod 代码"，
  而是纯粹的复杂度上限（建议 15-20）。

#### Step 2 — 树规约

全量内联后树会巨大。内联后立即做 Const 折叠和死节点消除：
- `Const(3) + Const(5)` → `Const(8)`
- `I2F(F2I(x))` → `x`
- 死分支裁剪

规约后的树才是用来反解的。

#### Step 3 — 通用反解

树只剩 JVM 原语 → 每个内部节点都有数学逆运算：
- 加减乘除异或移位取反 → 通用代数逆
- 类型转换 → 通用逆
- 比较/条件跳转 → `Choice` 节点，尝试每条路径

不再需要 `detectInvertible` 硬编码白名单。

### 需要解决的硬问题

#### 1. 虚方法调用（最致命）

`INVOKEVIRTUAL` 的目标类编译期不确定。`this.getFoo()` 可能是任意子类 override。
**方案**：分析时传入实体实例指针，用 `entity.getClass()` 解析虚调用目标。
变为运行时分析，缓存 per concrete class。代价可接受。

#### 2. Native 方法

JDK 大量 native（CAS、Unsafe、I/O 等），无法字节码内联。
**方案**：大签名白名单 + 手写逆运算。比当前 `detectInvertible` 的白名单大得多，
需要覆盖常见的 Math/StrictMath/Integer/Long/Float/Double 的 native 方法。

#### 3. 循环和递归

`getHealth()` 里有 `while` 循环做累加 → AST 树化变成无限展开。
**方案**：定点检测 — 循环变量回溯到已知值就停止展开，否则降级为 `UnknownExpr`。

#### 4. 内存副作用

`getHealth()` 理论上可能写字段（lazy init + cache），但血量 getter 里极少见。
**方案**：假设只读，遇到写操作时记下警告但继续（或直接降级）。

#### 5. INVOKEDYNAMIC

lambda、字符串拼接、record 访问器。JDK 实现高度不稳定，跨版本兼容难做。
**方案**：尝试解析 bootstrap metafactory，否则降级为黑盒。

### 工程复杂度和阶段建议

| 阶段 | 内容 | 复杂度 | 优先级 |
|------|------|--------|--------|
| Phase A | 移除 JDK/MC 跳过，全量内联所有非 native 方法 | 中 | 最高 |
| Phase B | 虚调用运行时 concrete class 解析 | 中 | 高 |
| Phase C | Native 方法大签名白名单扩充 | 高（人肉积累） | 中 |
| Phase D | 树规约（Const 折叠 + 死节点消除） | 低 | 中 |
| Phase E | 循环定点检测 | 高 | 低 |
| Phase F | INVOKEDYNAMIC 降级处理 | 中 | 低 |

### 当前实现规模

`HealthAnalyzer`: ~900 行
`HealthAnalyzerManager`: ~900 行
全量内联方案预估: 3000+ 行

### 降级策略总结

```
全量内联
├── 普通方法 → 递归展开 ✓
├── native → 签名字典查表，命中则手写逆；否则黑盒
├── indy → 解析 metafactory，失败则黑盒
├── 虚调用 → getClass() 解析真实目标 → 递归内联
├── 循环 → 定点检测，失败则黑盒
├── 副作用(写字段) → 黑盒
└── 超深度 → 截断，黑盒
```

## 反解能力对比

| 场景 | 当前 HealthAnalyzer | 全量内联方案 |
|------|--------------------|-------------|
| `return this.health` | ✓ 直接写字段 | ✓ 直接写字段 |
| `return this.health ^ 0xDEAD` | ✓ 异或可逆 | ✓ 异或可逆 |
| `return mix(health, salt)` (mod私有方法) | ✓ 内联后展开 | ✓ 内联后展开 |
| `return Math.pow(health, 2)` | ✗ UnresolvedCall | ✓ 内联 JDK 实现后展开 |
| `return customCrypto(health, key)` | ✗ 黑盒+sister-setter | 视情况：纯 JDK 原语实现则可解 |
| `return nativeEncrypt(health, key)` | ✗ | ✗ native 无法内联 |
| `return health` 但用 indy 实现 | ✗ | 部分可解 |
