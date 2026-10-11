package net.eca.mixin;

import net.eca.coremod.AllReturnToggle;
import net.minecraftforge.network.simple.IndexedMessageCodec;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;

@Mixin(value = IndexedMessageCodec.class, remap = false)
public abstract class NetworkCodecMixin {

    // 编码回调包含同步辅助调用，完整执行才能与接收端的数据布局一致。
    @Redirect(method = "tryEncode", at = @At(value = "INVOKE",
            target = "Ljava/util/Optional;ifPresent(Ljava/util/function/Consumer;)V"))
    private static <T> void eca$encode(Optional<T> encoder, Consumer<? super T> action) {
        AllReturnToggle.withNetworkBypass(() -> {
            encoder.ifPresent(action);
            return null;
        });
    }

    // 两次 map 分别处理解码与登录序号；后续消息处理器不在豁免范围内。
    @Redirect(method = "tryDecode", at = @At(value = "INVOKE",
            target = "Ljava/util/Optional;map(Ljava/util/function/Function;)Ljava/util/Optional;"), require = 2)
    private static <T, R> Optional<R> eca$decode(Optional<T> value, Function<? super T, ? extends R> decoder) {
        return AllReturnToggle.withNetworkBypass(() -> value.map(decoder));
    }

    @Redirect(method = "tryEncode", at = @At(value = "INVOKE",
            target = "Ljava/util/function/Function;apply(Ljava/lang/Object;)Ljava/lang/Object;"))
    private static <T, R> R eca$readLoginIndex(Function<T, R> getter, T message) {
        return AllReturnToggle.withNetworkBypass(() -> getter.apply(message));
    }
}
