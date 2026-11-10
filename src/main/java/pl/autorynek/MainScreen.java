package pl.autorynek;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.function.Consumer;

public class MainScreen extends Screen {
    enum Tab {
        STATUS("Status"), BUYS("Zakupy"), CONFIG("Config"), SCAN("Skan"), SETTINGS("Ustawienia");
        final String n;
        Tab(String n) { this.n = n; }
    }

    static Tab tab = Tab.STATUS;
    static int page = 0;
    static final int W = 440, H = 270;
    int left, top;

    record Lbl(int x, int y, String t) {}
    final List<Lbl> labels = new ArrayList<>();

    public MainScreen() { super(Text.literal("AutoRynek")); }

    @Override public boolean shouldPause() { return false; }

    @Override public void removed() { Config.save(); }

    void btn(String t, int x, int y, int w, Runnable r) {
        addDrawableChild(ButtonWidget.builder(Text.literal(t), b -> r.run()).dimensions(x, y, w, 20).build());
    }

    TextFieldWidget field(int x, int y, int w, String init, int max, Consumer<String> c) {
        TextFieldWidget f = new TextFieldWidget(textRenderer, x, y, w, 18, Text.empty());
        f.setMaxLength(max);
        f.setText(init);
        f.setChangedListener(c);
        addDrawableChild(f);
        return f;
    }

    @Override
    protected void init() {
        labels.clear();
        left = (width - W) / 2;
        top = Math.max(28, (height - H) / 2);
        Config cfg = Config.get();
        Tab[] ts = Tab.values();
        int bw = W / ts.length;
        for (int i = 0; i < ts.length; i++) {
            Tab t = ts[i];
            ButtonWidget b = ButtonWidget.builder(Text.literal(t.n), x -> { tab = t; page = 0; clearAndInit(); })
                    .dimensions(left + i * bw, top - 24, bw - 2, 20).build();
            b.active = t != tab;
            addDrawableChild(b);
        }

        switch (tab) {
            case STATUS -> {
                Bot bot = Bot.I;
                btn(bot.running ? "STOP" : "START", left + 8, top + 8, 100, () -> { bot.toggle(); clearAndInit(); });
                btn("SKANUJ", left + 114, top + 8, 100, () -> { if (bot.scanning) bot.stopAll("skan przerwany"); else bot.startScan(false); clearAndInit(); });
                btn("Auto Sell: " + (cfg.autoSell ? "ON" : "OFF"), left + 220, top + 8, 110, () -> { cfg.autoSell = !cfg.autoSell; clearAndInit(); });
            }
            case CONFIG -> {
                btn("Przelicz ceny", left + W - 138, top + 4, 130, () -> {
                    int n = 0;
                    for (Config.Item it : cfg.items) if (it.refPrice > 0) { it.maxPrice = cfg.buyPrice(it.refPrice); n++; }
                    Config.save();
                    Bot.I.msg("Przeliczono ceny kupna: " + n + " (min. zysk " + cfg.minProfitPercent + "%)");
                    clearAndInit();
                });
                int per = 7;
                List<Config.Item> L = cfg.items;
                int pages = Math.max(1, (L.size() + per - 1) / per);
                page = Math.min(page, pages - 1);
                for (int r = 0; r < per; r++) {
                    int idx = page * per + r;
                    if (idx >= L.size()) break;
                    Config.Item it = L.get(idx);
                    int y = top + 40 + r * 26;
                    field(left + 150, y, 70, String.valueOf(it.maxPrice), 14, s -> { long v = Market.parseMoney(s); if (v >= 0) it.maxPrice = v; });
                    field(left + 224, y, 70, String.valueOf(it.sellPrice), 14, s -> { long v = Market.parseMoney(s); if (v >= 0) it.sellPrice = v; });
                    btn(it.enabled ? "ON" : "OFF", left + 298, y - 1, 36, () -> { it.enabled = !it.enabled; clearAndInit(); });
                    btn(it.autoPrice ? "A+" : "A-", left + 338, y - 1, 30, () -> { it.autoPrice = !it.autoPrice; clearAndInit(); });
                    btn("X", left + 372, y - 1, 22, () -> { cfg.items.remove(it); clearAndInit(); });
                }
                pager(pages);
            }
            case SCAN -> {
                btn("Dodaj wszystkie", left + W - 138, top + 4, 130, () -> {
                    int n = 0;
                    for (Market.ScanEntry se : Bot.I.scanResults) {
                        Config.Item it = se.toItem();
                        boolean has = false;
                        for (Config.Item e : cfg.items) if (Market.sameItem(e, it)) { has = true; break; }
                        if (has) continue;
                        it.maxPrice = cfg.buyPrice(se.ref);
                        it.autoPrice = true;
                        cfg.items.add(it);
                        n++;
                    }
                    Config.save();
                    Bot.I.msg("Dodano do configu: " + n);
                    clearAndInit();
                });
                int per = 7;
                List<Market.ScanEntry> L = Bot.I.scanResults;
                int pages = Math.max(1, (L.size() + per - 1) / per);
                page = Math.min(page, pages - 1);
                for (int r = 0; r < per; r++) {
                    int idx = page * per + r;
                    if (idx >= L.size()) break;
                    Market.ScanEntry se = L.get(idx);
                    int y = top + 40 + r * 26;
                    boolean has = false;
                    for (Config.Item e : cfg.items) if (Market.sameItem(e, se.toItem())) has = true;
                    ButtonWidget b = ButtonWidget.builder(Text.literal(has ? "ok" : "+"), x -> {
                        Config.Item it = se.toItem();
                        it.maxPrice = cfg.buyPrice(se.ref);
                        cfg.items.add(it);
                        Config.save();
                        clearAndInit();
                    }).dimensions(left + 370, y - 1, 30, 20).build();
                    b.active = !has;
                    addDrawableChild(b);
                }
                pager(pages);
            }
            case SETTINGS -> {
                setting(0, "Opoznienie klik. (ms)", String.valueOf(cfg.clickDelayMs), s -> cfg.clickDelayMs = Math.max(30, (int) Math.max(0, Market.parseMoney(s))));
                setting(1, "Jitter (ms)", String.valueOf(cfg.jitterMs), s -> cfg.jitterMs = (int) Math.max(0, Market.parseMoney(s)));
                setting(2, "Min. zysk % (netto)", String.valueOf(cfg.minProfitPercent), s -> { try { cfg.minProfitPercent = Double.parseDouble(s); } catch (Exception ignored) {} });
                setting(3, "Undercut %", String.valueOf(cfg.undercutPercent), s -> { try { cfg.undercutPercent = Double.parseDouble(s); } catch (Exception ignored) {} });
                setting(4, "Rezerwa konta $", String.valueOf(cfg.reserve), s -> cfg.reserve = Math.max(0, Market.parseMoney(s)));
                setting(5, "Limit wydatkow/h $", String.valueOf(cfg.maxSpendPerHour), s -> cfg.maxSpendPerHour = Math.max(0, Market.parseMoney(s)));
                setting(6, "Strony szybkiego skanu", String.valueOf(cfg.quickScanPages), s -> cfg.quickScanPages = (int) Math.max(1, Market.parseMoney(s)));
                setting(7, "Max stron skanu", String.valueOf(cfg.scanMaxPages), s -> cfg.scanMaxPages = (int) Math.max(1, Market.parseMoney(s)));
                labels.add(new Lbl(left + 8, top + 112, "Webhook Discord (URL):"));
                field(left + 8, top + 124, W - 16, cfg.webhook, 300, s -> cfg.webhook = s.trim());
                btn("Auto Sell: " + (cfg.autoSell ? "ON" : "OFF"), left + 8, top + 156, 110, () -> { cfg.autoSell = !cfg.autoSell; clearAndInit(); });
                btn("Cena /wystaw: " + (cfg.sellPerUnit ? "za szt." : "calosc"), left + 124, top + 156, 130, () -> { cfg.sellPerUnit = !cfg.sellPerUnit; clearAndInit(); });
                btn("Skan ceny przed wystawieniem: " + (cfg.checkPriceBeforeSell ? "ON" : "OFF"), left + 8, top + 182, 250, () -> { cfg.checkPriceBeforeSell = !cfg.checkPriceBeforeSell; clearAndInit(); });
                btn("Test webhooka", left + 260, top + 156, 110, () -> Webhook.send("Test AutoRynek", 0x5865F2, "Status", "dziala"));
            }
            default -> {}
        }
    }

