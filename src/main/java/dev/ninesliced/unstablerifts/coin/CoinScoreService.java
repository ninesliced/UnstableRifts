package dev.ninesliced.unstablerifts.coin;

import javax.annotation.Nonnull;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Thread-safe per-player coin score tracker. Coins bypass inventory and
 * increment a counter here instead.
 */
public final class CoinScoreService {

    private static final ConcurrentHashMap<UUID, AtomicInteger> SCORES = new ConcurrentHashMap<>();

    private CoinScoreService() {
    }

    public static int addCoins(@Nonnull UUID playerUuid, int amount) {
        if (amount <= 0) {
            return getScore(playerUuid);
        }
        AtomicInteger score = SCORES.computeIfAbsent(playerUuid, k -> new AtomicInteger(0));
        return score.addAndGet(amount);
    }

    public static int setScore(@Nonnull UUID playerUuid, int amount) {
        int clamped = Math.max(0, amount);
        AtomicInteger score = SCORES.computeIfAbsent(playerUuid, k -> new AtomicInteger(0));
        score.set(clamped);
        return clamped;
    }

    public static void reset(@Nonnull UUID playerUuid) {
        SCORES.remove(playerUuid);
    }

    public static int getScore(@Nonnull UUID playerUuid) {
        AtomicInteger score = SCORES.get(playerUuid);
        return score != null ? score.get() : 0;
    }

    @Nonnull
    public static Map<UUID, Integer> getAllScores() {
        ConcurrentHashMap<UUID, Integer> snapshot = new ConcurrentHashMap<>();
        SCORES.forEach((uuid, atomicInt) -> snapshot.put(uuid, atomicInt.get()));
        return Collections.unmodifiableMap(snapshot);
    }

    public static void clear(@Nonnull UUID playerUuid) {
        SCORES.remove(playerUuid);
    }

    public static void clearAll() {
        SCORES.clear();
    }
}
