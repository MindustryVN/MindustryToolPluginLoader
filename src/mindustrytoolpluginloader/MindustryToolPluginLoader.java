package mindustrytoolpluginloader;

import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.pf4j.DefaultPluginManager;
import org.pf4j.PluginManager;
import org.pf4j.PluginStateEvent;
import org.pf4j.PluginStateListener;

import arc.util.CommandHandler;
import mindustry.mod.Plugin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import arc.Events;
import arc.files.Fi;
import arc.util.Log;
import lombok.Data;
import mindustry.game.EventType;
import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.util.List;
import java.util.Objects;

public class MindustryToolPluginLoader extends Plugin {

    public final PluginManager pluginManager;
    public static final UUID SERVER_ID = UUID.fromString(System.getenv("SERVER_ID"));

    public static final ScheduledExecutorService BACKGROUND_SCHEDULER = Executors
            .newSingleThreadScheduledExecutor();

    @Data
    public static class PluginData {
        final String id;
        final String name;
        final String url;
    }

    private static final List<PluginData> PLUGINS = List.of(
            new PluginData("mindustry-tool", "ServerController.jar",
                    "https://api.github.com/repos/MindustryVN/ServerController/releases/latest"));

    private static final String PLUGIN_DIR = "config/plugins";
    private static final Path METADATA_PATH = Paths.get("config/plugin-meta.json");

    private static final ObjectMapper objectMapper = new ObjectMapper();

    private static CommandHandler clientCommandHandler;
    private static CommandHandler serverCommandHandler;

    public MindustryToolPluginLoader() {
        try {
            if (!Files.exists(Paths.get(PLUGIN_DIR))) {
                Files.createDirectories(Paths.get(PLUGIN_DIR));
            }

            new Fi(PLUGIN_DIR).emptyDirectory();

        } catch (IOException e) {
            e.printStackTrace();
        }

        pluginManager = new DefaultPluginManager();
    }

    @Override
    public void init() {
        pluginManager.addPluginStateListener(new PluginStateListener() {

            @Override
            public void pluginStateChanged(PluginStateEvent event) {
                Log.info(
                        event.getPlugin().getPluginId() + ": " + event.getOldState() + " -> " + event.getPluginState());
            }
        });

        for (var clazz : EventType.class.getDeclaredClasses()) {
            try {
                Events.on(clazz, this::onEvent);
            } catch (Exception e) {
                Log.err("Failed to register event: " + clazz.getName(), e);
            }
        }

        for (var trigger : EventType.Trigger.values()) {
            try {
                Events.run(trigger, () -> onEvent(trigger));
            } catch (Exception e) {
                Log.err("Failed to register trigger: " + trigger.name(), e);
            }
        }

        BACKGROUND_SCHEDULER.scheduleWithFixedDelay(() -> {
            try {
                checkAndUpdate();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }, 0, 5, TimeUnit.MINUTES);

        System.out.println("MindustryToolPluginLoader initialized");
    }

    @Override
    public void registerServerCommands(CommandHandler handler) {
        serverCommandHandler = handler;

        pluginManager.getPlugins()
                .stream()
                .map(wrapper -> wrapper.getPlugin())
                .filter(plugin -> plugin instanceof MindustryToolPlugin)
                .map(plugin -> (MindustryToolPlugin) plugin)
                .forEach(plugin -> plugin.registerServerCommands(handler));
    }

    @Override
    public void registerClientCommands(CommandHandler handler) {
        clientCommandHandler = handler;

        pluginManager.getPlugins()
                .stream()
                .map(wrapper -> wrapper.getPlugin())
                .filter(plugin -> plugin instanceof MindustryToolPlugin)
                .map(plugin -> (MindustryToolPlugin) plugin)
                .forEach(plugin -> plugin.registerClientCommands(handler));
    }

    public void onEvent(Object event) {
        try {
            pluginManager.getPlugins()
                    .stream()
                    .map(wrapper -> wrapper.getPlugin())
                    .filter(plugin -> plugin instanceof MindustryToolPlugin)
                    .map(plugin -> (MindustryToolPlugin) plugin)
                    .forEach(plugin -> plugin.onEvent(event));

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void checkAndUpdate() throws Exception {
        for (var plugin : PLUGINS) {
            checkAndUpdate(plugin);
        }

        Log.info("Loaded plugins: " + pluginManager.getPlugins().stream().map(plugin -> plugin.getPluginId()).toList());
    }

    public synchronized void checkAndUpdate(PluginData plugin) throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://api.mindustry-tool.com/api/v3/plugins/version?path="
                        + plugin.url))
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        String updatedAt = response.body();

        String lastUpdated = null;
        ObjectNode meta = null;

        if (Files.exists(METADATA_PATH)) {
            try {
                meta = (ObjectNode) objectMapper.readTree(Files.readString(METADATA_PATH));

                if (meta.has(plugin.name) && meta.path(plugin.name).has("updated_at")) {
                    lastUpdated = meta.path(plugin.name).path("updated_at").asText(null);
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        var path = Paths.get(PLUGIN_DIR, plugin.name);

        if (updatedAt != null && Objects.equals(updatedAt, lastUpdated) && Files.exists(path)) {
            return;
        }

        var loaded = pluginManager.getPlugin(plugin.getId());

        if (loaded != null) {
            loaded.getPlugin().delete();

            Log.info("Unloaded plugin: " + plugin.name);
        }

        if (Files.exists(path)) {
            Files.delete(path);
        }

        // Download new plugin
        Log.info("Downloading updated plugin: " + plugin.name);
        HttpRequest downloadRequest = HttpRequest.newBuilder()
                .uri(URI.create("https://api.mindustry-tool.com/api/v3/plugins/download?path=" + plugin.url))
                .build();

        HttpResponse<Path> downloadResponse = client.send(downloadRequest, HttpResponse.BodyHandlers.ofFile(path));

        if (downloadResponse.statusCode() >= 300) {
            Log.info("Failed to download plugin: " + plugin.url + " " + downloadResponse.statusCode());

            if (Files.exists(path)) {
                Files.delete(path);
            }

            return;
        }

        Log.info("Downloaded to " + downloadResponse.body());

        if (meta == null) {
            meta = objectMapper.createObjectNode();
        }

        // Save updated metadata
        meta
                .putObject(plugin.getName())
                .put("updated_at", updatedAt).put("url", plugin.url);

        Files.writeString(METADATA_PATH, meta.toPrettyString());

        try {
            var pluginId = pluginManager.loadPlugin(path);
            var wrapper = pluginManager.getPlugin(pluginId);

            if (wrapper == null) {
                throw new RuntimeException("Plugin not found: " + pluginId);
            }

            var instance = wrapper.getPlugin();
            instance.start();

            if (instance instanceof MindustryToolPlugin mindustryToolPlugin) {
                Log.info("Init plugin: " + mindustryToolPlugin.getClass().getName());
                mindustryToolPlugin.init();

                if (clientCommandHandler != null) {
                    mindustryToolPlugin.registerClientCommands(clientCommandHandler);
                }

                if (serverCommandHandler != null) {
                    mindustryToolPlugin.registerServerCommands(serverCommandHandler);
                }
            } else {
                Log.info("Invalid plugin: " + instance.getClass().getName());
            }

            Log.info("Plugin updated and reloaded: " + plugin.name);

        } catch (Exception e) {
            e.printStackTrace();
            try {
                Files.delete(path);
            } catch (IOException e1) {
                e1.printStackTrace();
            }
            throw new RuntimeException("Failed to load plugin: " + plugin.name, e);
        }
    }
}
