package net.eca.coremod;

public class ProbeFixture {
    private int value = 3;
    private final int[] values = {5};

    public int update(int next) {
        value = next;
        values[0] = next + 1;
        return value + values[0];
    }
}
