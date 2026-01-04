package net.eca.util.health;

import net.eca.util.EcaLogger;
import org.objectweb.asm.*;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Stack;
import java.util.function.Function;

//Health value analyzer: analyzes getHealth() method implementation and builds reverse formula
public class HealthAnalyzer {

    private static final String TARGET_METHOD_NAME = "m_21223_";
    private static final String TARGET_METHOD_DESC = "()F";

    //Analyze the getHealth() method of a class (entry point with recursion)
    public static AnalysisResult analyze(Class<?> clazz) {

        //查找定义 getHealth() 方法的类（可能在父类）
        Class<?> methodOwnerClass = findMethodOwnerClass(clazz);

        if (methodOwnerClass == null) {
            EcaLogger.warn("[HealthAnalyzer] getHealth() method not found in class hierarchy for: {}", clazz.getName());
            return null;
        }

        if (methodOwnerClass != clazz) {
        }

        return analyzeMethodRecursive(methodOwnerClass, TARGET_METHOD_NAME, TARGET_METHOD_DESC, 0);
    }

    //查找定义了 getHealth() 方法的类（向上遍历继承链）
    private static Class<?> findMethodOwnerClass(Class<?> startClass) {
        Class<?> current = startClass;
        int depth = 0;

        while (current != null && current != Object.class) {

            if (classDefinesMethod(current, TARGET_METHOD_NAME, TARGET_METHOD_DESC)) {
                return current;
            }

            current = current.getSuperclass();
            depth++;
        }

        EcaLogger.warn("[HealthAnalyzer] Method not found in entire class hierarchy starting from: {}", startClass.getName());
        return null;
    }

    //检查类是否定义了指定方法
    private static boolean classDefinesMethod(Class<?> clazz, String methodName, String descriptor) {
        try {
            String className = clazz.getName().replace('.', '/') + ".class";
            InputStream classStream = clazz.getClassLoader().getResourceAsStream(className);

            if (classStream == null) {
                EcaLogger.warn("[HealthAnalyzer] Cannot load bytecode for class: {}", clazz.getName());
                return false;
            }

            ClassReader cr = new ClassReader(classStream);
            MethodDefinitionFinder finder = new MethodDefinitionFinder(methodName, descriptor);
            cr.accept(finder, 0);

            return finder.found;

        } catch (Exception e) {
            EcaLogger.error("[HealthAnalyzer] Error checking method in class: {}", clazz.getName(), e);
            return false;
        }
    }

    //简单的 ClassVisitor，只查找方法是否存在
    private static class MethodDefinitionFinder extends ClassVisitor {
        private final String targetMethodName;
        private final String targetMethodDesc;
        public boolean found = false;

        public MethodDefinitionFinder(String methodName, String methodDesc) {
            super(Opcodes.ASM9);
            this.targetMethodName = methodName;
            this.targetMethodDesc = methodDesc;
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
            if (name.equals(targetMethodName) && descriptor.equals(targetMethodDesc)) {
                found = true;
            }
            return null;
        }
    }

    //解析方法描述符，获取参数数量（通用方法）
    private static int getParameterCount(String descriptor) {
        // descriptor 格式: (参数类型...)返回类型
        // 例如: (Ljava/lang/Object;I)V
        //       参数1: Ljava/lang/Object; (对象)
        //       参数2: I (int)

        if (descriptor == null || !descriptor.startsWith("(")) {
            return 0;
        }

        String params = descriptor.substring(1, descriptor.indexOf(')'));
        int count = 0;
        int i = 0;

        while (i < params.length()) {
            char c = params.charAt(i);
            if (c == 'L') {
                //对象类型: Lpackage/Class;
                i = params.indexOf(';', i) + 1;
                count++;
            } else if (c == '[') {
                //数组类型: [I, [Ljava/lang/Object; 等
                i++;
                while (i < params.length() && params.charAt(i) == '[') {
                    i++;
                }
                if (i < params.length() && params.charAt(i) == 'L') {
                    i = params.indexOf(';', i) + 1;
                } else {
                    i++;
                }
                count++;
            } else {
                //基本类型: I, F, D, J, S, B, C, Z
                i++;
                count++;
            }
        }

        return count;
    }

