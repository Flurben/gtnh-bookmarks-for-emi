package com.emicraftingtree.bookmark;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jetbrains.annotations.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.fml.loading.FMLPaths;

import com.emicraftingtree.Config;
import com.emicraftingtree.EmiCraftingTree;
import com.emicraftingtree.chain.ChainItem;
import com.emicraftingtree.chain.ChainStack;
import com.emicraftingtree.chain.ItemContainerResolver;
import com.emicraftingtree.chain.RecipeKey;
import com.emicraftingtree.chain.Stacks;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import dev.emi.emi.api.recipe.EmiRecipe;
import dev.emi.emi.api.stack.EmiIngredient;
import dev.emi.emi.api.stack.EmiStack;
import dev.emi.emi.api.stack.serializer.EmiIngredientSerializer;
import dev.emi.emi.runtime.EmiFavorite;
import dev.emi.emi.runtime.EmiFavorites;

/**
 * Saves the bookmarks and their groups to {@code emicraftingtree/bookmarks.json} in the game directory (or a per-world
 * file, see {@link Config#worldSpecific()}), like NEI's bookmarks.ini. Stacks are written with EMI's serializers, so
 * loading waits until EMI has loaded.
 */
public final class BookmarkStorage {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String FOLDER = EmiCraftingTree.MOD_ID;
    private static final String FILE_NAME = "bookmarks.json";
    private static final long SAVE_DELAY_MS = 500;
    private static final int VERSION = 3;

    private static final List<BookmarkGrid> namespaces = new ArrayList<>();
    private static int activeNamespaceIndex = 0;

    private static @Nullable Path loadedFile;
    private static @Nullable Path file;
    private static boolean fileWorldSpecific;
    private static @Nullable Object fileWorld;
    private static boolean dirty;
    private static long dirtySince;
    private static boolean suppressDirty;

    static {
        namespaces.add(newGrid());
    }

    private BookmarkStorage() {}

    private static BookmarkGrid newGrid() {
        final BookmarkGrid grid = new BookmarkGrid(ItemContainerResolver.INSTANCE);
        grid.setChangeListener(BookmarkStorage::markDirty);
        return grid;
    }

    /** The bookmarks of the active namespace (NEI's bookmark pages, switched with the footer arrows). */
    public static BookmarkGrid grid() {
        return namespaces.get(activeNamespaceIndex);
    }

    public static int getActiveIndex() {
        return activeNamespaceIndex;
    }

    /** Number of namespaces, keeping one empty namespace at the end to start a new one in (NEI's rules). */
    public static int getNamespaceSize() {
        final int lastIndex = namespaces.size() - 1;
        final BookmarkGrid lastGrid = namespaces.get(lastIndex);
        final SortableGroup sortableGroup = BookmarkPanel.sortableGroup();

        if (lastGrid.size() > 0) {
            if (sortableGroup == null || sortableGroup.grid != lastGrid || !sortableGroup.aloneInGrid) {
                namespaces.add(newGrid());
            }
        } else if (lastIndex > 0 && activeNamespaceIndex != lastIndex) {
            final BookmarkGrid prevGrid = namespaces.get(lastIndex - 1);

            if (sortableGroup != null && sortableGroup.grid == prevGrid && sortableGroup.aloneInGrid
                    || prevGrid == grid() && prevGrid.size() == 0) {
                namespaces.remove(lastIndex);
            }
        }

        return namespaces.size();
    }

    public static boolean isEmpty() {
        return namespaces.size() <= 1 && grid().isEmpty();
    }

    public static boolean prevNamespace() {
        getNamespaceSize();
        removeEmptyNamespaces();

        if (activeNamespaceIndex == 0) {
            setNamespace(getNamespaceSize() - 1);
        } else {
            setNamespace(activeNamespaceIndex - 1);
        }

        return true;
    }

    public static boolean nextNamespace() {

        if (removeEmptyNamespaces()) {
            return true;
        }

        if (activeNamespaceIndex == getNamespaceSize() - 1) {
            setNamespace(0);
        } else {
            setNamespace(activeNamespaceIndex + 1);
        }

        return true;
    }

    private static void setNamespace(int namespaceIndex) {
        activeNamespaceIndex = Math.max(0, Math.min(namespaceIndex, namespaces.size() - 1));
        final BookmarkGrid grid = grid();

        if (grid.size() == 0 && activeNamespaceIndex > 0) {
            grid.setViewMode(BookmarkGrid.DEFAULT_GROUP_ID,
                    namespaces.get(activeNamespaceIndex - 1).getViewMode(BookmarkGrid.DEFAULT_GROUP_ID));
        }

        markDirty();
    }

