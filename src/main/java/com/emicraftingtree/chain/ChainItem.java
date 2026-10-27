package com.emicraftingtree.chain;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;

import org.jetbrains.annotations.Nullable;

/**
 * One bookmark entry: a recipe result, a recipe ingredient or a plain item, inside a bookmark group. Port of NEI's
 * BookmarkItem. Amounts are {@code factor * multiplier}, scaled by {@code chance / CHANCE_FULL}.
 */
public class ChainItem {

    public static final long CHANCE_FULL = 10000;

    public enum Type {

        INGREDIENT,
        RESULT,
        ITEM;

        public static Type fromInt(int type) {
            return type == 0 ? ITEM : type == 1 ? RESULT : INGREDIENT;
        }

        public int toInt() {
            return this == ITEM ? 0 : this == RESULT ? 1 : 2;
        }
    }

    public int groupId;

    public final ChainStack stack;
    public final Map<String, ChainStack> permutations;

    public @Nullable RecipeKey recipe;
    public Type type;

    public long multiplier;
    public final long factor;
    public final long chance;

    public ChainItem(int groupId, ChainStack stack, Map<String, ChainStack> permutations, @Nullable RecipeKey recipe,
            Type type, long multiplier, long factor, long chance) {
        this.groupId = groupId;
        this.stack = stack;
        this.permutations = permutations;
        this.recipe = recipe;
        this.type = type == null ? Type.ITEM : type;
        this.multiplier = multiplier;
        this.factor = factor;
        this.chance = chance;
    }

    public ChainItem(ChainStack stack, Map<String, ChainStack> permutations, @Nullable RecipeKey recipe, Type type,
            long multiplier, long factor, long chance) {
        this(0, stack, permutations, recipe, type, multiplier, factor, chance);
    }

    /**
     * Builds a recipe item from the total expected amount it has in one craft, already multiplied by chance
     * ({@code amountTimesChance = sum(amount * chance * CHANCE_FULL)}).
     */
    public static ChainItem ofRecipe(int groupId, ChainStack stack, Map<String, ChainStack> permutations,
            RecipeKey recipe, Type type, long multiplier, long amountTimesChance) {

        if ((amountTimesChance % CHANCE_FULL) == 0) {
            return new ChainItem(groupId, stack, permutations, recipe, type, multiplier,
                    amountTimesChance / CHANCE_FULL, CHANCE_FULL);
        }

        return new ChainItem(groupId, stack, permutations, recipe, type, multiplier, 1, amountTimesChance);
    }

    public static ChainItem ofRecipe(ChainStack stack, Map<String, ChainStack> permutations, RecipeKey recipe,
            Type type, long multiplier, long amountTimesChance) {
        return ofRecipe(0, stack, permutations, recipe, type, multiplier, amountTimesChance);
    }

    /** A plain item; like NEI, its amount is carried by the multiplier with a factor of 1. */
    public static ChainItem ofItem(int groupId, ChainStack stack, long amount) {
        return new ChainItem(groupId, stack, singlePermutation(stack), null, Type.ITEM, amount, 1, CHANCE_FULL);
    }

    public static ChainItem ofItem(ChainStack stack, long amount) {
        return ofItem(0, stack, amount);
    }

    public static Map<String, ChainStack> singlePermutation(ChainStack stack) {
        return Collections.singletonMap(stack.key, stack);
    }

    public ChainItem copyWithMultiplier(long multiplier) {
        return new ChainItem(this.groupId, this.stack, this.permutations, this.recipe, this.type, multiplier,
                this.factor, this.chance);
    }

    public ChainItem copyWithAmount(long amount) {
        return copyWithMultiplier(getMultiplierFromAmount(amount));
    }

    public ChainItem copy() {
        return copyWithMultiplier(this.multiplier);
    }

    public ChainItem copyWithFactor(long factor, long chance) {
        return new ChainItem(this.groupId, this.stack, this.permutations, this.recipe, this.type, this.multiplier,
                factor, chance);
    }

