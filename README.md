# GTNH Bookmarks for EMI

A Forge 1.20.1 addon for [EMI](https://github.com/emilyploszaj/emi) that brings the bookmark panel of
[GTNewHorizons' NotEnoughItems](https://github.com/GTNewHorizons/NotEnoughItems) to EMI's favorites sidebar.

You get NEI's bookmark groups and crafting chains:
- several recipe trees at once, side by side in the panel;
- calculated amounts for every step;
- collapsible recipes and groups;
- auto-crafting.

Everything is saved between sessions.

Requirements: Minecraft 1.20.1, Forge 47+, EMI 1.1.22 to 1.1.x (built and tested against 1.1.24). Client-side only.
Auto-crafting uses EMI's recipe filling: with EMI on the server it sends EMI's fill packet, otherwise EMI falls back to
normal inventory clicks. Only item chat links (`Ctrl + L`) need this mod on the server.

## The panel

The addon takes over EMI's **favorites** sidebar, which is the left sidebar by default. EMI keeps the sidebar frame,
the page arrows and the sidebar-type button. Inside it, bookmarks are laid out the way NEI does it:

- A bookmarked recipe is stored as its result followed by its ingredients.
- Bookmarks belong to **groups**. A group always starts on a new row and is marked by a bracket in a 7px gutter left
  of the grid.
- A group is drawn with a gray bracket, or a green one when it is a **crafting chain**.
- **View modes:**
  - *To-do list* (the default) puts every recipe and every plain item on its own row, with the result first. This
    is the layout in NEI's screenshots.
  - *Default* packs the items edge to edge; a crafting chain then shows only its outputs. Click the bracket (or the
    page label, for ungrouped bookmarks) to switch.
- **Crafting chain:**
  - The group's recipes are linked together.
  - Amounts are calculated through the whole chain, and the chain's quantity shows as `xN` on its first output.
  - Shift shows what is still missing; Ctrl shows the bookmarked amounts.
- **Collapsing:**
  - A collapsed group shows only its results.
  - A collapsed recipe folds its sub-chain into one bordered row.
- **Group tooltip:** hover a group's bracket to see its "Crafting Chain" summary (outputs and ingredients). With Shift,
  it also uses your inventory: required crafts, missing items and available items.
- **Hotkey list:** hold **Alt** while hovering a bookmark or a bracket.

The page label in EMI's header stands for the default group (items that are in no group), like NEI's page label.

On first start, existing EMI favorites are copied into the panel. Recipe favorites keep their recipe.

## Controls

The controls are NEI's. "A" is EMI's favorite key, which stands in for NEI's bookmark key. All other keys use NEI's
defaults and can be rebound in Controls, under "EMI Bookmarks". Hold **Alt** over a bookmark, a group bracket or any item
to see the hotkeys that apply there.

**Any hovered item** (EMI's item list, the recipe view, your inventory, a bookmark):

| Key | Action |
| --- | --- |
| `A` | Add only the item as a bookmark, never its recipe (over a bookmark: remove it) |
| `Ctrl + A` | Add it with its amount |
| `Shift + A` / `Ctrl + Shift + A` | Add the recipe (results and ingredients) at quantity 0 / 1. In the recipe view this is the recipe under the mouse. In EMI's item list it is the item's favorite recipe: its EMI default recipe (the "set default" button in the recipe view), or its only recipe, or else the recipe EMI prefers |
| `S` | Overlay the recipe on the crafting grid. Press S over nothing to clear it |
| `Shift + C` / `Ctrl + Shift + C` | Craft the item's recipe / craft only what is missing |
| `V` / `Shift + V` | With a chest open: pull the item into your inventory / pull only what is missing |
| `Ctrl + C`, `Ctrl + D`, `Ctrl + X` | Copy the item's name, its tags (NEI's ore dictionary) or its ID |
| `Ctrl + L` | Send an item link to chat; others click it to bookmark the item (needs this mod on the server, like NEI) |
| `R` / `U` | Recipes / uses (EMI's keys) |

**On a bookmark:**

| Key | Action |
| --- | --- |
| `LMB` / `RMB` | Recipes / usages. In cheat mode: give the item (RMB: one); `Shift + RMB` for usages, and `Shift + LMB` for recipes when there is no crafting grid to fill |
| `Shift + LMB` / `Ctrl + Shift + LMB` | Fill the crafting grid with the bookmarked recipe / with the bookmarked quantity (replaces NEI's `Shift + S`) |
| `A` / `Shift + A` | Remove the item (or its recipe, when it is the only result) / remove the recipe |
| `Ctrl + Scroll` | Change the quantity (`Ctrl + Alt + Scroll`: by a stack, or 144 for fluids) |
| `Alt + LMB` | Collapse/expand the recipe (crafting chains), or expand a collapsed group |
| `Shift + LMB + Drag` | Move the bookmark (or its recipe) |
| `LMB + Drag`, `MMB` | Pick the bookmark up, then click to drop it on a slot that accepts stacks |
| `Shift + Scroll` | Change which accepted item an ingredient uses |

**On a group bracket** (or the page label, which stands for the default group):

| Key | Action |
| --- | --- |
| `LMB` / `RMB` | Toggle group mode (default/to-do list) / toggle crafting chain |
| `Alt + LMB` | Collapse/expand the group |
| `LMB + Drag` / `RMB + Drag` | Create or include a group / take the dragged rows out of their group (drag over all of a group's rows to ungroup it; the bookmarks stay) |
| `Shift + LMB + Drag` | Move the group (scroll over the footer arrows while dragging to move it to another namespace) |
| `Ctrl + Scroll` | Change the group's quantity (`Ctrl + Alt + Scroll`: by 64) |
| `Shift + A` | Remove the group |
| `Shift + C` / `Ctrl + Shift + C` | Craft the whole chain / only what is missing |
| `V` / `Shift + V` | Pull the group's ingredients from an open chest |

**Panel:**

| Key | Action |
| --- | --- |
| `<` / `>` in the footer, or scroll over them | Switch bookmark namespace (NEI's bookmark pages) |
| `P` in the footer | Pull the default group's items from an open chest |
| `Page Up` / `Page Down` | Previous / next page |
| `B` | Hide/show the bookmark panel |

## Auto-crafting

`Shift + C` crafts in the screen you have open. Use the inventory for 2x2 recipes and a crafting table for 3x3 ones.
Pressed in EMI's recipe view, it goes back to the screen behind it and crafts there.

- The chain is worked out from what you are carrying, and sub-components are crafted first.
- Each batch (up to 64) is placed with EMI's recipe filling, the same as EMI's own fill button. Then, like a
  player, auto-crafting waits for the result to show up and shift-clicks it out. Mods that compute the result a moment
  later, such as FastWorkbench, need this. Leftovers such as tools go back to the inventory at the end.
- Items are counted the way EMI matches them, so any accepted substitute counts, and so does a tool whatever its
  durability, name or other NBT (GregTech tools included).
- When something can't be crafted, a short message at the top of the screen says why. Examples: missing an
  ingredient, the wrong screen, or a chain still at quantity 0 (set one with `Ctrl + Scroll`).
- Closing the screen stops it.

## Settings

`config/emicraftingtree-client.toml`:
- **Storage:** global or per-world bookmarks.
- **Bookmarking:** whether items keep their recipe, and whether `Shift + A` starts recipes at quantity 1 (NEI: 0).
- **Recipe tooltips:** when bookmarks show the recipe preview (NEI's default: only in the default view mode).
- **Display:** recipe marker style, recipe category icons, and the pop-in animation.
- **Features:** auto-crafting on/off, and the hotkey list on/off.

Bookmarks are saved to `emicraftingtree/bookmarks.json` in the game directory, or under
`emicraftingtree/worlds/<world>/` when per-world.

## How it hooks into EMI

There is no public EMI API for sidebars, so a few small mixins target EMI's own classes. They hand the favorites
sidebar's drawing and input to the addon, and widen the sidebar by the 7px gutter. EMI still treats bookmarks as
favorites, so tooltips, recipe lookups and EMI's own craft clicks keep working.

The mixins are tied to EMI's internals, so the mod declares EMI `[1.1.22,1.2)`.

## Building

```
./gradlew build
```

- The mod jar is written to `build/libs/gtnh-bookmarks-for-emi-<version>.jar`.
- `./gradlew test` runs unit tests for the chain math and the ported bookmark grid.
- `./gradlew runClient -Pselftest` runs a scripted in-game test, then quits. It:
  - creates a flat test world;
  - sets up bookmark groups;
  - drives the panel through EMI's input handlers, holding Shift, Ctrl and Alt where a shortcut needs them (a
    dev-only mixin makes Minecraft report those keys as pressed);
  - auto-crafts a torch chain, and a test recipe that takes any axe, using worn and renamed axes;
  - reloads the bookmarks from disk;
  - saves screenshots to `run/screenshots/selftest-*.png`.

  Add `-Pfastbench` to run it with FastWorkbench and Placebo as well (copy their jars into `compat-libs/` first).

  The test lives in `src/selftest` and is not included in the mod jar.
- `./gradlew runClient -Pbenchmark` times frames and input handling, then quits. It fills the panel with 3 items and
  60 crafting recipes (5 per group, every other group a crafting chain), and measures:
  - frames in the inventory and in EMI's recipe view, with the mouse idle or over bookmarks, the item list, a group
    bracket or a recipe slot, with and without Alt or Shift held;
  - opening recipes and uses;
  - key presses, clicks and scrolls;
  - editing amounts with Ctrl + scroll (bookmark changes rebuild the layout).

  Options:
  - `-Pbaseline` runs the same scenarios with EMI alone. This mod's mixins and client code are left out, and EMI
    favorites stand in for the bookmarks.
  - `-Pbenchsize=600` uses 600 recipes instead of 60.
  - `-Pjfr` also records a Java Flight Recorder profile.

  Results are written to `run/benchmark/<mod|baseline>[-size].json`. The benchmark code is also in `src/selftest`.

## License and credits

The bookmark grid, groups, crafting chain math, sorting, grouping, tooltips and auto-crafting logic are ported from
GTNewHorizons' NotEnoughItems, which is licensed under the GNU LGPL v3. This addon is therefore distributed under the
same license; see `LICENSE`. EMI is by Emi and is MIT licensed; this addon only uses it as a dependency.
