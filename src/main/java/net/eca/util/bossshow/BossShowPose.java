package net.eca.util.bossshow;

//世界空间相机位姿快照
public final class BossShowPose {
    public double x;
    public double y;
    public double z;
    public float yaw;
    public float pitch;
    public boolean cinematic;

    public BossShowPose() {}

    public BossShowPose(double x, double y, double z, float yaw, float pitch, boolean cinematic) {
        this.x = x; this.y = y; this.z = z;
        this.yaw = yaw; this.pitch = pitch;
        this.cinematic = cinematic;
    }
}
