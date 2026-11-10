package pl.autorynek;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.GenericContainerScreen;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.ClickSlotC2SPacket;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.ScoreboardDisplaySlot;
import net.minecraft.scoreboard.ScoreboardEntry;
import net.minecraft.scoreboard.ScoreboardObjective;
import net.minecraft.scoreboard.Team;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;

import com.google.gson.GsonBuilder;

import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class Bot {
    public static final Bot I = new Bot();

    static final int NONE = 0, LIST = 1, CONFIRM = 2, OTHER = 3;
    static final Pattern TITLE = Pattern.compile("Rynek\\s*\\((\\d+)\\s*/\\s*(\\d+)\\)");
    static final Pattern BUY = Pattern.compile("Pomy\\S+ zakupi\\S+ produkt za ([\\d.,kKmM]+)\\s*\\$");
    static final Pattern SOLD = Pattern.compile("(?s)sprzedany.*?dodane\\s+([\\d.,kKmM]+)\\s*\\$");
    static final Pattern GONE = Pattern.compile("usuni\\S+ albo sprzedany");
    static final Pattern COOL = Pattern.compile("Nast\\S+ raz mo\\S+ to wykona\\S+ za (\\d+)\\s*ms");
    static final Pattern BAL = Pattern.compile("Stan konta:?\\s*([\\d.,]+[kKmM]?)");

    enum S { OPEN, SORT, LOOP, WAIT_CONFIRM, WAIT_RESULT, SELL, SCAN_PAGES }

    static class View {
        int kind = NONE;
        GenericContainerScreenHandler h;
        int page, total;
    }

    public static class Purchase {
        public long time;
        public String label;
        public long price, sellEst, profit;
        public int count;
    }

    static class Pending {
        Config.Item e;
        long price;
        int count;
    }

    final MinecraftClient mc = MinecraftClient.getInstance();
    final Random rnd = new Random();
    final AtomicBoolean queued = new AtomicBoolean(false);

    public volatile boolean running, scanning;
    boolean resumeBuy;
    long cooldownUntil, learnedDelay, sellOverride, targetLowest = -1;
    boolean targetMode;
    Config.Item targetItem;
    int skipTo;
    final Map<Config.Item, Integer> cfgPage = new IdentityHashMap<>();
    S state = S.OPEN;
    long stateSince, lastClick, lastCmd, hashSince, nextDelay = 70, lastAdvance;
    int lastHash, sortTries, sellStage, sellIdx, lastPage = -1;
    Pending pending, sellPending;

    // statystyki
    public long startedAt, spent, income;
    public int buys, sells;
    public final List<Purchase> history = new ArrayList<>();
    final Deque<long[]> spends = new ArrayDeque<>();

    // skan
    public int pagesDone, scanMax, scanTotal, offersSeen, badSkipped;
    public List<Market.ScanEntry> scanResults = new ArrayList<>();
    public long lastScanTime;
    final Map<String, Market.ScanEntry> accE = new LinkedHashMap<>();
    final Map<String, List<Long>> accP = new HashMap<>();
    final Map<Config.Item, List<Long>> cfgP = new IdentityHashMap<>();

    public void init() {
        loadScan();
        ScheduledExecutorService ex = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "autorynek-loop");
            t.setDaemon(true);
            return t;
        });
        ex.scheduleWithFixedDelay(() -> {
            if ((running || scanning) && !queued.getAndSet(true)) {
                mc.execute(() -> {
                    queued.set(false);
                    try {
                        step();
                    } catch (Throwable t) {
                        t.printStackTrace();
                        stopAll("blad: " + t);
                    }
                });
            }
        }, 200, 4, TimeUnit.MILLISECONDS);
    }

    // ---------- sterowanie ----------
    public void msg(String s) {
        if (mc.player != null) mc.player.sendMessage(Text.literal("\u00a7b[AutoRynek] \u00a7f" + s), false);
    }

    void dbg(String s) {
        if (Config.get().debug) msg("\u00a77" + s);
    }

    public void toggle() {
        if (running) stopAll("STOP"); else start();
    }

    public void start() {
        if (scanning) { msg("Trwa skan - poczekaj."); return; }
        if (Config.get().items.isEmpty()) { msg("Config jest pusty - dodaj przedmioty (Ctrl+A w rynku)."); return; }
        running = true;
        learnedDelay = 0;
        if (startedAt == 0) startedAt = System.currentTimeMillis();
        pending = null;
        toOpen(System.currentTimeMillis());
        msg("START");
        Webhook.send("AutoRynek START", 0x57F287);
    }

    public void stopAll(String why) {
        boolean was = running || scanning;
        running = false;
        scanning = false;
        resumeBuy = false;
        pending = null;
        if (was) msg("Zatrzymano: " + why);
    }

    public void startScan(boolean quick) {
        if (scanning) return;
        resumeBuy = running;
        running = false;
        scanning = true;
        accE.clear(); accP.clear(); cfgP.clear(); cfgPage.clear(); targetMode = false;
        pagesDone = 0; lastPage = -1; scanTotal = 0; offersSeen = 0; badSkipped = 0;
        scanMax = quick ? Config.get().quickScanPages : Config.get().scanMaxPages;
        toOpen(System.currentTimeMillis());
        msg(quick ? "Szybki skan..." : "SKAN calego rynku...");
    }

    void toOpen(long now) { state = S.OPEN; stateSince = now; }

    // ---------- czat ----------
    public void onChat(String m) {
        long nowC = System.currentTimeMillis();
        Matcher cm = COOL.matcher(m);
        if (cm.find()) {
            long ms = Long.parseLong(cm.group(1));
            cooldownUntil = nowC + ms + 10;
            if ((running || scanning) && state == S.LOOP && pending == null) {
                learnedDelay = Math.max(learnedDelay, ms + 10);
                dbg("Limit serwera przy odswiezaniu - zwalniam do " + learnedDelay + "ms");
            }
        }
        if (GONE.matcher(m).find() && pending != null) {
            dbg("Oferta juz sprzedana/usunieta (ktos byl szybszy) - wracam do szukania.");
            pending = null;
            if (mc.player != null) mc.player.closeHandledScreen();
            toOpen(nowC);
            lastCmd = 0;
            return;
        }
        Matcher b = BUY.matcher(m);
        if (b.find()) { onBought(Market.parseMoney(b.group(1))); return; }
        Matcher s = SOLD.matcher(m);
        if (s.find()) { onSold(Market.parseMoney(s.group(1))); return; }
    }

    void onBought(long price) {
        if (pending == null) return;
        Config cfg = Config.get();
        Config.Item e = pending.e;
        long unit = sellUnit(e);
        long total = unit * pending.count;
        long net = (long) Math.floor(total * 0.97);
        Purchase p = new Purchase();
        p.time = System.currentTimeMillis();
        p.label = Market.label(e);
        p.price = price > 0 ? price : pending.price;
        p.count = pending.count;
        p.sellEst = total;
        p.profit = net - p.price;
        history.add(0, p);
        if (history.size() > 200) history.remove(history.size() - 1);
        msg("\u00a7aKupiono: " + p.label + " za " + Market.money(p.price) + " (zysk ~" + Market.money(p.profit) + ")");
        buys++;
        spent += p.price;
        spends.addLast(new long[]{p.time, p.price});
        long bal = balance();
        Webhook.send("Zakup", 0x5865F2, "Przedmiot", p.label, "Cena", Market.money(p.price),
                "Ilosc", String.valueOf(p.count), "Szac. sprzedaz", Market.money(total),
                "Zysk (po 3%)", Market.money(p.profit), "Saldo", bal >= 0 ? Market.money(bal) : "?");
        if (cfg.autoSell && total > 0) {
            if (cfg.checkPriceBeforeSell) {
                startTarget(pending);
            } else {
                sellPending = pending;
                sellOverride = 0;
                sellStage = 0;
                state = S.SELL;
                stateSince = System.currentTimeMillis();
            }
        } else {
            toOpen(System.currentTimeMillis());
        }
        pending = null;
    }

    void onSold(long amount) {
        sells++;
        if (amount > 0) income += amount;
        Webhook.send("Sprzedaz", 0x57F287, "Przychod", amount > 0 ? Market.money(amount) : "?");
        if (Config.get().autoSell && running && !scanning) startScan(true);
    }

    // ---------- helpers ----------
    long sellUnit(Config.Item e) {
        Config cfg = Config.get();
        if (e.sellPrice > 0) return e.sellPrice;
        double u = e.refPrice > 0 ? e.refPrice : e.maxPrice * (1 + cfg.minProfitPercent / 100.0) / 0.97;
        return (long) Math.floor(u * (1 - cfg.undercutPercent / 100.0));
    }

    long spentLastHour() {
        long cut = System.currentTimeMillis() - 3600_000L;
        while (!spends.isEmpty() && spends.peekFirst()[0] < cut) spends.pollFirst();
        long s = 0;
        for (long[] x : spends) s += x[1];
        return s;
    }

    public long balance() {
        try {
            if (mc.world == null) return -1;
            Scoreboard sb = mc.world.getScoreboard();
            ScoreboardObjective o = sb.getObjectiveForSlot(ScoreboardDisplaySlot.SIDEBAR);
            if (o == null) return -1;
            for (ScoreboardEntry e : sb.getScoreboardEntries(o)) {
                Team t = sb.getScoreHolderTeam(e.owner());
                String s = Team.decorateName(t, e.name()).getString();
                Matcher m = BAL.matcher(s);
                if (m.find()) return Market.parseMoney(m.group(1));
            }
        } catch (Exception ignored) {}
        return -1;
    }

    View view() {
        View v = new View();
        if (mc.currentScreen == null) return v;
        v.kind = OTHER;
        if (mc.currentScreen instanceof GenericContainerScreen g) {
            String t = g.getTitle().getString();
            GenericContainerScreenHandler h = g.getScreenHandler();
            Matcher m = TITLE.matcher(t);
            if (m.find() && h.getRows() == 6) {
                v.kind = LIST; v.h = h;
                v.page = Integer.parseInt(m.group(1));
                v.total = Integer.parseInt(m.group(2));
            } else if (t.trim().equals("Rynek") && h.getRows() == 3) {
                v.kind = CONFIRM; v.h = h;
            }
        }
        return v;
    }

    int hash(GenericContainerScreenHandler h) {
        int x = 17;
        int n = h.getRows() * 9;
        for (int i = 0; i < n; i++) x = x * 31 + ItemStack.hashCode(h.getSlot(i).getStack());
        return x;
    }

    void click(GenericContainerScreenHandler h, int slot, int button) {
        mc.getNetworkHandler().sendPacket(new ClickSlotC2SPacket(h.syncId, h.getRevision(), slot, button,
                SlotActionType.PICKUP, ItemStack.EMPTY, new Int2ObjectOpenHashMap<>()));
    }

    // ---------- glowna petla ----------
    void step() {
        if (!running && !scanning) return;
        ClientPlayerEntity p = mc.player;
        if (p == null || mc.getNetworkHandler() == null) { stopAll("brak polaczenia"); return; }
        if (mc.currentScreen instanceof MainScreen) return;
        long now = System.currentTimeMillis();
        View v = view();
        switch (state) {
            case OPEN -> open(v, now);
            case SORT -> sort(v, now);
            case LOOP -> loop(v, now);
            case WAIT_CONFIRM -> confirm(v, now, p);
            case WAIT_RESULT -> { if (now - stateSince > 2000) { dbg("Brak wiadomosci o zakupie (ktos byl szybszy?)"); pending = null; toOpen(now); } }
            case SELL -> sell(now, p);
            case SCAN_PAGES -> scanPages(v, now);
        }
    }

    void open(View v, long now) {
        if (v.kind == LIST) { state = S.SORT; sortTries = 0; stateSince = now; return; }
        if (now - lastCmd >= Config.get().openRetryMs) {
            lastCmd = now;
            mc.getNetworkHandler().sendChatCommand("ah");
        }
    }

    void sort(View v, long now) {
        if (v.kind != LIST) { toOpen(now); return; }
        Config cfg = Config.get();
        String want = scanning ? "Najni\u017cszej ceny" : "Najnowsze";
        String sel = Market.selectedOption(v.h.getSlot(cfg.sortSlot).getStack());
        if (sel.contains(want)) {
            state = scanning ? S.SCAN_PAGES : S.LOOP;
            lastHash = 0; lastPage = -1; lastAdvance = now; lastClick = 0;
            return;
        }
        if (now - lastClick >= 250) {
            if (++sortTries > 10) { stopAll("nie moge ustawic sortowania: " + want + " (zaznaczone: '" + sel + "')"); return; }
            click(v.h, cfg.sortSlot, 1);
            lastClick = now;
        }
    }

    void loop(View v, long now) {
        if (v.kind != LIST) { toOpen(now); return; }
        Config cfg = Config.get();
        int hs = hash(v.h);
        if (hs != lastHash) {
            lastHash = hs;
            if (tryBuy(v, now)) return;
        }
        if (now - lastClick >= nextDelay) {
            click(v.h, cfg.refreshSlot, cfg.refreshButton);
            lastClick = now;
            nextDelay = Math.max(cfg.clickDelayMs, learnedDelay) + rnd.nextInt(cfg.jitterMs + 1);
        }
    }

    boolean tryBuy(View v, long now) {
        if (now < cooldownUntil) return false;
        Config cfg = Config.get();
        String me = mc.player.getName().getString();
        long bal = balance();
        int bestSlot = -1; long bestMargin = Long.MIN_VALUE, bestPrice = 0; int bestCount = 1; Config.Item bestItem = null;
        for (int r = 0; r < 4; r++) {
            for (int c = 2; c < 9; c++) {
                int idx = r * 9 + c;
                ItemStack st = v.h.getSlot(idx).getStack();
                if (st.isEmpty()) continue;
                long price = Market.offerPrice(st);
                if (price <= 0) continue;
                if (Market.seller(st).equalsIgnoreCase(me)) continue;
                Market.Info in = Market.info(st);
                if (in.hasBad(cfg.badEnchants)) continue;
                for (Config.Item e : cfg.items) {
                    if (!e.enabled || e.maxPrice <= 0 || !Market.matches(e, in)) continue;
                    long lim = e.maxPrice * st.getCount();
                    if (price > lim) break;
                    if (bal >= 0 && bal - price < cfg.reserve) break;
                    if (cfg.maxSpendPerHour > 0 && spentLastHour() + price > cfg.maxSpendPerHour) break;
                    long margin = lim - price;
                    if (margin > bestMargin) {
                        bestMargin = margin; bestSlot = idx; bestPrice = price; bestCount = st.getCount(); bestItem = e;
                    }
                    break;
                }
            }
        }
        if (bestSlot < 0) return false;
        dbg("Klikam oferte: " + Market.label(bestItem) + " za " + Market.money(bestPrice) + " (limit " + Market.money(bestItem.maxPrice * bestCount) + ")");
        click(v.h, bestSlot, 0);
        pending = new Pending();
        pending.e = bestItem; pending.price = bestPrice; pending.count = bestCount;
        state = S.WAIT_CONFIRM;
        stateSince = now;
        return true;
    }

    void confirm(View v, long now, ClientPlayerEntity p) {
        Config cfg = Config.get();
        if (v.kind == CONFIRM && pending != null) {
            for (int i = 0; i < 27; i++) {
                ItemStack st = v.h.getSlot(i).getStack();
                if (st.isOf(Items.LIME_DYE)) {
                    long pr = Market.offerPrice(st);
                    if (pr > 0 && pr <= pending.e.maxPrice * pending.count) {
                        dbg("Potwierdzam zakup za " + Market.money(pr));
                        click(v.h, i, 0);
                        state = S.WAIT_RESULT;
                        stateSince = now;
                    } else {
                        dbg("Anuluje: cena w oknie " + Market.money(pr) + " > limit");
                        p.closeHandledScreen();
                        pending = null;
                        toOpen(now);
                    }
                    return;
                }
            }
        }
        if (now - stateSince > cfg.confirmTimeoutMs) {
            dbg("Brak okna potwierdzenia (kind=" + v.kind + ", ekran: " + (mc.currentScreen == null ? "brak" : mc.currentScreen.getTitle().getString()) + ")");
            if (v.kind == CONFIRM) p.closeHandledScreen();
            pending = null;
            if (v.kind == LIST) { state = S.LOOP; stateSince = now; } else toOpen(now);
        }
    }

    // ---------- auto sell ----------
    void sell(long now, ClientPlayerEntity p) {
        Config cfg = Config.get();
        switch (sellStage) {
            case 0 -> {
                if (mc.currentScreen != null) p.closeHandledScreen();
                sellStage = 1; stateSince = now;
            }
            case 1 -> {
                if (now - stateSince < 350) return;
                int found = -1;
                for (int i = 0; i < 36; i++) {
                    ItemStack st = p.getInventory().getStack(i);
                    if (!st.isEmpty() && Market.matches(sellPending.e, Market.info(st))) { found = i; break; }
                }
                if (found < 0) {
                    msg("Nie znalazlem kupionego przedmiotu w ekwipunku - pomijam auto-sprzedaz.");
                    sellPending = null; toOpen(now); return;
                }
                if (found >= 9) {
                    mc.interactionManager.clickSlot(p.playerScreenHandler.syncId, found, 8, SlotActionType.SWAP, p);
                    found = 8;
                }
                p.getInventory().selectedSlot = found;
                sellIdx = found; sellStage = 2; stateSince = now;
            }
            case 2 -> {
                if (now - stateSince < 250) return;
                ItemStack hs = p.getInventory().getStack(sellIdx);
                int n = Math.min(hs.getCount(), sellPending.count);
                if (n <= 0) { sellPending = null; toOpen(now); return; }
                long unit = sellOverride > 0 ? sellOverride : sellUnit(sellPending.e);
                dbg("Wystawiam " + n + " szt. po " + Market.money(unit) + (cfg.sellPerUnit ? " (za szt.)" : " za szt. (razem " + Market.money(unit * n) + ")"));
                long price = cfg.sellPerUnit ? unit : unit * n;
                mc.getNetworkHandler().sendChatCommand("wystaw " + n + " " + price);
                sellStage = 3; stateSince = now;
            }
            default -> {
                if (now - stateSince < 700) return;
                sellOverride = 0;
                sellPending = null;
                toOpen(now);
            }
        }
    }

    // ---------- skan ----------
    void scanPages(View v, long now) {
        Config cfg = Config.get();
        if (v.kind != LIST) {
            if (pagesDone > 0 && now - lastAdvance > 4000) finishScan("utracono rynek");
            else toOpenIfIdle(now);
            return;
        }
        int hs = hash(v.h);
        if (hs != lastHash) { lastHash = hs; hashSince = now; }
        scanTotal = v.total;
        if (v.page != lastPage) {
            if (now - hashSince < cfg.scanSettleMs) return;
            if (!targetMode || v.page >= skipTo) { scanItems(v.h, v.page); pagesDone++; }
            lastPage = v.page;
            lastAdvance = now;
            lastClick = 0;
            return;
        }
        if (v.page >= v.total || pagesDone >= scanMax) { finishScan("ok"); return; }
        if (now - lastAdvance > 3000) { finishScan("brak nastepnej strony"); return; }
        if (now - lastClick >= cfg.scanDelayMs) {
            click(v.h, cfg.nextSlot, 0);
            lastClick = now;
        }
    }

    void toOpenIfIdle(long now) {
        if (now - lastCmd >= Config.get().openRetryMs) { lastCmd = now; mc.getNetworkHandler().sendChatCommand("ah"); }
    }

    void scanItems(GenericContainerScreenHandler h, int page) {
        for (int r = 0; r < 4; r++) {
            for (int c = 2; c < 9; c++) {
                ItemStack st = h.getSlot(r * 9 + c).getStack();
                if (st.isEmpty()) continue;
                long price = Market.offerPrice(st);
                if (price <= 0) continue;
                offersSeen++;
                long unit = Math.max(1, price / Math.max(1, st.getCount()));
                Market.Info in = Market.info(st);
                if (in.hasBad(Config.get().badEnchants)) { badSkipped++; continue; }
                if (targetMode) {
                    if (Market.matches(targetItem, in) && !Market.seller(st).equalsIgnoreCase(mc.player.getName().getString())
                            && (targetLowest < 0 || unit < targetLowest)) targetLowest = unit;
                    continue;
                }
                String key = in.key();
                Market.ScanEntry se = accE.get(key);
                if (se == null) { se = in.toScanEntry(); se.page = page; accE.put(key, se); accP.put(key, new ArrayList<>()); }
                se.count++;
                List<Long> pl = accP.get(key);
                if (pl.size() < 5) pl.add(unit);
                for (Config.Item e : Config.get().items) {
                    if (Market.matches(e, in)) {
                        cfgPage.putIfAbsent(e, page);
                        List<Long> l = cfgP.computeIfAbsent(e, k -> new ArrayList<>());
                        if (l.size() < 5) l.add(unit);
                    }
                }
            }
        }
    }

    static long median(List<Long> l) {
        List<Long> s = new ArrayList<>(l);
        Collections.sort(s);
        return s.size() >= 2 ? s.get(1) : s.get(0);
    }

    void finishScan(String reason) {
        if (targetMode) { finishTarget(reason); return; }
        Config cfg = Config.get();
        List<Market.ScanEntry> out = new ArrayList<>();
        for (Map.Entry<String, Market.ScanEntry> en : accE.entrySet()) {
            Market.ScanEntry se = en.getValue();
            se.ref = median(accP.get(en.getKey()));
            out.add(se);
        }
        out.sort((a, b) -> Integer.compare(b.count, a.count));
        if (pagesDone >= 20 || scanResults.isEmpty()) scanResults = out;
        lastScanTime = System.currentTimeMillis();
        int updated = 0;
        for (Map.Entry<Config.Item, List<Long>> en : cfgP.entrySet()) {
            Config.Item e = en.getKey();
            e.refPrice = median(en.getValue());
            Integer pg = cfgPage.get(e);
            if (pg != null) e.refPage = pg;
            if (e.autoPrice) e.maxPrice = cfg.buyPrice(e.refPrice);
            updated++;
        }
        Config.save();
        saveScan();
        scanning = false;
        msg("Skan (" + reason + "): " + pagesDone + " stron, " + offersSeen + " ofert (pominieto " + badSkipped + " ze zlymi enchantami) = " + accE.size() + " roznych przedmiotow z cena zapisana w zakladce Skan. Z configu zaktualizowano: " + updated + ".");
        Webhook.send("Skan", 0xFEE75C, "Strony", String.valueOf(pagesDone), "Przedmioty", String.valueOf(accE.size()));
        if (resumeBuy) { resumeBuy = false; running = true; toOpen(System.currentTimeMillis()); }
    }

    /** Po zakupie: sprawdza aktualna najnizsza cene tego przedmiotu i dopiero wtedy wystawia. */
    void startTarget(Pending p) {
        Config cfg = Config.get();
        sellPending = p;
        targetItem = p.e;
        targetMode = true;
        targetLowest = -1;
        resumeBuy = running;
        running = false;
        scanning = true;
        pagesDone = 0; lastPage = -1; scanTotal = 0;
        scanMax = Math.max(1, cfg.targetPages);
        skipTo = Math.max(1, p.e.refPage - 1);
        lastCmd = 0;
        toOpen(System.currentTimeMillis());
        dbg("Sprawdzam aktualna cene: " + Market.label(p.e) + " (od strony " + skipTo + ")");
    }

    void finishTarget(String reason) {
        Config cfg = Config.get();
        long now = System.currentTimeMillis();
        scanning = false;
        targetMode = false;
        Pending sp = sellPending;
        long paidUnit = sp.price / Math.max(1, sp.count);
        long minUnit = (long) Math.ceil(paidUnit / 0.97 * 1.03); // nie schodzimy ponizej ~3% zysku netto
        long unit;
        if (targetLowest > 0) {
            unit = (long) Math.floor(targetLowest * (1 - cfg.undercutPercent / 100.0));
            dbg("Najtansza oferta teraz: " + Market.money(targetLowest) + " -> wystawiam za " + Market.money(unit));
        } else {
            unit = sellUnit(sp.e);
            dbg("Nie znalazlem aktualnej ceny (" + reason + ") - uzywam ceny ze skanu: " + Market.money(unit));
        }
        if (unit < minUnit) {
            msg("\u00a7eRynek jest za tani (" + Market.money(unit) + "/szt., kupione po " + Market.money(paidUnit) + "). Wystawiam min. " + Market.money(minUnit) + " zeby nie stracic.");
            unit = minUnit;
        }
        sellOverride = unit;
        running = resumeBuy;
        resumeBuy = false;
        state = S.SELL;
        sellStage = 0;
        stateSince = now;
    }

    void saveScan() {
        try {
            Files.writeString(Config.path().resolveSibling("autorynek_scan.json"),
                    new GsonBuilder().create().toJson(scanResults));
        } catch (Exception ignored) {}
    }

    void loadScan() {
        try {
            var p = Config.path().resolveSibling("autorynek_scan.json");
            if (Files.exists(p)) {
                Market.ScanEntry[] arr = new GsonBuilder().create().fromJson(Files.readString(p), Market.ScanEntry[].class);
                scanResults = new ArrayList<>(Arrays.asList(arr));
            }
        } catch (Exception ignored) {}
    }

    public String statusText() {
        if (scanning) return "SKAN " + pagesDone + "/" + Math.min(scanMax, Math.max(scanTotal, 1));
        if (!running) return "ZATRZYMANY";
        return "DZIALA (" + state + ")";
    }
}
