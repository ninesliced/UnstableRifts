package dev.ninesliced.shotcave.hud;

import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.hud.CustomUIHud;
import com.hypixel.hytale.server.core.universe.PlayerRef;

import javax.annotation.Nonnull;
import java.lang.reflect.Method;

/**
 * Optional compatibility with Buuz135 MultipleHUD.
 */
final class MultiHudCompat {
    private static final String MULTIHUD_MAIN_CLASS = "com.buuz135.mhud.MultipleHUD";
    private static final String SHOTCAVE_HUD_ID = "Shotcave";

    private static volatile boolean initialized;
    private static volatile Object instance;
    private static volatile Method setCustomHudMethod;
    private static volatile Method hideCustomHudMethod;

    private MultiHudCompat() {
    }

    static boolean setHud(@Nonnull Player player, @Nonnull PlayerRef playerRef, @Nonnull CustomUIHud hud) {
        ensureInitialized();
        if (instance == null || setCustomHudMethod == null) {
            return false;
        }
        try {
            setCustomHudMethod.invoke(instance, player, playerRef, SHOTCAVE_HUD_ID, hud);
            return true;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return false;
        }
    }

    static boolean hideHud(@Nonnull Player player, @Nonnull PlayerRef playerRef) {
        ensureInitialized();
        if (instance == null || hideCustomHudMethod == null) {
            return false;
        }
        try {
            hideCustomHudMethod.invoke(instance, player, playerRef, SHOTCAVE_HUD_ID);
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
                    CustomUIHud.class
                );
                Method hideMethod = cls.getMethod(
                    "hideCustomHud",
                    Player.class,
                    PlayerRef.class,
                    String.class
                );

                instance = multiHud;
                setCustomHudMethod = setMethod;
                hideCustomHudMethod = hideMethod;
            } catch (ClassNotFoundException ignored) {
                // MultipleHUD is optional.
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // Keep compatibility optional and fail-safe.
            }
            initialized = true;
        }
    }
}