    void setting(int i, String label, String init, Consumer<String> c) {
        int col = i / 4, row = i % 4;
        int x = left + 8 + col * 218;
        int y = top + 14 + row * 24;
        labels.add(new Lbl(x, y + 5, label));
        field(x + 130, y, 70, init, 12, c);
    }

    void pager(int pages) {
        btn("<<", left + W / 2 - 100, top + H - 26, 34, () -> { page = Math.max(0, page - 10); clearAndInit(); });
        btn(">>", left + W / 2 + 66, top + H - 26, 34, () -> { page = Math.min(pages - 1, page + 10); clearAndInit(); });
        btn("<", left + W / 2 - 60, top + H - 26, 30, () -> { page = Math.max(0, page - 1); clearAndInit(); });
        btn(">", left + W / 2 + 30, top + H - 26, 30, () -> { page = Math.min(pages - 1, page + 1); clearAndInit(); });
        labels.add(new Lbl(left + W / 2 - 14, top + H - 21, (page + 1) + "/" + pages));
    }

    @Override
    public void renderBackground(DrawContext ctx, int mx, int my, float d) {
        ctx.fill(0, 0, width, height, 0xB0000000);
        ctx.fill(left, top, left + W, top + H, 0xF0161620);
    }

    void txt(DrawContext c, String s, int x, int y, int col) {
        c.drawTextWithShadow(textRenderer, s, x, y, col);
    }