    //Recursively analyze a method until finding minimal writable unit
    private static AnalysisResult analyzeMethodRecursive(Class<?> clazz, String methodName, String descriptor, int depth) {

        //检查是否是 Java 核心类（bootstrap 类加载器加载的类）
        if (clazz.getClassLoader() == null) {
            EcaLogger.warn("[HealthAnalyzer] Skipping Java core class (bootstrap classloader): {}", clazz.getName());
            return null;
        }

        //检查是否是 Java 标准库类（避免递归到 java.* 和 javax.* 包）
        String className = clazz.getName();
        if (className.startsWith("java.") || className.startsWith("javax.") || className.startsWith("jdk.")) {
            EcaLogger.warn("[HealthAnalyzer] Skipping Java standard library class: {}", className);
            return null;
        }

        AnalysisResult result = analyzeMethodBytecode(clazz, methodName, descriptor);

        if (result == null || !result.foundMethod) {
            return result;
        }

        //如果找到最小可写单元，停止递归
        if (result.foundMinimalUnit) {
            return result;
        }

        //如果是方法调用，递归分析
        if (result.hasMethodCall()) {

            try {
                //加载被调用方法的类
                String targetClassName = result.methodCallTarget.owner.replace('/', '.');

                //检查是否是 Java 标准库类（避免递归分析）
                if (targetClassName.startsWith("java.") || targetClassName.startsWith("javax.") || targetClassName.startsWith("jdk.")) {
                    EcaLogger.warn("[HealthAnalyzer] Skipping recursion into Java standard library class: {}", targetClassName);
                    return result;
                }

                Class<?> targetClass = Class.forName(targetClassName);

                //递归分析被调用的方法
                AnalysisResult subResult = analyzeMethodRecursive(
                    targetClass,
                    result.methodCallTarget.name,
                    result.methodCallTarget.descriptor,
                    depth + 1
                );

                //合并结果
                if (subResult != null) {
                    result.merge(subResult);
                }

            } catch (ClassNotFoundException e) {
                EcaLogger.warn("[HealthAnalyzer] Cannot load class for recursive analysis: {}", result.methodCallTarget.owner);
            }
        }

        return result;
    }

    //Analyze a single method's bytecode
    private static AnalysisResult analyzeMethodBytecode(Class<?> clazz, String methodName, String descriptor) {
        try {
            String className = clazz.getName().replace('.', '/') + ".class";
            InputStream classStream = clazz.getClassLoader().getResourceAsStream(className);

            if (classStream == null) {
                EcaLogger.warn("[HealthAnalyzer] Cannot get class bytecode: {}", clazz.getName());
                return null;
            }

            ClassReader classReader = new ClassReader(classStream);
            AnalysisVisitor visitor = new AnalysisVisitor(clazz, methodName, descriptor);
            classReader.accept(visitor, ClassReader.EXPAND_FRAMES);

            if (!visitor.result.foundMethod) {
                EcaLogger.warn("[HealthAnalyzer] Method {} not found in {}", methodName, clazz.getName());
            }

            return visitor.result;

        } catch (IOException e) {
            EcaLogger.error("[HealthAnalyzer] Failed to analyze bytecode: {}", clazz.getName(), e);
            return null;
        }
    }

    //Analysis result
    public static class AnalysisResult {
        public boolean foundMethod = false;
        public boolean foundMinimalUnit = false;
        public StackElement returnValueSource;
        public List<ArithmeticOp> arithmeticOps = new ArrayList<>();
        public Function<Float, Float> reverseFormula;
        public DataSourceInfo dataSource;
        public ContainerInfo containerInfo;
        public MethodCallTarget methodCallTarget;

        //嵌套字段访问路径（从 Entity 到最终字段的路径）
        public List<FieldAccessStep> fieldAccessPath = new ArrayList<>();

        //EntityData specific info
        public String entityDataAccessorName;
        public String entityDataAccessorOwner;

        //HashMap specific info
        public String hashMapContainerGetterMethod;
        public String hashMapContainerGetterOwner;
        public StackElement hashMapKeySource;
        public MethodCallTarget hashMapMethodTarget;  //HashMap.get/getOrDefault 方法目标

