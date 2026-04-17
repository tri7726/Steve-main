package com.steve.ai.action.actions;

import com.steve.ai.SteveMod;
import com.steve.ai.action.ActionResult;
import com.steve.ai.action.ActionSlot;
import com.steve.ai.action.Task;
import com.steve.ai.entity.SteveEntity;
import com.steve.ai.memory.SteveInventory;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;

import java.util.EnumSet;
import java.util.List;

/**
 * FeedAnimalAction: Steve finds a target animal, walks to it, and feeds it.
 * Parameters:
 * - animal: Target animal type (e.g., "cow", "sheep", "pig")
 * - item: Item name to feed (optional, defaults to standard for that animal)
 */
@SuppressWarnings("null") // Minecraft API (getEntitiesOfClass, getBoundingBox, getType) guaranteed non-null at runtime
public class FeedAnimalAction extends BaseAction {
    private String animalType;
    private String itemName;
    private Animal targetAnimal;
    private Item foodItem;
    private int ticksRunning;
    private static final int MAX_TICKS = 2400; // 2 minutes
    private static final double FEED_DIST = 3.0;

    public FeedAnimalAction(SteveEntity steve, Task task) {
        super(steve, task);
    }

    @Override
    protected void onStart() {
        animalType = task.getStringParameter("animal");
        itemName = task.getStringParameter("item");
        ticksRunning = 0;

        if (animalType == null) {
            result = ActionResult.failure("Thiếu loại động vật cần cho ăn.");
            return;
        }

        // Determine default food if item not provided
        if (itemName == null || itemName.isBlank()) {
            itemName = getDefaultFood(animalType);
        }

        foodItem = parseItem(itemName);
        if (foodItem == null || foodItem == Items.AIR) {
            result = ActionResult.failure("Vật phẩm thức ăn không hợp lệ: " + itemName);
            return;
        }

        findAnimal();
        if (targetAnimal == null) {
            result = ActionResult.failure("Không tìm thấy con " + animalType + " nào gần đây.");
            return;
        }

        steve.sendChatMessage("Để tao đi cho con " + animalType + " ăn " + foodItem.getDescription().getString() + "!");
        SteveMod.LOGGER.info("Steve '{}' feeding {} to {}", steve.getSteveName(), itemName, animalType);
    }

    @Override
    protected void onTick() {
        ticksRunning++;
        if (ticksRunning > MAX_TICKS) {
            result = ActionResult.failure("Hết thời gian cho động vật ăn");
            return;
        }

        if (targetAnimal == null || !targetAnimal.isAlive() || targetAnimal.isRemoved()) {
            findAnimal();
            if (targetAnimal == null) {
                result = ActionResult.failure("Mất dấu con vật mục tiêu");
                return;
            }
        }

        double dist = steve.distanceTo(targetAnimal);
        if (dist > FEED_DIST) {
            steve.getNavigation().moveTo(targetAnimal, 1.2);
        } else {
            steve.getNavigation().stop();
            executeFeed();
        }
        
        // Look at animal
        steve.getLookControl().setLookAt(targetAnimal, 30f, 30f);
    }

    private void executeFeed() {
        if (!targetAnimal.canFallInLove()) {
            result = ActionResult.success("Con vật này đã no hoặc chưa thể nhân giống.");
            steve.sendChatMessage("Con này đang bận yêu rồi, không ăn nữa!");
            return;
        }

        SteveInventory inv = steve.getMemory().getInventory();
        if (!inv.hasItem(foodItem, 1)) {
            result = ActionResult.failure("Hết " + foodItem.getDescription().getString() + " để cho ăn!");
            steve.sendChatMessage("Hết sạch " + foodItem.getDescription().getString() + " rồi, hông cho ăn được!");
            return;
        }

        // Check if item is valid food for this animal in vanilla
        ItemStack foodStack = new ItemStack(foodItem);
        if (!targetAnimal.isFood(foodStack)) {
            result = ActionResult.failure(foodItem.getDescription().getString() + " không phải thức ăn của " + animalType);
            steve.sendChatMessage("Nó hông có ăn " + foodItem.getDescription().getString() + " đâu!");
            return;
        }

        inv.removeItem(foodItem, 1);
        
        // Trigger love mode
        targetAnimal.setInLove(null); // Player parameter is null since Steve is a Mob
        steve.swing(InteractionHand.MAIN_HAND, true);
        
        // Visual effects are handled by vanilla setInLove
        steve.sendChatMessage("Đã cho " + animalType + " ăn!");
        result = ActionResult.success("Đã cho con " + animalType + " ăn " + foodItem.getDescription().getString());
    }

    @Override
    protected void onCancel() {
        steve.getNavigation().stop();
    }

    @Override
    public String getDescription() {
        String aName = targetAnimal != null ? targetAnimal.getType().getDescription().getString() : animalType;
        String iName = foodItem != null ? foodItem.getDescription().getString() : itemName;
        return "Feed " + iName + " to " + aName;
    }

    @Override
    public EnumSet<ActionSlot> getRequiredSlots() {
        return EnumSet.of(ActionSlot.LOCOMOTION, ActionSlot.INTERACTION);
    }

    private void findAnimal() {
        AABB box = steve.getBoundingBox().inflate(32.0);
        List<Animal> animals = steve.level().getEntitiesOfClass(Animal.class, box);
        
        Animal nearest = null;
        double nearestDist = Double.MAX_VALUE;
        
        String targetType = animalType.toLowerCase().trim();
        
        for (Animal animal : animals) {
            if (!animal.isAlive()) continue;
            
            String entityName = animal.getType().getDescription().getString().toLowerCase();
            var key = ForgeRegistries.ENTITY_TYPES.getKey(animal.getType());
            String entityId = key != null ? key.getPath() : "";
            
            if (entityName.contains(targetType) || entityId.contains(targetType)) {
                double d = steve.distanceTo(animal);
                if (d < nearestDist) {
                    nearestDist = d;
                    nearest = animal;
                }
            }
        }
        targetAnimal = nearest;
    }

    private String getDefaultFood(String animal) {
        return switch (animal.toLowerCase().trim()) {
            case "cow", "sheep", "mushroom_cow", "mooshroom" -> "wheat";
            case "pig", "carrot" -> "carrot";
            case "chicken", "seed" -> "wheat_seeds";
            case "horse", "donkey", "mule" -> "golden_apple";
            case "wolf", "dog" -> "cooked_beef";
            case "cat" -> "cod";
            default -> "wheat";
        };
    }

    private Item parseItem(String name) {
        if (name == null || name.isBlank()) return null;
        name = name.toLowerCase().trim().replace(" ", "_");
        
        // Basic translation map for common foods
        if (name.equals("lua_mi")) name = "wheat";
        if (name.equals("ca_rot")) name = "carrot";
        if (name.equals("hat_giong")) name = "wheat_seeds";
        if (name.equals("khoai_tay")) name = "potato";
        if (name.equals("thit_bo")) name = "cooked_beef";

        if (name.contains(":")) name = name.split(":")[1];
        
        try {
            Item item = ForgeRegistries.ITEMS.getValue(ResourceLocation.parse("minecraft:" + name));
            return (item != null && item != Items.AIR) ? item : null;
        } catch (Exception e) {
            return null;
        }
    }
}
