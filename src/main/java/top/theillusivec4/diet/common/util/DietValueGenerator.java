package top.theillusivec4.diet.common.util;

import com.google.common.base.Stopwatch;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.item.Food;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.crafting.IRecipe;
import net.minecraft.item.crafting.Ingredient;
import net.minecraft.item.crafting.RecipeManager;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.Tags;
import net.minecraftforge.event.OnDatapackSyncEvent;
import net.minecraftforge.fml.event.server.FMLServerStartingEvent;
import net.minecraftforge.registries.ForgeRegistries;
import top.theillusivec4.diet.DietMod;
import top.theillusivec4.diet.api.IDietGroup;
import top.theillusivec4.diet.common.group.DietGroups;
import top.theillusivec4.diet.common.network.DietNetwork;
import top.theillusivec4.diet.common.network.server.SPacketGeneratedValues;

public class DietValueGenerator {

  private static final Map<Item, Set<IDietGroup>> GENERATED = new HashMap<>();
  private static final Map<Item, List<Item>> TRAILS = new HashMap<>();
  private static final Comparator<ItemStack> COMPARATOR = new IngredientComparator();

  private static final Tags.IOptionalNamedTag<Item> INGREDIENTS =
      ItemTags.createOptional(new ResourceLocation(DietMod.id("ingredients")));
  private static final Tags.IOptionalNamedTag<Item> SPECIAL_FOOD =
      ItemTags.createOptional(new ResourceLocation(DietMod.id("special_food")));

  private static final Set<Item> UNGROUPED = new HashSet<>();
  private static final Map<Item, List<IRecipe<?>>> RECIPES = new HashMap<>();
  private static final Set<Item> PROCESSED = new HashSet<>();

  public static void setup() {
    MinecraftForge.EVENT_BUS.addListener(DietValueGenerator::onDatapackSync);
    MinecraftForge.EVENT_BUS.addListener(DietValueGenerator::serverStarting);
  }

  private static void onDatapackSync(final OnDatapackSyncEvent evt) {

    if (evt.getPlayer() == null) {
      DietValueGenerator.reload(evt.getPlayerList().getServer());
    } else {
      DietValueGenerator.sync(evt.getPlayer());
    }
  }

  private static void serverStarting(final FMLServerStartingEvent evt) {
    DietValueGenerator.reload(evt.getServer());
  }

  public static void reload(MinecraftServer server) {
    DietMod.LOGGER.info("Generating diet values...");
    Stopwatch stopwatch = Stopwatch.createUnstarted();
    stopwatch.reset();
    stopwatch.start();
    GENERATED.clear();
    TRAILS.clear();
    Set<IDietGroup> groups = DietGroups.get();
    findUngroupedFoods(groups);
    RecipeManager recipeManager = server.getRecipeManager();
    findAllRecipesForItems(recipeManager);
    processItems(groups);
    stopwatch.stop();
    DietMod.LOGGER.info("Generating diet values took {}", stopwatch);

    for (ServerPlayerEntity player : server.getPlayerList().getPlayers()) {
      DietNetwork.sendGeneratedValuesS2C(player, GENERATED);
    }
  }

  private static void processItems(Set<IDietGroup> groups) {
    DietMod.LOGGER.info("Processing items...");
    PROCESSED.clear();

    for (Item item : UNGROUPED) {

      if (!PROCESSED.contains(item)) {
        List<Item> trail = new ArrayList<>();
        findGroups(groups, item, trail);
      }
    }
    DietMod.LOGGER.info("Processed {} items", PROCESSED.size());
  }