        public void buildReverseFormula() {
            if (arithmeticOps.isEmpty()) {
                reverseFormula = null;
                return;
            }


            //Reverse operations (from end to start)
            List<Function<Float, Float>> reverseOps = new ArrayList<>();
            for (int i = arithmeticOps.size() - 1; i >= 0; i--) {
                ArithmeticOp op = arithmeticOps.get(i);

                Function<Float, Float> reverseOp = reverseOperation(op);
                if (reverseOp != null) {
                    reverseOps.add(reverseOp);
                } else {
                    EcaLogger.warn("[HealthAnalyzer] Could not reverse operation: {}", op);
                }
            }


            //Compose functions
            reverseFormula = (targetValue) -> {
                float result = targetValue;
                for (Function<Float, Float> op : reverseOps) {
                    result = op.apply(result);
                }
                return result;
            };
        }

        private Function<Float, Float> reverseOperation(ArithmeticOp op) {
            switch (op.opcode) {
                //一元运算（取负）- 所有类型
                case Opcodes.FNEG:
                case Opcodes.INEG:
                case Opcodes.DNEG:
                    return y -> -y;

                //Float 四则运算
                case Opcodes.FADD: return y -> y - op.getOperandFloat();
                case Opcodes.FSUB: return y -> y + op.getOperandFloat();
                case Opcodes.FMUL: return y -> y / op.getOperandFloat();
                case Opcodes.FDIV: return y -> y * op.getOperandFloat();

                //Int 四则运算
                case Opcodes.IADD: return y -> y - op.getOperandFloat();
                case Opcodes.ISUB: return y -> y + op.getOperandFloat();
                case Opcodes.IMUL: return y -> y / op.getOperandFloat();
                case Opcodes.IDIV: return y -> y * op.getOperandFloat();

                //Double 四则运算
                case Opcodes.DADD: return y -> y - op.getOperandFloat();
                case Opcodes.DSUB: return y -> y + op.getOperandFloat();
                case Opcodes.DMUL: return y -> y / op.getOperandFloat();
                case Opcodes.DDIV: return y -> y * op.getOperandFloat();

                //类型转换（恒等函数，因为逆向时已经是 float）
                case Opcodes.I2F:   // int → float，逆向: float → int
                case Opcodes.F2I:   // float → int，逆向: int → float
                case Opcodes.I2D:   // int → double，逆向: double → int
                case Opcodes.D2I:   // double → int，逆向: int → double
                case Opcodes.D2F:   // double → float，逆向: float → double
                case Opcodes.F2D:   // float → double，逆向: double → float
                    return y -> y;  // 类型转换的逆操作就是再次转换，数值不变

                default:
                    return null;
            }
        }

        public void identifyDataSource() {
            if (returnValueSource == null) {
                EcaLogger.warn("[HealthAnalyzer] No return value source found");
                dataSource = new DataSourceInfo();
                dataSource.sourceType = SourceType.UNKNOWN;
                return;
            }

            //优先检查：如果检测到 EntityData 访问，立即标记为容器访问（最小可写单元）
            if (entityDataAccessorName != null && entityDataAccessorOwner != null) {
                dataSource = new DataSourceInfo();
                dataSource.sourceType = SourceType.METHOD_CALL;
                dataSource.owner = "net/minecraft/network/syncher/SynchedEntityData";
                dataSource.name = "m_135370_";  //SynchedEntityData.get()
                foundMinimalUnit = true;
                return;
            }

            //优先检查：如果检测到 HashMap 访问，立即标记为容器访问（最小可写单元）
            if (hashMapKeySource != null) {
                dataSource = new DataSourceInfo();
                dataSource.sourceType = SourceType.METHOD_CALL;
                //使用专门保存的 hashMapMethodTarget，避免被异常分支覆盖
                if (hashMapMethodTarget != null) {
                    dataSource.owner = hashMapMethodTarget.owner;
                    dataSource.name = hashMapMethodTarget.name;
                } else {
                    //备用：通用 HashMap 方法
                    dataSource.owner = "java/util/HashMap";
                    dataSource.name = "get";
                    EcaLogger.warn("[HealthAnalyzer] No HashMap method target saved, using default");
                }
                foundMinimalUnit = true;
                return;
            }


            dataSource = new DataSourceInfo();
            dataSource.sourceType = mapElementType(returnValueSource.type);
            dataSource.owner = returnValueSource.owner;
            dataSource.name = (String) returnValueSource.value;
            dataSource.descriptor = returnValueSource.descriptor;

            //特殊处理：如果返回值是算术结果，但方法内部有方法调用，需要递归分析该方法
            if (dataSource.sourceType == SourceType.ARITHMETIC && methodCallTarget != null) {
                //不设置 foundMinimalUnit，让 hasMethodCall() 返回 true
            }


            //检查是否是最小可写单元
            if (dataSource.sourceType == SourceType.DIRECT_FIELD) {
                foundMinimalUnit = true;
            } else if (dataSource.sourceType == SourceType.METHOD_CALL) {
                //检查是否是容器访问
                if (isContainerAccess(dataSource.owner, dataSource.name)) {
                    foundMinimalUnit = true;
                } else {
                }
            }
        }

