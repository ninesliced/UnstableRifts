package dev.ninesliced.shotcave.dungeon;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
/**
 * A weighted random selection pool.
 *
 * @param <T> element type
 */
public final class WeightedPool<T> {
    private final List<Entry<T>> entries;
    private final int totalWeight;
    private WeightedPool(@Nonnull List<Entry<T>> entries, int totalWeight) {
        this.entries = entries;
        this.totalWeight = totalWeight;
    }
    @Nonnull
    public static <T> WeightedPool<T> of(@Nullable Map<T, Integer> weights) {
        if (weights == null || weights.isEmpty()) {
            return new WeightedPool<>(Collections.emptyList(), 0);
        }
        List<Entry<T>> entries = new ArrayList<>(weights.size());
        int total = 0;
        for (var e : weights.entrySet()) {
            int w = Math.max(0, e.getValue());
            if (w > 0) {
                entries.add(new Entry<>(e.getKey(), w));
                total += w;
            }
        }
        return new WeightedPool<>(entries, total);
    }
    public boolean isEmpty() {
        return entries.isEmpty();
    }
    public int size() {
        return entries.size();
    }
    @Nullable
    public T pick(@Nonnull Random random) {
        if (entries.isEmpty()) return null;
        if (entries.size() == 1) return entries.get(0).value;
        int roll = random.nextInt(totalWeight);
        int cumulative = 0;
        for (Entry<T> entry : entries) {
            cumulative += entry.weight;
            if (roll < cumulative) {
                return entry.value;
            }
        }
        return entries.getLast().value;
    }
    @Nonnull
    public List<T> values() {
        return entries.stream().map(e -> e.value).toList();
    }
    private record Entry<T>(T value, int weight) {
    }
}
