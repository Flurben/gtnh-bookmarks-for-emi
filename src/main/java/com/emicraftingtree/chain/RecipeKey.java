package com.emicraftingtree.chain;

import org.jetbrains.annotations.Nullable;

import dev.emi.emi.api.recipe.EmiRecipe;

/**
 * Identity of a bookmarked recipe (NEI's RecipeId): the EMI recipe id plus its category id (NEI's "handler").
 * Equality is by id only, so keys survive EMI reloads. The EMI recipe itself is looked up lazily, because bookmarks are
 * loaded before EMI has finished loading recipes.
 */
public final class RecipeKey {

    /** Looks up EMI recipes by id; replaced with a real lookup in game, a no-op in unit tests. */
    public interface Resolver {

        @Nullable
        EmiRecipe resolve(String id);

        int generation();
    }

    private static Resolver resolver = new Resolver() {

        @Override
        public @Nullable EmiRecipe resolve(String id) {
            return null;
        }

        @Override
        public int generation() {
            return 0;
        }
    };

    public final String id;
    /** Recipe category id, used as NEI's "handler" for markers and statistics. */
    public final String handlerName;

    private @Nullable EmiRecipe recipe;
    private int resolvedGeneration = -1;

    public RecipeKey(String id, String handlerName, @Nullable EmiRecipe recipe) {
        this.id = id;
        this.handlerName = handlerName;
        this.recipe = recipe;
    }

    public static void setResolver(Resolver resolver) {
        RecipeKey.resolver = resolver;
    }

    public static RecipeKey of(String id, String handlerName) {
        return new RecipeKey(id, handlerName, null);
    }

    public static RecipeKey of(EmiRecipe recipe) {
        return new RecipeKey(
                String.valueOf(recipe.getId()),
                recipe.getCategory() != null ? String.valueOf(recipe.getCategory().getId()) : "",
                recipe);
    }

    /** The EMI recipe, or null while EMI is loading or when the recipe no longer exists. */
    public @Nullable EmiRecipe getRecipe() {
        final int generation = resolver.generation();

        if (this.resolvedGeneration != generation) {
            final EmiRecipe resolved = resolver.resolve(this.id);

            if (resolved != null || generation != 0) {
                this.recipe = resolved;
            }

            this.resolvedGeneration = generation;
        }

        return this.recipe;
    }

    @Override
    public boolean equals(Object obj) {
        return obj instanceof RecipeKey other && this.id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return this.id.hashCode();
    }

    @Override
    public String toString() {
        return this.id;
    }

}