        private SourceType mapElementType(ElementType type) {
            switch (type) {
                case CONSTANT: return SourceType.CONSTANT;
                case FIELD_VALUE: return SourceType.DIRECT_FIELD;
                case METHOD_RESULT: return SourceType.METHOD_CALL;
                case ARITHMETIC_RESULT: return SourceType.ARITHMETIC;
                default: return SourceType.UNKNOWN;
            }
        }

        private boolean isContainerAccess(String owner, String name) {
            if (owner == null || name == null) return false;

            //HashMap/Map.get/getOrDefault
            if (owner.contains("HashMap") || owner.contains("Map")) {
                if (name.equals("get") || name.equals("getOrDefault")) {
                    return true;
                }
            }

            //ArrayList.get
            if (owner.contains("ArrayList") && name.equals("get")) {
                return true;
            }

            //SynchedEntityData.get
            if (owner.contains("SynchedEntityData") && name.equals("m_135370_")) {
                return true;
            }

            return false;
        }

        public boolean hasMethodCall() {
            //情况1：直接方法调用（无算术运算）
            if (dataSource != null && dataSource.sourceType == SourceType.METHOD_CALL && !foundMinimalUnit) {
                return true;
            }

            //情况2：算术运算包装的方法调用（如: -getHealth() - 10.0）
            if (dataSource != null && dataSource.sourceType == SourceType.ARITHMETIC && methodCallTarget != null && !foundMinimalUnit) {
                return true;
            }

            return false;
        }

        //合并子结果
        public void merge(AnalysisResult subResult) {

            //合并最小可写单元（子结果优先）
            if (subResult.foundMinimalUnit) {

                this.foundMinimalUnit = true;
                this.dataSource = subResult.dataSource;
                this.containerInfo = subResult.containerInfo;

                //合并 EntityData 信息
                if (subResult.entityDataAccessorName != null) {
                    this.entityDataAccessorName = subResult.entityDataAccessorName;
                    this.entityDataAccessorOwner = subResult.entityDataAccessorOwner;
                }

                //合并 HashMap 信息
                if (subResult.hashMapKeySource != null) {
                    this.hashMapKeySource = subResult.hashMapKeySource;
                }
                if (subResult.hashMapContainerGetterMethod != null) {
                    this.hashMapContainerGetterMethod = subResult.hashMapContainerGetterMethod;
                    this.hashMapContainerGetterOwner = subResult.hashMapContainerGetterOwner;
                }
                if (subResult.hashMapMethodTarget != null) {
                    this.hashMapMethodTarget = subResult.hashMapMethodTarget;
                }
            }

            //合并字段访问路径（先当前，再子结果）

            List<FieldAccessStep> mergedPath = new ArrayList<>();
            mergedPath.addAll(this.fieldAccessPath);  //先当前层的字段
            mergedPath.addAll(subResult.fieldAccessPath);  //再子结果的字段

            //去重：移除连续的重复字段访问
            List<FieldAccessStep> deduplicatedPath = new ArrayList<>();
            FieldAccessStep lastStep = null;
            for (FieldAccessStep step : mergedPath) {
                //只有当前步骤与上一步不同时才添加
                if (lastStep == null || !lastStep.ownerClass.equals(step.ownerClass) || !lastStep.fieldName.equals(step.fieldName)) {
                    deduplicatedPath.add(step);
                    lastStep = step;
                } else {
                }
            }

            this.fieldAccessPath = deduplicatedPath;

            if (!deduplicatedPath.isEmpty()) {
            }

            //累积运算公式（先子结果，再当前）

            List<ArithmeticOp> mergedOps = new ArrayList<>();
            mergedOps.addAll(subResult.arithmeticOps);
            mergedOps.addAll(this.arithmeticOps);
            this.arithmeticOps = mergedOps;


            //重新构建逆向公式
            buildReverseFormula();
        }
    }

