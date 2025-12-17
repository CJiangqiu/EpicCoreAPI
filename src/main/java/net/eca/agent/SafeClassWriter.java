package net.eca.agent;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;

/**
 * 安全的ClassWriter，避免在Agent环境中的类加载问题
 * 重写getCommonSuperClass方法，直接返回Object避免触发类加载
 */
public class SafeClassWriter extends ClassWriter {

    public SafeClassWriter(int flags) {
        super(flags);
    }

    public SafeClassWriter(ClassReader classReader, int flags) {
        super(classReader, flags);
    }

    @Override
    protected String getCommonSuperClass(String type1, String type2) {
        // 避免在Agent中加载类，直接返回Object
        return "java/lang/Object";
    }
}