    private static boolean removeEmptyNamespaces() {

        if (activeNamespaceIndex != namespaces.size() - 1 && grid().size() == 0) {
            namespaces.remove(activeNamespaceIndex);
            setNamespace(activeNamespaceIndex);
            return true;
        }

        return false;
    }

    private static void markDirty() {
        if (!suppressDirty) {
            if (!dirty) {
                dirtySince = System.currentTimeMillis();
            }
            dirty = true;
        }
    }

    /** The file for the current world, worked out again only when the world, server or setting changes. */
    private static Path file() {
        final boolean worldSpecific = Config.worldSpecific();
        final Minecraft minecraft = Minecraft.getInstance();
        final Object world = !worldSpecific ? null
                : minecraft.getSingleplayerServer() != null ? minecraft.getSingleplayerServer()
                        : minecraft.getCurrentServer();

        if (file == null || worldSpecific != fileWorldSpecific || world != fileWorld) {
            final Path folder = FMLPaths.GAMEDIR.get().resolve(FOLDER);
            file = worldSpecific ? folder.resolve("worlds").resolve(worldKey()).resolve(FILE_NAME)
                    : folder.resolve(FILE_NAME);
            fileWorldSpecific = worldSpecific;
            fileWorld = world;
        }

        return file;
    }

    private static String worldKey() {
        final Minecraft minecraft = Minecraft.getInstance();
        String key = "global";

        if (minecraft.getSingleplayerServer() != null) {
            final Path root = minecraft.getSingleplayerServer().getWorldPath(LevelResource.ROOT).toAbsolutePath()
                    .normalize();
            key = "local_" + (root.getFileName() != null ? root.getFileName().toString() : "world");
        } else {
            final ServerData server = minecraft.getCurrentServer();

            if (server != null) {
                key = "server_" + server.ip;
            }
        }

        return key.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    /** Loads the bookmark file for the current world if it is not the one in memory. Needs EMI to be loaded. */
    public static void ensureLoaded() {
        final Path file = file();

        if (file.equals(loadedFile)) {
            return;
        }

        if (loadedFile != null && dirty) {
            write(loadedFile);
        }

        loadedFile = file;
        dirty = false;
        suppressDirty = true;

        try {
            namespaces.clear();
            namespaces.add(newGrid());
            activeNamespaceIndex = 0;

            if (Files.exists(file)) {
                read(file);
            } else if (Config.importEmiFavorites() && !Config.worldSpecific()) {
                importEmiFavorites();
                dirty = !grid().isEmpty();
                dirtySince = 0;
            }
        } finally {
            suppressDirty = false;
        }
    }

    /** Writes pending changes once they have settled for a moment. */
    public static void tick() {
        if (dirty && loadedFile != null && System.currentTimeMillis() - dirtySince >= SAVE_DELAY_MS) {
            write(loadedFile);
        }
    }

    public static void flush() {
        if (dirty && loadedFile != null) {
            write(loadedFile);
        }
    }

    /** Forgets the loaded file so the next access reloads (world change). */
    public static void unload() {
        flush();
        loadedFile = null;
    }

    private static void write(Path path) {
        dirty = false;

        try {
            Files.createDirectories(path.getParent());
            final Path temp = path.resolveSibling(FILE_NAME + ".tmp");
            final JsonObject json = new JsonObject();
            final JsonArray array = new JsonArray();
            int active = 0;

            for (int index = 0; index < namespaces.size(); index++) {
                final BookmarkGrid grid = namespaces.get(index);

                if (grid.size() > 0 || array.isEmpty() && index == namespaces.size() - 1) {
                    if (index == activeNamespaceIndex) {
                        active = array.size();
                    }

                    final JsonObject namespace = toJson(grid);
                    namespace.addProperty("page", grid.page);
                    array.add(namespace);
                }
            }

            json.addProperty("version", VERSION);
            json.addProperty("activeNamespace", active);
            json.add("namespaces", array);

            try (Writer writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
                GSON.toJson(json, writer);
            }

            Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            EmiCraftingTree.LOGGER.error("Failed to write bookmarks to {}", path, e);
        }
    }

    private static void read(Path path) {
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            final JsonElement element = GSON.fromJson(reader, JsonElement.class);

            if (element == null || !element.isJsonObject()) {
                return;
            }

            final JsonObject json = element.getAsJsonObject();

            if (json.has("namespaces")) {
                namespaces.clear();

                for (JsonElement namespace : json.getAsJsonArray("namespaces")) {
                    final BookmarkGrid grid = newGrid();
                    fromJson(grid, namespace.getAsJsonObject());

                    if (namespace.getAsJsonObject().has("page")) {
                        grid.page = namespace.getAsJsonObject().get("page").getAsInt();
                    }

                    namespaces.add(grid);
                }

                if (namespaces.isEmpty()) {
                    namespaces.add(newGrid());
                }

                setNamespace(json.has("activeNamespace") ? json.get("activeNamespace").getAsInt() : 0);
            } else {
                fromJson(grid(), json);
            }

            // Before version 3 groups defaulted to the packed view; switch them to one row per recipe once
            if (!json.has("version") || json.get("version").getAsInt() < VERSION) {
                for (BookmarkGrid grid : namespaces) {
                    for (int groupId : grid.getGroups().keySet()) {
                        grid.setViewMode(groupId, BookmarkGrid.DEFAULT_VIEW_MODE);
                    }
                }

                dirty = true;
                dirtySince = 0;
            }
        } catch (Exception e) {
            EmiCraftingTree.LOGGER.error("Failed to read bookmarks from {}", path, e);
        }
    }