    //字段访问步骤（用于嵌套字段）
    public static class FieldAccessStep {
        public String ownerClass;  //字段所在的类
        public String fieldName;   //字段名
        public String descriptor;  //字段描述符
        public Class<?> fieldType; //字段类型

        public FieldAccessStep(String ownerClass, String fieldName, String descriptor) {
            this.ownerClass = ownerClass;
            this.fieldName = fieldName;
            this.descriptor = descriptor;
        }

        @Override
        public String toString() {
            return ownerClass + "." + fieldName;
        }
    }

    //Data source information
    public static class DataSourceInfo {
        public SourceType sourceType;
        public String owner;
        public String name;
        public String descriptor;
    }

    public enum SourceType {
        CONSTANT, DIRECT_FIELD, METHOD_CALL, ARITHMETIC, UNKNOWN
    }

    //Container information
    public static class ContainerInfo {
        public String containerType;
        public KeyInfo keyInfo;
        public ContainerSource containerSource;
    }

    //Key information
    public static class KeyInfo {
        public KeySourceType sourceType;
        public String methodName;
        public String fieldName;
        public Object constantValue;
    }

    public enum KeySourceType {
        THIS, METHOD_CALL, STATIC_FIELD, INSTANCE_FIELD, CONSTANT
    }

    //Container source
    public static class ContainerSource {
        public ContainerSourceType type;
        public String owner;
        public String name;
        public String descriptor;
    }

    public enum ContainerSourceType {
        STATIC_METHOD, STATIC_FIELD, INSTANCE_FIELD, INSTANCE_METHOD
    }

    //Method call target
    public static class MethodCallTarget {
        public String owner;
        public String name;
        public String descriptor;
    }

    //Stack element (operand stack simulation)
    public static class StackElement {
        public ElementType type;
        public Object value;
        public String owner;
        public String descriptor;
        public boolean isStaticField = false;
        public boolean isStaticMethod = false;

        public StackElement(ElementType type) {
            this.type = type;
        }

        @Override
        public String toString() {
            return "StackElement{type=" + type + ", value=" + value + ", owner=" + owner + "}";
        }
    }

    public enum ElementType {
        CONSTANT, THIS_REF, FIELD_VALUE, METHOD_RESULT, ARITHMETIC_RESULT, LOCAL_VAR, UNKNOWN
    }

    //Arithmetic operation
    public static class ArithmeticOp {
        public int opcode;
        public Object operand;

        public ArithmeticOp(int opcode, Object operand) {
            this.opcode = opcode;
            this.operand = operand;
        }

        public float getOperandFloat() {
            if (operand instanceof Number) {
                return ((Number) operand).floatValue();
            }
            return 0;
        }

        @Override
        public String toString() {
            String op = getOpName(opcode);
            return operand != null ? op + "(" + operand + ")" : op;
        }

        private String getOpName(int opcode) {
            switch (opcode) {
                case Opcodes.FNEG: return "NEG";
                case Opcodes.FADD: return "ADD";
                case Opcodes.FSUB: return "SUB";
                case Opcodes.FMUL: return "MUL";
                case Opcodes.FDIV: return "DIV";
                default: return "OP_" + opcode;
            }
        }
    }

    //Class visitor
    private static class AnalysisVisitor extends ClassVisitor {
        private final Class<?> targetClass;
        private final String targetMethodName;
        private final String targetMethodDesc;
        public AnalysisResult result = new AnalysisResult();

        public AnalysisVisitor(Class<?> targetClass, String methodName, String methodDesc) {
            super(Opcodes.ASM9);
            this.targetClass = targetClass;
            this.targetMethodName = methodName;
            this.targetMethodDesc = methodDesc;
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
            if (name.equals(targetMethodName) && descriptor.equals(targetMethodDesc)) {
                result.foundMethod = true;
                return new MethodAnalysisVisitor(this, access);
            }
            return null;
        }
    }

    //Method visitor with operand stack simulation
    private static class MethodAnalysisVisitor extends MethodVisitor {
        private final AnalysisVisitor classVisitor;
        private final Stack<StackElement> stack = new Stack<>();
        private final boolean isStaticMethod;

