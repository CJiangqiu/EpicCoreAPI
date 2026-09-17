package net.eca.pro.ingot;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;

final class SafeClassWriter extends ClassWriter {
    SafeClassWriter(ClassReader reader, int flags) {
        super(reader, flags);
    }

    @Override
    protected String getCommonSuperClass(String type1, String type2) {
        return "java/lang/Object";
    }
}
