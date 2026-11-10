package pl.autorynek;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class Config {
    public static class Item {
        public String id = "";
        public String name = "";
        public List<String> enchants = new ArrayList<>();
        public List<String> lore = new ArrayList<>();   // opcjonalne wymagane linie lore (edytuj w JSON)
        public long maxPrice = 0;      // max cena ZA SZTUKĘ przy kupnie
        public long sellPrice = 0;     // cena sprzedaży za sztukę (0 = auto)
        public long refPrice = 0;      // cena referencyjna ze skanu
        public int refPage = 0;        // strona (sort. po cenie rosnaco), na ktorej widziano najtansza oferte
        public boolean autoPrice = false; // po skanie ustaw maxPrice = ref * (1 - marża)
        public boolean enabled = true;
        public String label = "";
    }

    public List<Item> items = new ArrayList<>();
    public String webhook = "";

    // timing
    public int clickDelayMs = 40;
    public int jitterMs = 10;
    public int openRetryMs = 1500;
    public int confirmTimeoutMs = 1500;
    public int scanDelayMs = 120;
    public int scanSettleMs = 60;

    // ekonomia
    public double marginPercent = 10; // nieuzywane (zostawione dla starych configow)
    public double minProfitPercent = 12; // minimalny zysk NETTO (po 3% podatku i undercucie)
    public boolean debug = true; // komunikaty o krokach kupowania na czacie
    public double undercutPercent = 1;
    public long reserve = 0;
    public long maxSpendPerHour = 0; // 0 = bez limitu
    public boolean autoSell = false;
    public List<String> badEnchants = new ArrayList<>(List.of("minecraft:knockback", "minecraft:sweeping_edge")); // przedmioty z tymi enchantami sa ignorowane
    public boolean checkPriceBeforeSell = true; // przed /wystaw skanuje aktualna cene przedmiotu
    public int targetPages = 3;                 // ile stron skanowac przy sprawdzaniu ceny
    public boolean sellPerUnit = false; // czy /wystaw <ilosc> <cena> przyjmuje cenę za sztukę

    // skan
    public int scanMaxPages = 600;
    public int quickScanPages = 5;

    // sloty GUI rynku (patrz screeny)
    public int refreshSlot = 0;     // siekierka (filtr Narzędzia)
    public int refreshButton = 1;   // 1 = prawy klik
    public int sortSlot = 53;       // repeater - sortowanie
    public int nextSlot = 50;       // zielony barwnik - następna strona

    /** Maksymalna cena kupna tak, zeby po sprzedazy (ref - undercut, -3% podatku) zostalo minProfitPercent zysku. */
    public long buyPrice(long ref) {
        return (long) Math.floor(ref * (1 - undercutPercent / 100.0) * 0.97 / (1 + minProfitPercent / 100.0));
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static Config inst;

    public static Config get() {
        if (inst == null) load();
        return inst;
    }

    static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve("autorynek.json");
    }

    public static void load() {
        try {
            Path p = path();
            if (Files.exists(p)) inst = GSON.fromJson(Files.readString(p), Config.class);
        } catch (Exception ignored) {}
        if (inst == null) inst = new Config();
        if (inst.items == null) inst.items = new ArrayList<>();
        if (inst.webhook == null) inst.webhook = "";
    }

    public static void save() {
        try {
            Files.writeString(path(), GSON.toJson(get()));
        } catch (Exception ignored) {}
    }
}