        public MethodAnalysisVisitor(AnalysisVisitor classVisitor, int access) {
            super(Opcodes.ASM9);
            this.classVisitor = classVisitor;
            this.isStaticMethod = (access & Opcodes.ACC_STATIC) != 0;
        }

        @Override
        public void visitVarInsn(int opcode, int var) {
            StackElement element = new StackElement(ElementType.UNKNOWN);
            if (opcode == Opcodes.ALOAD && var == 0) {
                //静态方法：ALOAD_0 是第一个参数
                //实例方法：ALOAD_0 是 this 引用
                if (isStaticMethod) {
                    element.type = ElementType.LOCAL_VAR;
                    element.value = "param_0";
                } else {
                    element.type = ElementType.THIS_REF;
                    element.value = "this";
                }
            } else if (opcode == Opcodes.FLOAD || opcode == Opcodes.ALOAD || opcode == Opcodes.ILOAD) {
                element.type = ElementType.LOCAL_VAR;
                element.value = "localVar_" + var;
            }
            stack.push(element);
        }

        @Override
        public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
            if (opcode == Opcodes.GETFIELD && !stack.isEmpty()) {

                //记录字段访问路径（GETFIELD 是实例字段访问）
                StackElement objRef = stack.peek();
                if (objRef.type == ElementType.THIS_REF || objRef.type == ElementType.FIELD_VALUE) {
                    //这是从 this 或另一个字段访问的字段，记录路径
                    FieldAccessStep step = new FieldAccessStep(owner, name, descriptor);
                    classVisitor.result.fieldAccessPath.add(step);
                }

                stack.pop(); //Pop object reference
            } else if (opcode == Opcodes.GETSTATIC) {
            }

            StackElement fieldValue = new StackElement(ElementType.FIELD_VALUE);
            fieldValue.owner = owner;
            fieldValue.value = name;
            fieldValue.descriptor = descriptor;

            //标记 GETSTATIC 的静态字段
            if (opcode == Opcodes.GETSTATIC) {
                fieldValue.isStaticField = true;
            }

            stack.push(fieldValue);
        }

        @Override
        public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
            String opcodeStr = opcode == Opcodes.INVOKEVIRTUAL ? "INVOKEVIRTUAL" :
                              opcode == Opcodes.INVOKESTATIC ? "INVOKESTATIC" :
                              opcode == Opcodes.INVOKEINTERFACE ? "INVOKEINTERFACE" :
                              opcode == Opcodes.INVOKESPECIAL ? "INVOKESPECIAL" : "INVOKE";


            //提取 EntityData 的 Key (GETSTATIC Accessor)
            if (owner.contains("SynchedEntityData") && name.equals("m_135370_")) {
                //栈顶是 accessor (key)
                if (!stack.isEmpty()) {
                    StackElement keyElement = stack.peek();
                    if (keyElement.type == ElementType.FIELD_VALUE && keyElement.isStaticField) {
                        classVisitor.result.entityDataAccessorName = (String) keyElement.value;
                        classVisitor.result.entityDataAccessorOwner = keyElement.owner;
                    }
                }
            }

            //提取 HashMap 的 container getter 和 key（通用容器方法检测）
            if (owner.contains("HashMap") || owner.contains("Map")) {
                if (name.equals("get") || name.equals("getOrDefault") || name.equals("compute") ||
                    name.equals("computeIfAbsent") || name.equals("computeIfPresent")) {


                    //通用方案：从 descriptor 解析参数数量
                    int paramCount = getParameterCount(descriptor);
                    int containerIndex = paramCount + 1;  // receiver 在所有参数之前


                    //栈布局: [容器, 参数1, 参数2, ..., 参数N]
                    if (stack.size() >= containerIndex) {
                        //第一个参数通常是 key
                        if (paramCount >= 1 && stack.size() >= paramCount) {
                            //key 在倒数第 paramCount 个位置
                            classVisitor.result.hashMapKeySource = stack.get(stack.size() - paramCount);
                        }

                        //容器 getter 应该已经在 INVOKESTATIC 时被记录了
                        if (classVisitor.result.hashMapContainerGetterMethod != null) {
                        } else {
                            EcaLogger.warn("[HealthAnalyzer] No container getter was captured");
                        }

                        //立即保存 HashMap 方法调用目标，避免被后续方法调用覆盖
                        MethodCallTarget hashMapTarget = new MethodCallTarget();
                        hashMapTarget.owner = owner;
                        hashMapTarget.name = name;
                        hashMapTarget.descriptor = descriptor;
                        classVisitor.result.hashMapMethodTarget = hashMapTarget;
                    }
                }
            }

