/* SPDX-License-Identifier: LGPL-3.0-or-later */
package baritone.launch.mixins;

import baritone.api.BaritoneAPI;
import baritone.behavior.LookBehavior;
import net.minecraft.client.Camera;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(Camera.class)
public class MixinCamera {
    @Redirect(method = "alignWithEntity", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;getViewYRot(F)F"))
    private float naturalYaw(Entity entity, float partial) {
        float fallback = entity.getViewYRot(partial);
        if (!(entity instanceof LocalPlayer player)) return fallback;
        var baritone = BaritoneAPI.getProvider().getBaritoneForPlayer(player);
        return baritone == null ? fallback : ((LookBehavior) baritone.getLookBehavior()).renderYaw(partial, fallback);
    }

    @Redirect(method = "alignWithEntity", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;getViewXRot(F)F"))
    private float naturalPitch(Entity entity, float partial) {
        float fallback = entity.getViewXRot(partial);
        if (!(entity instanceof LocalPlayer player)) return fallback;
        var baritone = BaritoneAPI.getProvider().getBaritoneForPlayer(player);
        return baritone == null ? fallback : ((LookBehavior) baritone.getLookBehavior()).renderPitch(partial, fallback);
    }
}
