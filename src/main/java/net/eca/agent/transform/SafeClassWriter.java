package net.eca.agent.transform;

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
        if (type1.equals(type2)) {
            return type1;
        }

        if ("java/lang/Object".equals(type1) || "java/lang/Object".equals(type2)) {
            return "java/lang/Object";
        }

        try {
            Set<String> type1Ancestors = getSuperClassChain(type1);

            if (type1Ancestors.contains(type2)) {
                return type2;
            }

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
        }

        return "java/lang/Object";
    }

    private Set<String> getSuperClassChain(String type) {
        Set<String> ancestors = new HashSet<>();
        String current = type;
        int maxDepth = 100;

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

    private String getSuperClassName(String type) {
        String resourcePath = type + ".class";
        try (InputStream is = classLoader.getResourceAsStream(resourcePath)) {
            if (is != null) {
                ClassReader cr = new ClassReader(is);
                return cr.getSuperName();
            }
        } catch (IOException e) {
        }

        try {
            Class<?> clazz = Class.forName(type.replace('/', '.'), false, classLoader);
            Class<?> superClass = clazz.getSuperclass();
            if (superClass != null) {
                return superClass.getName().replace('.', '/');
            }
        } catch (ClassNotFoundException e) {
        }

        return null;
    }
}