    public static JsonObject toJson(BookmarkGrid grid) {
        final JsonObject json = new JsonObject();
        final JsonArray groups = new JsonArray();
        final JsonArray items = new JsonArray();
        final Set<Integer> usedGroups = new HashSet<>();

        for (ChainItem item : grid.getBookmarkItems()) {
            final JsonObject itemJson = itemToJson(item);

            if (itemJson != null) {
                items.add(itemJson);
                usedGroups.add(item.groupId);
            }
        }

        for (Map.Entry<Integer, BookmarkGroup> entry : grid.getGroups().entrySet()) {
            if (entry.getKey() != BookmarkGrid.DEFAULT_GROUP_ID && !usedGroups.contains(entry.getKey())) {
                continue;
            }

            final BookmarkGroup group = entry.getValue();
            final JsonObject groupJson = new JsonObject();
            final JsonArray collapsedRecipes = new JsonArray();

            groupJson.addProperty("id", entry.getKey());
            groupJson.addProperty("viewMode", group.viewMode.name());
            groupJson.addProperty("crafting", group.crafting != null);
            groupJson.addProperty("collapsed", group.collapsed);

            for (RecipeKey recipe : group.collapsedRecipes) {
                collapsedRecipes.add(recipeToJson(recipe));
            }

            groupJson.add("collapsedRecipes", collapsedRecipes);
            groups.add(groupJson);
        }

        json.addProperty("version", 1);
        json.add("groups", groups);
        json.add("items", items);
        return json;
    }

    public static void fromJson(BookmarkGrid grid, JsonObject json) {
        final List<ChainItem> items = new ArrayList<>();

        if (json.has("groups")) {
            for (JsonElement element : json.getAsJsonArray("groups")) {
                final JsonObject groupJson = element.getAsJsonObject();
                final int id = groupJson.get("id").getAsInt();
                BookmarkViewMode viewMode = BookmarkGrid.DEFAULT_VIEW_MODE;

                try {
                    viewMode = BookmarkViewMode.valueOf(groupJson.get("viewMode").getAsString());
                } catch (RuntimeException ignored) {}

                final BookmarkGroup group = new BookmarkGroup(viewMode,
                        groupJson.has("crafting") && groupJson.get("crafting").getAsBoolean());
                group.collapsed = groupJson.has("collapsed") && groupJson.get("collapsed").getAsBoolean();

                if (groupJson.has("collapsedRecipes")) {
                    for (JsonElement recipe : groupJson.getAsJsonArray("collapsedRecipes")) {
                        final RecipeKey key = recipeFromJson(recipe);
                        if (key != null) {
                            group.collapsedRecipes.add(key);
                        }
                    }
                }

                grid.putGroup(id, group);
            }
        }

        if (json.has("items")) {
            for (JsonElement element : json.getAsJsonArray("items")) {
                final ChainItem item = itemFromJson(element.getAsJsonObject());

                if (item != null) {
                    if (grid.getGroup(item.groupId) == null) {
                        grid.putGroup(item.groupId, new BookmarkGroup(BookmarkGrid.DEFAULT_VIEW_MODE));
                    }
                    items.add(item);
                }
            }
        }

        grid.loadItems(items);
    }

    private static @Nullable JsonObject itemToJson(ChainItem item) {
        final JsonElement stack = stackToJson(item.stack);

        if (stack == null) {
            return null;
        }

        final JsonObject json = new JsonObject();
        json.add("stack", stack);
        json.addProperty("amount", item.stack.amount);
        json.addProperty("type", item.type.toInt());
        json.addProperty("multiplier", item.multiplier);
        json.addProperty("factor", item.factor);

        if (item.chance != ChainItem.CHANCE_FULL) {
            json.addProperty("chance", item.chance);
        }

        if (item.groupId != BookmarkGrid.DEFAULT_GROUP_ID) {
            json.addProperty("group", item.groupId);
        }

        if (item.recipe != null) {
            json.add("recipe", recipeToJson(item.recipe));
        }

        if (item.permutations.size() > 1 || !item.permutations.containsKey(item.stack.key)) {
            final JsonArray permutations = new JsonArray();

            for (ChainStack perm : item.permutations.values()) {
                final JsonElement permJson = stackToJson(perm);

                if (permJson != null) {
                    final JsonObject entry = new JsonObject();
                    entry.add("stack", permJson);
                    entry.addProperty("amount", perm.amount);
                    permutations.add(entry);
                }
            }

            json.add("permutations", permutations);
        }

        return json;
    }

