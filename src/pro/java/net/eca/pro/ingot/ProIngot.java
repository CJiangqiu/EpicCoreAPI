package net.eca.pro.ingot;

import java.util.Set;

interface ProIngot {
    String name();

    Set<String> targets();

    byte[] transform(String internalName, byte[] classBytes);

    boolean verify(String internalName, byte[] classBytes);
}
