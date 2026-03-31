package dev.ninesliced.unstablerifts.dungeon;

import com.google.gson.annotations.SerializedName;

import javax.annotation.Nonnull;
import java.util.Random;

/**
 * A min–max integer range used in dungeon generation config.
 * Gson-friendly (plain fields).
 */
public final class IntRange {

    @SerializedName("min")
    private int min;

    @SerializedName("max")
    private int max;

    public IntRange() {
        this(0, 0);
    }

    public IntRange(int min, int max) {
        this.min = min;
        this.max = max;
    }

    public int getMin() {
        return min;
    }

    public int getMax() {
        return max;
    }

    /**
     * Roll a random value in [min, max] (inclusive).
     */
    public int roll(@Nonnull Random random) {
        if (min >= max) return min;
        return min + random.nextInt(max - min + 1);
    }

    @Override
    public String toString() {
        return min == max ? String.valueOf(min) : min + "~" + max;
    }
}