    @Override
    public void render(DrawContext ctx, int mx, int my, float delta) {
        renderBackground(ctx, mx, my, delta);
        super.render(ctx, mx, my, delta);
        Config cfg = Config.get();
        Bot bot = Bot.I;
        for (Lbl l : labels) txt(ctx, l.t(), l.x(), l.y(), 0xFFCCCCCC);
        int x = left + 8, y = top + 38;
        switch (tab) {
            case STATUS -> {
                long bal = bot.balance();
                long up = bot.startedAt == 0 ? 0 : (System.currentTimeMillis() - bot.startedAt) / 1000;
                double perMin = up > 0 ? bot.buys * 60.0 / up : 0;
                txt(ctx, "Status: " + bot.statusText(), x, y, bot.running ? 0xFF55FF55 : 0xFFFFAA00);
                txt(ctx, String.format("Czas od pierwszego startu: %02d:%02d:%02d", up / 3600, up / 60 % 60, up % 60), x, y + 14, 0xFFFFFFFF);
                txt(ctx, "Zakupy: " + bot.buys + "   Wydane: " + Market.money(bot.spent), x, y + 28, 0xFFFFFFFF);
                txt(ctx, "Sprzedaze: " + bot.sells + "   Przychod: " + Market.money(bot.income), x, y + 42, 0xFFFFFFFF);
                txt(ctx, String.format("Tempo: %.2f zakupow/min", perMin), x, y + 56, 0xFFFFFFFF);
                txt(ctx, "Saldo: " + (bal >= 0 ? Market.money(bal) : "? (nie odczytano)"), x, y + 70, 0xFFFFFFFF);
                txt(ctx, "Przedmiotow w configu: " + cfg.items.size() + "   Wpisow skanu: " + bot.scanResults.size(), x, y + 84, 0xFFFFFFFF);
                if (!bot.history.isEmpty()) {
                    Bot.Purchase p = bot.history.get(0);
                    txt(ctx, "Ostatni zakup: " + p.label + " za " + Market.money(p.price) + " (zysk ~" + Market.money(p.profit) + ")", x, y + 104, 0xFF88CCFF);
                }
                txt(ctx, "Klawisze: START/STOP i PANIC w Sterowaniu. Ctrl+A w rynku dodaje przedmiot.", x, y + 130, 0xFF888888);
            }
            case BUYS -> {
                txt(ctx, "Godz.     Przedmiot                      Cena          Sprzedaz~     Zysk~", x, top + 8, 0xFFAAAAAA);
                SimpleDateFormat f = new SimpleDateFormat("HH:mm:ss");
                for (int i = 0; i < 14 && i < bot.history.size(); i++) {
                    Bot.Purchase p = bot.history.get(i);
                    int yy = top + 24 + i * 15;
                    txt(ctx, f.format(new Date(p.time)), x, yy, 0xFFFFFFFF);
                    txt(ctx, textRenderer.trimToWidth(p.label, 130), x + 56, yy, 0xFFFFFFFF);
                    txt(ctx, Market.money(p.price), x + 196, yy, 0xFFFFFFFF);
                    txt(ctx, Market.money(p.sellEst), x + 264, yy, 0xFFFFFFFF);
                    txt(ctx, Market.money(p.profit), x + 340, yy, p.profit >= 0 ? 0xFF55FF55 : 0xFFFF5555);
                }
            }
            case CONFIG -> {
                txt(ctx, "Przedmiot", x, top + 26, 0xFFAAAAAA);
                txt(ctx, "Kupuj do (szt.)", left + 150, top + 26, 0xFFAAAAAA);
                txt(ctx, "Sprzedaj za", left + 224, top + 26, 0xFFAAAAAA);
                txt(ctx, "A+ = cena z marzy po skanie", left + 298, top + 26, 0xFF777777);
                int per = 7;
                for (int r = 0; r < per; r++) {
                    int idx = page * per + r;
                    if (idx >= cfg.items.size()) break;
                    Config.Item it = cfg.items.get(idx);
                    String s = Market.label(it) + (it.refPrice > 0 ? " (ref " + Market.money(it.refPrice) + ")" : "");
                    txt(ctx, textRenderer.trimToWidth(s, 138), x, top + 45 + r * 26, 0xFFFFFFFF);
                }
                if (cfg.items.isEmpty()) txt(ctx, "Pusto. Najedz na przedmiot w rynku i nacisnij Ctrl+A, albo dodaj ze skanu.", x, top + 60, 0xFFFFAA00);
            }
            case SCAN -> {
                txt(ctx, "Ostatni skan: " + (bot.lastScanTime == 0 ? "brak" : new SimpleDateFormat("HH:mm:ss").format(new Date(bot.lastScanTime))) + "   (" + Bot.I.scanResults.size() + " typow, wg liczby ofert)", x, top + 26, 0xFFAAAAAA);
                int per = 7;
                for (int r = 0; r < per; r++) {
                    int idx = page * per + r;
                    if (idx >= bot.scanResults.size()) break;
                    Market.ScanEntry se = bot.scanResults.get(idx);
                    int yy = top + 45 + r * 26;
                    txt(ctx, textRenderer.trimToWidth(se.label, 210), x, yy, 0xFFFFFFFF);
                    txt(ctx, "x" + se.count, left + 226, yy, 0xFFAAAAAA);
                    txt(ctx, Market.money(se.ref), left + 270, yy, 0xFF88CCFF);
                }
            }
            default -> {}
        }
    }
}
