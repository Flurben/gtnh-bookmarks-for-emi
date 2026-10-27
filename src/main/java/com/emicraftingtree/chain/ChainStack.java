package com.emicraftingtree.chain;

import java.util.Objects;

import org.jetbrains.annotations.Nullable;

import dev.emi.emi.api.stack.EmiStack;

/**
 * A stack as seen by the chain math: a type key (item or fluid plus NBT, ignoring durability), the amount it has in
 * its recipe, and the EMI stack used for display. The math only ever compares keys and amounts.
 */
public final class ChainStack {

    public final String key;
    public final long amount;
    public final boolean fluid;
    public final @Nullable EmiStack emi;

    public ChainStack(String key, long amount, boolean fluid, @Nullable EmiStack emi) {
        this.key = key;
        this.amount = amount;
        this.fluid = fluid;
        this.emi = emi;
    }

    public static ChainStack of(String key, long amount) {
        return new ChainStack(key, amount, false, null);
    }

    public ChainStack withAmount(long amount) {
        return new ChainStack(this.key, amount, this.fluid, this.emi);
    }

    public boolean sameType(ChainStack other) {
        return other != null && this.key.equals(other.key);
    }

    @Override
    public boolean equals(Object obj) {
        return obj instanceof ChainStack other && this.key.equals(other.key);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(this.key);
    }

    @Override
    public String toString() {
        return this.amount + "x" + this.key;
    }

}
