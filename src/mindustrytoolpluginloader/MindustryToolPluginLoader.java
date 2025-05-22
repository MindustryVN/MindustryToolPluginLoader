package mindustrytoolpluginloader;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.util.UUID;

import org.pf4j.DefaultPluginManager;
import org.pf4j.PluginManager;

import arc.util.CommandHandler;
import arc.util.Log;
import arc.util.Timer;
import mindustry.mod.Plugin;

public class MindustryToolPluginLoader extends Plugin {

    public static final PluginManager pluginManager = new DefaultPluginManager();
    public static final PluginUpdater updater = new PluginUpdater(pluginManager);
    public static final UUID SERVER_ID = UUID.fromString(System.getenv("SERVER_ID"));
    private final HttpClient httpClient = HttpClient.newBuilder()//
            .connectTimeout(Duration.ofSeconds(2))//
            .build();

    public final PrintStream standardOutputStream = System.out;

    public MindustryToolPluginLoader() {
        initOutputStream();
    }

    @Override
    public void init() {

        updater.init();

        Timer.schedule(() -> {
            try {
                updater.checkAndUpdate();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }, 0, 60 * 5);

        System.out.println("MindustryToolPluginLoader initialized");
    }

    @Override
    public void registerServerCommands(CommandHandler handler) {
        updater.registerServerCommands(handler);
    }

    @Override
    public void registerClientCommands(CommandHandler handler) {
        updater.registerClientCommands(handler);
    }

    private void initOutputStream() {
        System.out.println("Setup logger");
        var custom = new OutputStream() {
            @Override
            public void write(int b) throws IOException {
            }

            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                try {
                    String message = new String(b, off, len);
                    standardOutputStream.println(message);
                    sendToConsole(message);
                } catch (Exception e) {
                    standardOutputStream.println(e.getMessage());
                }
            }

            @Override
            public void flush() throws IOException {
                standardOutputStream.flush();
            }
        };

        System.setOut(new PrintStream(custom));
        System.out.println("Setup logger done");
    }

    public void sendConsoleMessage(String chat) {
        var request = HttpRequest.newBuilder(URI.create("http://server-manager:8088/internal-api/v1/console"))//
                .header("Content-Type", "application/json")//
                .header("X-SERVER-ID", SERVER_ID.toString())
                .POST(HttpRequest.BodyPublishers.ofString(chat))//
                .build();

        httpClient.sendAsync(request, BodyHandlers.ofString())
                .whenComplete((_result, error) -> {
                    if (error != null) {
                        Log.err("Can not send console message: " + error.getMessage());
                    }
                });
    }

    private void sendToConsole(String message) {
        try {
            sendConsoleMessage(message);
        } catch (Exception e) {
            standardOutputStream.println(message);
            standardOutputStream.println(e.getMessage());
        }
    }

}
