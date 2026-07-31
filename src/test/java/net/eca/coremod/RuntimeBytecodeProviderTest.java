package net.eca.coremod;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

class RuntimeBytecodeProviderTest {

    @Test
    void analysisInputRemainsSeparateFromFinalRuntimeBytes() {
        String className = Fixture.class.getName();
        byte[] analysisInput = {1, 2, 3};
        byte[] laterAnalysisInput = {4, 5, 6};
        byte[] runtimeOutput = {7, 8, 9};

        RuntimeBytecodeProvider.captureAnalysisInput(className, analysisInput);
        RuntimeBytecodeProvider.captureAnalysisInput(className, laterAnalysisInput);
        RuntimeBytecodeProvider.captureStatic(className, runtimeOutput);

        assertArrayEquals(analysisInput, RuntimeBytecodeProvider.getAnalysis(Fixture.class));
        assertArrayEquals(runtimeOutput, RuntimeBytecodeProvider.get(Fixture.class));
    }

    private static final class Fixture {
    }
}
