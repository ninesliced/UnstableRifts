package dev.ninesliced.unstablerifts.player;

import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.util.EventTitleUtil;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public final class PlayerEventNotifier {

    private PlayerEventNotifier() {
    }

    public static void showEventTitle(@Nonnull PlayerRef playerRef,
                                      @Nonnull String title,
                                      boolean major) {
        showEventTitle(playerRef, title, null, major);
    }

    public static void showEventTitle(@Nonnull PlayerRef playerRef,
                                      @Nonnull String title,
                                      @Nullable String subtitle,
                                      boolean major) {
        Message subtitleMessage = subtitle == null || subtitle.isBlank()
                ? Message.empty()
                : Message.raw(subtitle);
        EventTitleUtil.showEventTitleToPlayer(playerRef, Message.raw(title), subtitleMessage, major);
    }
}
