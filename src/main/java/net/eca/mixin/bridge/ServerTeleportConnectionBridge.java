package net.eca.mixin.bridge;

public interface ServerTeleportConnectionBridge {

    int eca$beginTeleport(double x, double y, double z, float yRot, float xRot, boolean onGround);

    void eca$completeTeleport(int teleportId, double x, double y, double z);
}
