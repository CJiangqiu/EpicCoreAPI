package net.eca.agent;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

final class CoremodServiceTransformer implements ClassFileTransformer {
    private static final String SERVICE_TARGET = "cpw/mods/modlauncher/TransformationServicesHandler";
    private static final String EXCLUSION_TARGET = "net/minecraftforge/fml/loading/ModDirTransformerDiscoverer";
    private static final String DISCOVERY_DESCRIPTOR =
            "(Lcpw/mods/modlauncher/ArgumentHandler$DiscoveryData;)V";
    private static final String FILTER = "net/eca/agent/CoremodServiceFilter";

    private final Instrumentation instrumentation;
    private final AtomicBoolean serviceTransformed = new AtomicBoolean();
    private final AtomicBoolean exclusionTransformed = new AtomicBoolean();
    private volatile Module bootstrapHelperModule;

    CoremodServiceTransformer(Instrumentation instrumentation) {
        this.instrumentation = instrumentation;
    }

    void setBootstrapHelperModule(Module module) {
        bootstrapHelperModule = module;
    }

    @Override
    public byte[] transform(Module module, ClassLoader loader, String className,
                            Class<?> classBeingRedefined, ProtectionDomain protectionDomain,
                            byte[] classfileBuffer) {
        if (SERVICE_TARGET.equals(className) && serviceTransformed.compareAndSet(false, true)) {
            return transformServiceDiscovery(module, classfileBuffer);
        }
        if (EXCLUSION_TARGET.equals(className) && exclusionTransformed.compareAndSet(false, true)) {
            return transformModExclusions(module, classfileBuffer);
        }
        return null;
    }

    private byte[] transformServiceDiscovery(Module module, byte[] classfileBuffer) {
        try {
            addHelperRead(module);

            AtomicBoolean injected = new AtomicBoolean();
            ClassReader reader = new ClassReader(classfileBuffer);
            ClassWriter writer = new SafeClassWriter(reader, ClassWriter.COMPUTE_MAXS);
            reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                 String signature, String[] exceptions) {
                    MethodVisitor delegate = super.visitMethod(access, name, descriptor, signature, exceptions);
                    if (!"discoverServices".equals(name) || !DISCOVERY_DESCRIPTOR.equals(descriptor)) {
                        return delegate;
                    }
                    return new MethodVisitor(Opcodes.ASM9, delegate) {
                        private int streamToListCalls;
                        private boolean awaitingCandidateStore;

                        @Override
                        public void visitMethodInsn(int opcode, String owner, String calledName,
                                                    String calledDescriptor, boolean isInterface) {
                            super.visitMethodInsn(opcode, owner, calledName, calledDescriptor, isInterface);
                            if ("java/util/stream/Stream".equals(owner)
                                    && "toList".equals(calledName)
                                    && "()Ljava/util/List;".equals(calledDescriptor)
                                    && ++streamToListCalls == 2) {
                                awaitingCandidateStore = true;
                            }
                        }

                        @Override
                        public void visitVarInsn(int opcode, int variable) {
                            if (awaitingCandidateStore && opcode == Opcodes.ASTORE) {
                                super.visitMethodInsn(Opcodes.INVOKESTATIC, FILTER, "filter",
                                        "(Ljava/util/List;)Ljava/util/List;", false);
                                injected.set(true);
                                awaitingCandidateStore = false;
                            }
                            super.visitVarInsn(opcode, variable);
                        }
                    };
                }
            }, ClassReader.EXPAND_FRAMES);
            if (!injected.get()) throw new IllegalStateException("service_discovery_injection_missing");
            AgentLogWriter.info("[EcaAgent] Transformation-service discovery protected");
            return writer.toByteArray();
        } catch (Throwable t) {
            AgentLogWriter.error("[EcaAgent] Transformation-service discovery protection failed", t);
            return null;
        }
    }

    private byte[] transformModExclusions(Module module, byte[] classfileBuffer) {
        try {
            addHelperRead(module);
            AtomicBoolean injected = new AtomicBoolean();
            ClassReader reader = new ClassReader(classfileBuffer);
            ClassWriter writer = new SafeClassWriter(reader, ClassWriter.COMPUTE_MAXS);
            reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                 String signature, String[] exceptions) {
                    MethodVisitor delegate = super.visitMethod(access, name, descriptor, signature, exceptions);
                    if (!"allExcluded".equals(name) || !"()Ljava/util/List;".equals(descriptor)) {
                        return delegate;
                    }
                    return new MethodVisitor(Opcodes.ASM9, delegate) {
                        @Override
                        public void visitInsn(int opcode) {
                            if (opcode == Opcodes.ARETURN) {
                                super.visitMethodInsn(Opcodes.INVOKESTATIC, FILTER, "filterExcluded",
                                        "(Ljava/util/List;)Ljava/util/List;", false);
                                injected.set(true);
                            }
                            super.visitInsn(opcode);
                        }
                    };
                }
            }, ClassReader.EXPAND_FRAMES);
            if (!injected.get()) throw new IllegalStateException("mod_exclusion_injection_missing");
            AgentLogWriter.info("[EcaAgent] Normal mod discovery preservation installed");
            return writer.toByteArray();
        } catch (Throwable t) {
            AgentLogWriter.error("[EcaAgent] Normal mod discovery preservation failed", t);
            return null;
        }
    }

    private void addHelperRead(Module module) {
        Module helperModule = bootstrapHelperModule;
        if (helperModule == null) throw new IllegalStateException("bootstrap_helper_unavailable");
        if (module != null && module.isNamed() && instrumentation.isModifiableModule(module)) {
            instrumentation.redefineModule(module, Set.of(helperModule), Map.of(), Map.of(), Set.of(), Map.of());
        }
    }

    private static final class SafeClassWriter extends ClassWriter {
        private SafeClassWriter(ClassReader reader, int flags) {
            super(reader, flags);
        }

        @Override
        protected String getCommonSuperClass(String firstType, String secondType) {
            return "java/lang/Object";
        }
    }
}