    private static @Nullable ChainItem itemFromJson(JsonObject json) {
        final ChainStack stack = stackFromJson(json.get("stack"), json.has("amount") ? json.get("amount").getAsLong()
                : 1);

        if (stack == null) {
            return null;
        }

        final Map<String, ChainStack> permutations = new LinkedHashMap<>();

        if (json.has("permutations")) {
            for (JsonElement element : json.getAsJsonArray("permutations")) {
                final JsonObject entry = element.getAsJsonObject();
                final ChainStack perm = stackFromJson(entry.get("stack"),
                        entry.has("amount") ? entry.get("amount").getAsLong() : 1);

                if (perm != null) {
                    permutations.putIfAbsent(perm.key, perm);
                }
            }
        }

        permutations.putIfAbsent(stack.key, stack);

        return new ChainItem(
                json.has("group") ? json.get("group").getAsInt() : BookmarkGrid.DEFAULT_GROUP_ID,
                stack,
                permutations,
                json.has("recipe") ? recipeFromJson(json.get("recipe")) : null,
                ChainItem.Type.fromInt(json.has("type") ? json.get("type").getAsInt() : 0),
                json.has("multiplier") ? json.get("multiplier").getAsLong() : 1,
                json.has("factor") ? json.get("factor").getAsLong() : 1,
                json.has("chance") ? json.get("chance").getAsLong() : ChainItem.CHANCE_FULL);
    }

    private static JsonObject recipeToJson(RecipeKey recipe) {
        final JsonObject json = new JsonObject();
        json.addProperty("id", recipe.id);
        json.addProperty("category", recipe.handlerName);
        return json;
    }

    private static @Nullable RecipeKey recipeFromJson(JsonElement json) {
        if (json == null || !json.isJsonObject() || !json.getAsJsonObject().has("id")) {
            return null;
        }

        final JsonObject object = json.getAsJsonObject();
        return RecipeKey.of(object.get("id").getAsString(),
                object.has("category") ? object.get("category").getAsString() : "");
    }

    private static @Nullable JsonElement stackToJson(ChainStack stack) {
        if (stack.emi == null) {
            return null;
        }

        try {
            final JsonElement json = EmiIngredientSerializer.getSerialized(stack.emi.copy().setAmount(1));
            return json == null || json.isJsonNull() ? null : json;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static @Nullable ChainStack stackFromJson(@Nullable JsonElement json, long amount) {
        if (json == null) {
            return null;
        }

        try {
            final EmiIngredient ingredient = EmiIngredientSerializer.getDeserialized(json);

            if (ingredient instanceof EmiStack stack && !stack.isEmpty()) {
                return Stacks.of(stack, amount);
            }
        } catch (RuntimeException e) {
            EmiCraftingTree.LOGGER.debug("Skipping unreadable bookmark stack {}", json, e);
        }

        return null;
    }

    /** First start: EMI favorites become bookmarks (recipe favorites keep their recipe, like NEI's linked items). */
    private static void importEmiFavorites() {
        for (EmiFavorite favorite : EmiFavorites.favorites) {
            final List<EmiStack> stacks = favorite.getStack().getEmiStacks();

            if (stacks.isEmpty() || stacks.get(0).isEmpty()) {
                continue;
            }

            final EmiStack emiStack = stacks.get(0);
            final EmiRecipe recipe = favorite.getRecipe();
            ChainItem item = null;

            if (RecipeBookmarks.canBookmark(recipe)) {
                item = RecipeBookmarks.resultItem(recipe, BookmarkGrid.DEFAULT_GROUP_ID, Stacks.of(emiStack), 1);

                if (item != null) {
                    item.type = ChainItem.Type.ITEM;
                }
            }

            if (item == null) {
                item = ChainItem.ofItem(BookmarkGrid.DEFAULT_GROUP_ID, Stacks.of(emiStack, 1), 0);
            }

            grid().addItem(item, false);
        }

        if (!grid().isEmpty()) {
            EmiCraftingTree.LOGGER.info("Imported {} EMI favorites as bookmarks", grid().size());
        }
    }

}
