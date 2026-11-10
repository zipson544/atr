package pl.autorynek;

import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.slot.Slot;
import org.lwjgl.glfw.GLFW;
import pl.autorynek.mixin.HandledScreenAccessor;

public class AutoRynekMod implements ClientModInitializer {
    static KeyBinding toggleKey, panicKey;

    @Override
    public void onInitializeClient() {
        Config.load();
        toggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.autorynek.toggle",
                InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_RIGHT_BRACKET, "category.autorynek"));
        panicKey = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.autorynek.panic",
                InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_END, "category.autorynek"));

        ClientTickEvents.END_CLIENT_TICK.register(c -> {
            while (toggleKey.wasPressed()) Bot.I.toggle();
            while (panicKey.wasPressed()) Bot.I.stopAll("PANIC");
        });

        ClientReceiveMessageEvents.GAME.register((msg, overlay) -> {
            if (!overlay) Bot.I.onChat(msg.getString());
        });

        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> Bot.I.stopAll("rozlaczono"));

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(ClientCommandManager.literal("autorynekgui").executes((CommandContext<FabricClientCommandSource> ctx) -> {
                    MinecraftClient mc = MinecraftClient.getInstance();
                    mc.send(() -> mc.setScreen(new MainScreen()));
                    return 1;
                })));

        // Ctrl+A = dodaj przedmiot pod kursorem do configu
        ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
            if (screen instanceof HandledScreen<?> hs) {
                ScreenKeyboardEvents.allowKeyPress(screen).register((s, key, scancode, mods) -> {
                    if (key == GLFW.GLFW_KEY_A && (mods & GLFW.GLFW_MOD_CONTROL) != 0) {
                        addHovered(hs);
                        return false;
                    }
                    return true;
                });
            }
        });

        Bot.I.init();
    }

    static void addHovered(HandledScreen<?> hs) {
        Slot slot = ((HandledScreenAccessor) hs).autorynek$getFocusedSlot();
        if (slot == null || slot.getStack().isEmpty()) { Bot.I.msg("Najedz kursorem na przedmiot."); return; }
        ItemStack st = slot.getStack();
        Market.Info in = Market.info(st);
        Config cfg = Config.get();
        Config.Item it = in.toItem();
        for (Config.Item e : cfg.items) {
            if (Market.sameItem(e, it)) { Bot.I.msg("Juz jest w configu: " + Market.label(e)); return; }
        }
        long price = Market.offerPrice(st);
        if (price > 0) {
            long unit = price / Math.max(1, st.getCount());
            it.refPrice = unit;
            it.maxPrice = cfg.buyPrice(unit);
        }
        cfg.items.add(it);
        Config.save();
        Bot.I.msg("Dodano: " + Market.label(it) + (it.maxPrice > 0 ? " (kupuj do " + Market.money(it.maxPrice) + ")" : " - ustaw cene w /autorynekgui"));
    }
}