            //Pop receiver (this) for instance methods
            if (opcode == Opcodes.INVOKEVIRTUAL || opcode == Opcodes.INVOKEINTERFACE) {
                if (!stack.isEmpty()) {
                    stack.pop();
                }
            }

            //记录方法调用目标
            MethodCallTarget callTarget = new MethodCallTarget();
            callTarget.owner = owner;
            callTarget.name = name;
            callTarget.descriptor = descriptor;
            classVisitor.result.methodCallTarget = callTarget;

            StackElement methodResult = new StackElement(ElementType.METHOD_RESULT);
            methodResult.owner = owner;
            methodResult.value = name;
            methodResult.descriptor = descriptor;

            //标记静态方法调用
            if (opcode == Opcodes.INVOKESTATIC) {
                methodResult.isStaticMethod = true;

                //特殊检测：如果这是返回容器的静态方法（方法名包含 HashMap/Map 相关），记录它
                if ((name.toLowerCase().contains("map") || name.toLowerCase().contains("get")) &&
                    (descriptor.contains("HashMap") || descriptor.contains("Map"))) {
                    classVisitor.result.hashMapContainerGetterMethod = name;
                    classVisitor.result.hashMapContainerGetterOwner = owner;
                }
            }

            stack.push(methodResult);
        }

        @Override
        public void visitLdcInsn(Object value) {

            StackElement constant = new StackElement(ElementType.CONSTANT);
            constant.value = value;
            stack.push(constant);
        }

        @Override
        public void visitInsn(int opcode) {
            switch (opcode) {
                //一元运算（取负）- Float/Int/Double
                case Opcodes.FNEG:
                case Opcodes.INEG:
                case Opcodes.DNEG:
                    if (!stack.isEmpty()) {
                        stack.pop();
                        StackElement result = new StackElement(ElementType.ARITHMETIC_RESULT);
                        stack.push(result);
                        classVisitor.result.arithmeticOps.add(new ArithmeticOp(opcode, null));
                    }
                    break;

                //二元运算 - Float
                case Opcodes.FADD:
                case Opcodes.FSUB:
                case Opcodes.FMUL:
                case Opcodes.FDIV:
                //二元运算 - Int
                case Opcodes.IADD:
                case Opcodes.ISUB:
                case Opcodes.IMUL:
                case Opcodes.IDIV:
                //二元运算 - Double
                case Opcodes.DADD:
                case Opcodes.DSUB:
                case Opcodes.DMUL:
                case Opcodes.DDIV:
                    if (stack.size() >= 2) {
                        StackElement operand2 = stack.pop();
                        stack.pop();
                        StackElement result = new StackElement(ElementType.ARITHMETIC_RESULT);
                        stack.push(result);
                        classVisitor.result.arithmeticOps.add(new ArithmeticOp(opcode, operand2.value));
                    }
                    break;

                //类型转换（Int/Float/Double 互转）
                case Opcodes.I2F:   // int → float
                case Opcodes.F2I:   // float → int
                case Opcodes.I2D:   // int → double
                case Opcodes.D2I:   // double → int
                case Opcodes.D2F:   // double → float
                case Opcodes.F2D:   // float → double
                    if (!stack.isEmpty()) {
                        stack.pop();
                        StackElement result = new StackElement(ElementType.ARITHMETIC_RESULT);
                        stack.push(result);
                        classVisitor.result.arithmeticOps.add(new ArithmeticOp(opcode, null));
                    }
                    break;

                //返回指令
                case Opcodes.FRETURN:
                case Opcodes.IRETURN:
                case Opcodes.DRETURN:
                    if (!stack.isEmpty()) {
                        StackElement returnValue = stack.pop();
                        classVisitor.result.returnValueSource = returnValue;
                    }
                    break;
            }
        }

        @Override
        public void visitEnd() {

            //Build reverse formula and identify data source
            classVisitor.result.buildReverseFormula();
            classVisitor.result.identifyDataSource();


            super.visitEnd();
        }
    }
}
