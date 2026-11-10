package pl.autorynek;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;

public final class Webhook {
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    /** fields: kolejno nazwa, wartość, nazwa, wartość... */
    public static void send(String title, int color, String... fields) {
        String url = Config.get().webhook;
        if (url == null || !url.startsWith("http")) return;
        try {
            JsonObject embed = new JsonObject();
            embed.addProperty("title", title);
            embed.addProperty("color", color);
            embed.addProperty("timestamp", Instant.now().toString());
            JsonArray arr = new JsonArray();
            for (int i = 0; i + 1 < fields.length; i += 2) {
                JsonObject f = new JsonObject();
                f.addProperty("name", fields[i]);
                f.addProperty("value", fields[i + 1].isEmpty() ? "-" : fields[i + 1]);
                f.addProperty("inline", true);
                arr.add(f);
            }
            embed.add("fields", arr);
            JsonObject body = new JsonObject();
            JsonArray embeds = new JsonArray();
            embeds.add(embed);
            body.add("embeds", embeds);
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                    .build();
            HTTP.sendAsync(req, HttpResponse.BodyHandlers.discarding());
        } catch (Exception ignored) {}
    }
}