    /** Copy using another accepted alternative as the displayed stack, rescaling the amount like NEI does. */
    public ChainItem copyWithPerm(ChainStack perm) {

        if (this.recipe != null && !perm.sameType(this.stack)) {
            final ChainStack oldPerm = this.permutations.get(this.stack.key);
            final ChainStack newPerm = this.permutations.get(perm.key);

            if (oldPerm != null && newPerm != null) {
                final long amount = oldPerm.amount > 0 ? this.factor * this.chance * newPerm.amount / oldPerm.amount
                        : 0;
                final long factor = (amount % CHANCE_FULL) == 0 ? amount / CHANCE_FULL : 1;
                final long chance = factor == 1 ? amount : CHANCE_FULL;

                return new ChainItem(this.groupId, newPerm, this.permutations, this.recipe, this.type,
                        this.multiplier, factor, chance);
            }
        }

        return copy();
    }

    public boolean containsItems(ChainItem item) {
        for (String key : this.permutations.keySet()) {
            if (item.permutations.containsKey(key)) {
                return true;
            }
        }
        return false;
    }

    public long getAmount() {
        return getAmount(this.multiplier);
    }

    public long getAmount(long multiplier, @Nullable ChainStack perm) {

        if (this.recipe != null && perm != null && !perm.sameType(this.stack)) {
            final ChainStack oldPerm = this.permutations.get(this.stack.key);
            final ChainStack newPerm = this.permutations.get(perm.key);

            if (oldPerm != null && newPerm != null) {
                final long amount = oldPerm.amount > 0 ? this.factor * this.chance * newPerm.amount / oldPerm.amount
                        : 0;
                final long factor = (amount % CHANCE_FULL) == 0 ? amount / CHANCE_FULL : 1;
                final long chance = factor == 1 ? amount : CHANCE_FULL;

                return getAmount(multiplier, factor, chance);
            }
        }

        return getAmount(multiplier);
    }

    public long getAmount(long multiplier) {
        return getAmount(multiplier, this.factor, this.chance);
    }

    protected long getAmount(long multiplier, long factor, long chance) {
        final long amount = factor * multiplier;

        if (chance != CHANCE_FULL) {

            if (this.type == Type.INGREDIENT) {
                // For ingredients, round up the expected amount to be conservative.
                return (amount * chance + CHANCE_FULL - 1) / CHANCE_FULL;
            } else {
                // For results, round down the expected amount to be conservative.
                return (amount * chance) / CHANCE_FULL;
            }

        }

        return amount;
    }

    public long getMultiplierFromAmount(long amount) {
        if (this.factor <= 0 || this.chance <= 0) return 0;

        if (this.chance == CHANCE_FULL) {
            return (amount + this.factor - 1) / this.factor;
        }

        return (amount * CHANCE_FULL + this.factor * this.chance - 1) / (this.factor * this.chance);
    }

    public long getMultiplier() {
        return this.multiplier;
    }

    public boolean equalsRecipe(ChainItem item) {
        return equalsRecipe(item.recipe, item.groupId);
    }

    public boolean equalsRecipe(@Nullable RecipeKey recipe, int groupId) {
        return groupId == this.groupId && recipe != null && recipe.equals(this.recipe);
    }

    public boolean emptyFactor() {
        return this.factor <= 0 || this.chance <= 0;
    }

    @Override
    public int hashCode() {
        // with the stack in the hash, plain items of one group don't all share a bucket (switching to another
        // alternative makes a new item, see copyWithPerm)
        return Objects.hash(this.groupId, this.type, this.recipe, this.stack.key);
    }

    @Override
    public boolean equals(Object object) {

        if (object instanceof ChainItem item) {
            return this.groupId == item.groupId && this.type == item.type && this.stack.sameType(item.stack)
                    && Objects.equals(this.recipe, item.recipe);
        }

        return false;
    }

    @Override
    public String toString() {
        return this.type + "[" + this.stack.key + " x" + this.multiplier + " f" + this.factor + " @" + this.recipe
                + " g" + this.groupId + "]";
    }

}