  private static Set<IDietGroup> findGroups(Set<IDietGroup> groups, Item targetItem,
                                            List<Item> trail) {
    PROCESSED.add(targetItem);
    Set<IDietGroup> result = new HashSet<>();
    ItemStack proxy = new ItemStack(targetItem);

    for (IDietGroup group : groups) {

      if (group.contains(proxy)) {
        result.add(group);
      }
    }

    if (result.isEmpty()) {
      List<IRecipe<?>> list = RECIPES.get(targetItem);

      if (list != null) {
        Set<IDietGroup> found = new HashSet<>();

        for (IRecipe<?> recipe : list) {
          Set<Item> keys = new HashSet<>();

          for (Ingredient ingredient : recipe.getIngredients()) {
            ItemStack[] itemStacks = ingredient.getMatchingStacks().clone();

            if (itemStacks.length == 0) {
              continue;
            }
            Arrays.sort(itemStacks, COMPARATOR);

            for (int i = 0; i < itemStacks.length; i++) {
              ItemStack stack = itemStacks[i];
              Item item = stack.getItem();

              if (i == 0) {

                if (keys.contains(item)) {
                  break;
                }
                keys.add(item);
              }

              if (!INGREDIENTS.contains(item)) {
                Set<IDietGroup> fallback = GENERATED.get(item);
                boolean addTrail = false;

                if (fallback != null) {
                  found.addAll(fallback);
                  addTrail = !fallback.isEmpty();
                } else if (!PROCESSED.contains(item)) {
                  List<Item> subTrail = new ArrayList<>();
                  Set<IDietGroup> pending = new HashSet<>(findGroups(groups, item, subTrail));
                  found.addAll(pending);
                  GENERATED.putIfAbsent(item, pending);
                  TRAILS.putIfAbsent(item, subTrail);
                  addTrail = !pending.isEmpty();
                }

                if (addTrail) {
                  List<Item> subTrail = TRAILS.get(item);

                  if (subTrail != null) {
                    trail.add(item);
                    trail.addAll(subTrail);
                  }
                }

                if (!found.isEmpty()) {
                  break;
                }
              }
            }
          }

          if (!found.isEmpty()) {
            break;
          }
        }
        result.addAll(found);
      }
    }
    GENERATED.putIfAbsent(targetItem, result);
    TRAILS.putIfAbsent(targetItem, trail);
    return result;
  }

  @SuppressWarnings("ConstantConditions")
  private static void findAllRecipesForItems(RecipeManager recipeManager) {
    DietMod.LOGGER.info("Building item-to-recipes map...");
    RECIPES.clear();
    Map<Item, List<IRecipe<?>>> result = new HashMap<>();

    for (IRecipe<?> recipe : recipeManager.getRecipes()) {
      ItemStack output = ItemStack.EMPTY;

      try {
        output = recipe.getRecipeOutput();
      } catch (Exception e) {
        DietMod.LOGGER.error("Diet was unable to process recipe: {}", recipe.getId());
      }

      // This shouldn't be necessary but some mods are violating the non-null contract, so
      // we have to check for it anyways
      if (output == null) {
        DietMod.LOGGER.debug("Diet was unable to process recipe due to null output: {}",
            recipe.getId());
        continue;
      }
      Item item = output.getItem();
      List<IRecipe<?>> current = result.computeIfAbsent(item, k -> new ArrayList<>());
      current.add(recipe);
    }

    for (List<IRecipe<?>> list : result.values()) {

      if (list.size() > 1) {
        list.sort(Comparator.comparing(IRecipe::getId));
      }
    }
    RECIPES.putAll(result);
    DietMod.LOGGER.info("Found {} valid items with recipes", RECIPES.size());
  }

  private static void findUngroupedFoods(Set<IDietGroup> groups) {
    DietMod.LOGGER.info("Finding ungrouped food items...");
    UNGROUPED.clear();
    Set<Item> result = new HashSet<>();
    items:
    for (Item item : ForgeRegistries.ITEMS) {
      Food food = item.getFood();

      if ((food != null && food.getHealing() > 0) || SPECIAL_FOOD.contains(item)) {

        for (IDietGroup group : groups) {

          if (group.contains(new ItemStack(item))) {
            continue items;
          }
        }
        result.add(item);
      }
    }
    UNGROUPED.addAll(result);
    DietMod.LOGGER.info("Found {} ungrouped food items", UNGROUPED.size());
  }

  public static void sync(ServerPlayerEntity player) {
    DietNetwork.sendGeneratedValuesS2C(player, GENERATED);
  }

  public static void sync(SPacketGeneratedValues packet) {
    GENERATED.clear();
    GENERATED.putAll(packet.generated);
  }

  public static Optional<Set<IDietGroup>> get(Item item) {
    return Optional.ofNullable(GENERATED.get(item));
  }

  public static List<Item> getTrail(Item item) {
    return TRAILS.getOrDefault(item, Collections.emptyList());
  }

  private static class IngredientComparator implements Comparator<ItemStack> {

    @Override
    public int compare(ItemStack o1, ItemStack o2) {
      ResourceLocation rl1 = o1.getItem().getRegistryName();
      ResourceLocation rl2 = o2.getItem().getRegistryName();

      if (rl1 == null) {
        return 1;
      } else if (rl2 == null) {
        return -1;
      } else {
        String namespace1 = rl1.getNamespace();
        String namespace2 = rl2.getNamespace();

        if (namespace1.equals("minecraft") && !namespace2.equals("minecraft")) {
          return -1;
        } else if (namespace2.equals("minecraft") && !namespace1.equals("minecraft")) {
          return 1;
        } else {
          return rl1.compareNamespaced(rl2);
        }
      }
    }
  }
}
