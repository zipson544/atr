package pl.autorynek;

import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ItemEnchantmentsComponent;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.text.Style;
import net.minecraft.text.Text;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class Market {
    static final Pattern PAREN = Pattern.compile("\\(\\s*\\$?\\s*([\\d.,]+)\\s*\\)");

    public static class ScanEntry {
        public String id = "", name = "", label = "";
        public List<String> enchants = new ArrayList<>();
        public int count, page;
        public long ref;

        public Config.Item toItem() {
            Config.Item it = new Config.Item();
            it.id = id;
            it.name = name;
            it.enchants = new ArrayList<>(enchants);
            it.label = label;
            it.refPrice = ref;
            it.refPage = page;
            return it;
        }
    }

    public static class Info {
        public String id, name;
        public List<String> enchants, head;

        public boolean hasBad(List<String> bad) {
            if (bad == null) return false;
            for (String e : enchants) for (String b : bad) if (e.startsWith(b + ":")) return true;
            return false;
        }

        public String key() {
            return id + "|" + name + "|" + String.join(",", enchants);
        }

        public String label() {
            String base = name.isEmpty() ? id.replace("minecraft:", "") : name;
            return enchants.isEmpty() ? base : base + " [" + enchants.size() + " ench]";
        }

        public ScanEntry toScanEntry() {
            ScanEntry s = new ScanEntry();
            s.id = id;
            s.name = name;
            s.enchants = new ArrayList<>(enchants);
            s.label = label();
            return s;
        }

        public Config.Item toItem() {
            Config.Item it = new Config.Item();
            it.id = id;
            it.name = name;
            it.enchants = new ArrayList<>(enchants);
            it.label = label();
            return it;
        }
    }

    public static long parseMoney(String s) {
        if (s == null) return -1;
        s = s.trim().replace("$", "").replace(" ", "").toLowerCase(Locale.ROOT);
        if (s.isEmpty()) return -1;
        double mul = 1;
        char last = s.charAt(s.length() - 1);
        if (last == 'k') { mul = 1e3; s = s.substring(0, s.length() - 1); }
        else if (last == 'm') { mul = 1e6; s = s.substring(0, s.length() - 1); }
        else if (last == 'b') { mul = 1e9; s = s.substring(0, s.length() - 1); }
        if (mul == 1) {
            s = s.replace(",", "");
            if (s.indexOf('.') != s.lastIndexOf('.')) s = s.replace(".", "");
        } else {
            s = s.replace(",", ".");
        }
        try {
            return Math.round(Double.parseDouble(s) * mul);
        } catch (Exception e) {
            return -1;
        }
    }

    public static String money(long v) {
        return String.format(Locale.US, "%,d", v).replace(',', ' ') + "$";
    }

    public static List<String> lore(ItemStack s) {
        List<String> r = new ArrayList<>();
        LoreComponent l = s.get(DataComponentTypes.LORE);
        if (l != null) for (Text t : l.lines()) r.add(t.getString());
        return r;
    }

    /** Cena z lore: "Koszt $32.99k ($32,999)" lub "Cena produktu: $19.98k ($19,980)" - bierzemy dokładną z nawiasu. */
    public static long offerPrice(ItemStack s) {
        for (String ln : lore(s)) {
            if (ln.contains("Koszt") || ln.contains("Cena")) {
                Matcher m = PAREN.matcher(ln);
                if (m.find()) return parseMoney(m.group(1));
            }
        }
        return -1;
    }

    public static String seller(ItemStack s) {
        for (String ln : lore(s)) {
            int i = ln.indexOf("Wystawi");
            if (i >= 0) {
                int c = ln.indexOf(' ', i);
                return c < 0 ? "" : ln.substring(c).trim();
            }
        }
        return "";
    }

    static void collect(List<String> r, ItemEnchantmentsComponent c) {
        if (c == null) return;
        for (RegistryEntry<Enchantment> e : c.getEnchantments()) {
            String id = e.getKey().map(k -> k.getValue().toString()).orElse("?");
            r.add(id + ":" + c.getLevel(e));
        }
    }

    public static Info info(ItemStack s) {
        Info i = new Info();
        i.id = Registries.ITEM.getId(s.getItem()).toString();
        String disp = s.getName().getString();
        String def = s.getItem().getName().getString();
        i.name = disp.equals(def) ? "" : disp;
        i.enchants = new ArrayList<>();
        collect(i.enchants, s.get(DataComponentTypes.ENCHANTMENTS));
        collect(i.enchants, s.get(DataComponentTypes.STORED_ENCHANTMENTS));
        Collections.sort(i.enchants);
        i.head = new ArrayList<>();
        for (String ln : lore(s)) {
            String t = ln.trim();
            if (t.startsWith("\u00bb")) break;
            if (!t.isEmpty()) i.head.add(t);
        }
        return i;
    }

    public static boolean matches(Config.Item e, Info i) {
        if (!e.id.equals(i.id)) return false;
        if (!e.name.isEmpty() && !e.name.equals(i.name)) return false;
        if (!e.enchants.equals(i.enchants)) return false;
        for (String l : e.lore) if (!i.head.contains(l)) return false;
        return true;
    }

    public static boolean sameItem(Config.Item a, Config.Item b) {
        return a.id.equals(b.id) && a.name.equals(b.name) && a.enchants.equals(b.enchants);
    }

    public static String label(Config.Item e) {
        if (!e.label.isEmpty()) return e.label;
        return e.name.isEmpty() ? e.id.replace("minecraft:", "") : e.name;
    }

    /** Zwraca tekst zaznaczonej (żółtej) opcji z lore przycisku sortowania. */
    public static String selectedOption(ItemStack s) {
        LoreComponent l = s.get(DataComponentTypes.LORE);
        if (l == null) return "";
        for (Text t : l.lines()) {
            Optional<String> o = t.visit((Style style, String str) -> {
                if (style.getColor() != null && str.chars().anyMatch(Character::isLetter)) {
                    int rgb = style.getColor().getRgb();
                    int r = (rgb >> 16) & 255, g = (rgb >> 8) & 255, b = rgb & 255;
                    if (r > 200 && g > 200 && b < 130) return Optional.of(str);
                }
                return Optional.<String>empty();
            }, Style.EMPTY);
            if (o.isPresent()) return o.get();
        }
        return "";
    }
}
