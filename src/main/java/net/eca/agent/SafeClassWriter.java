package net.eca.agent;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.Set;

/**
 * 安全的ClassWriter，避免在Agent环境中的类加载问题
 * 使用字节码分析而非类加载来计算公共父类
 */
public class SafeClassWriter extends ClassWriter {
    private final ClassLoader classLoader;

    public SafeClassWriter(int flags) {
        super(flags);
        this.classLoader = getClassLoader();
    }

    public SafeClassWriter(ClassReader classReader, int flags) {
        super(classReader, flags);
        this.classLoader = getClassLoader();
    }

    public ClassLoader getClassLoader() {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        if (cl == null) {
            cl = SafeClassWriter.class.getClassLoader();
        }
        if (cl == null) {
            cl = ClassLoader.getSystemClassLoader();
        }
        return cl;
    }

    @Override
    protected String getCommonSuperClass(String type1, String type2) {
        // 快速路径：相同类型
        if (type1.equals(type2)) {
            return type1;
        }

        // 快速路径：其中一个是 Object
        if ("java/lang/Object".equals(type1) || "java/lang/Object".equals(type2)) {
            return "java/lang/Object";
        }

        try {
            // 获取 type1 的所有父类链
            Set<String> type1Ancestors = getSuperClassChain(type1);

            // 检查 type2 是否在 type1 的父类链中
            if (type1Ancestors.contains(type2)) {
                return type2;
            }

            // 遍历 type2 的父类链，查找第一个在 type1 祖先中的类
            String current = type2;
            while (current != null && !"java/lang/Object".equals(current)) {
                String superName = getSuperClassName(current);
                if (superName == null) {
                    break;
                }
                if (type1Ancestors.contains(superName)) {
                    return superName;
                }
                current = superName;
            }
        } catch (Exception e) {
            // 忽略异常，回退到 Object
        }

        return "java/lang/Object";
    }

    /**
     * 获取类的所有父类（包括自己）
     */
    private Set<String> getSuperClassChain(String type) {
        Set<String> ancestors = new HashSet<>();
        String current = type;
        int maxDepth = 100; // 防止无限循环

        while (current != null && maxDepth-- > 0) {
            ancestors.add(current);
            if ("java/lang/Object".equals(current)) {
                break;
            }
            current = getSuperClassName(current);
        }

        ancestors.add("java/lang/Object");
        return ancestors;
    }

    /**
     * 通过读取字节码获取父类名，不触发类加载
     */
    private String getSuperClassName(String type) {
        // 尝试从类加载器获取字节码
        String resourcePath = type + ".class";
        try (InputStream is = classLoader.getResourceAsStream(resourcePath)) {
            if (is != null) {
                ClassReader cr = new ClassReader(is);
                return cr.getSuperName();
            }
        } catch (IOException e) {
            // 忽略
        }

        // 回退：尝试加载类（某些系统类可能需要）
        try {
            Class<?> clazz = Class.forName(type.replace('/', '.'), false, classLoader);
            Class<?> superClass = clazz.getSuperclass();
            if (superClass != null) {
                return superClass.getName().replace('.', '/');
            }
        } catch (ClassNotFoundException e) {
            // 忽略
        }

        return null;
    }
}
