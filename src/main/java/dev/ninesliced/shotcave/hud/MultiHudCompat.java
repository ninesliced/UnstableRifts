package dev.ninesliced.shotcave.hud;

import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.hud.CustomUIHud;
import com.hypixel.hytale.server.core.universe.PlayerRef;

import javax.annotation.Nonnull;
import java.lang.reflect.Method;

/**
 * Optional compatibility with Buuz135 MultipleHUD.
 */
public final class MultiHudCompat {
    private static final String MULTIHUD_MAIN_CLASS = "com.buuz135.mhud.MultipleHUD";

    private static volatile boolean initialized;
    private static volatile Object instance;
    private static volatile Method setCustomHudMethod;
    private static volatile Method hideCustomHudMethod;

    private MultiHudCompat() {
    }

    public static boolean setHud(
            @Nonnull Player player,
            @Nonnull PlayerRef playerRef,
            @Nonnull String hudId,
            @Nonnull CustomUIHud hud) {
        ensureInitialized();
        if (instance == null || setCustomHudMethod == null) {
            return false;
        }
        try {
            setCustomHudMethod.invoke(instance, player, playerRef, hudId, hud);
            return true;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return false;
        }
    }

    public static boolean hideHud(
            @Nonnull Player player,
            @Nonnull PlayerRef playerRef,
            @Nonnull String hudId) {
        ensureInitialized();
        if (instance == null || hideCustomHudMethod == null) {
            return false;
        }
        try {
            hideCustomHudMethod.invoke(instance, player, playerRef, hudId);
            return true;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return false;
        }
    }

    private static void ensureInitialized() {
        if (initialized) {
            return;
        }
        synchronized (MultiHudCompat.class) {
            if (initialized) {
                return;
            }
            try {
                Class<?> cls = Class.forName(MULTIHUD_MAIN_CLASS);
                Method getInstance = cls.getMethod("getInstance");
                Object multiHud = getInstance.invoke(null);

                Method setMethod = cls.getMethod(
                        "setCustomHud",
                        Player.class,
                        PlayerRef.class,
                        String.class,
                        CustomUIHud.class);
                Method hideMethod = cls.getMethod(
                        "hideCustomHud",
                        Player.class,
                        PlayerRef.class,
                        String.class);

                instance = multiHud;
                setCustomHudMethod = setMethod;
                hideCustomHudMethod = hideMethod;
            } catch (ClassNotFoundException ignored) {
            } catch (ReflectiveOperationException | RuntimeException ignored) {
            }
            initialized = true;
        }
    }
}
