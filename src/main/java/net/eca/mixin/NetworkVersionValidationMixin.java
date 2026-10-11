package net.eca.mixin;

import net.eca.coremod.AllReturnToggle;
import net.minecraftforge.network.NetworkInstance;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.function.Predicate;

@Mixin(value = NetworkInstance.class, remap = false)
public abstract class NetworkVersionValidationMixin {

    // 保留真实协议校验结果，避免布尔回调被提前返回误判为版本不兼容。
    @Redirect(method = {"tryServerVersionOnClient", "tryClientVersionOnServer"},
            at = @At(value = "INVOKE", target = "Ljava/util/function/Predicate;test(Ljava/lang/Object;)Z"))
    private boolean eca$validateVersion(Predicate<String> predicate, Object version) {
        return AllReturnToggle.withNetworkBypass(() -> predicate.test((String) version));
    }
}
