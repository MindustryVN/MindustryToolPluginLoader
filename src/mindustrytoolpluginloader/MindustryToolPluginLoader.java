package mindustrytoolpluginloader;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.pf4j.DefaultPluginManager;
import org.pf4j.PluginManager;
import org.pf4j.PluginWrapper;

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
import java.lang.ref.WeakReference;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.util.List;
import java.util.Objects;

public class MindustryToolPluginLoader extends Plugin {

    public final UUID SERVER_ID = UUID.fromString(System.getenv("SERVER_ID"));

    private final PluginManager pluginManager;
    private final ConcurrentHashMap<String, WeakReference<MindustryToolPlugin>> plugins = new ConcurrentHashMap<>();

    public final ScheduledExecutorService BACKGROUND_SCHEDULER = Executors
            .newSingleThreadScheduledExecutor();

    @Data
    public class PluginData {
        final String id;
        final String name;
        final String url;
    }

    private final List<PluginData> PLUGINS = List.of(
            new PluginData("mindustry-tool", "ServerController.jar",
                    "https://api.github.com/repos/MindustryVN/ServerController/releases/latest"));

    private final String PLUGIN_DIR = "config/plugins";
    private final Path METADATA_PATH = Paths.get("config/plugin-meta.json");

    private final ObjectMapper objectMapper = new ObjectMapper();

    private CommandHandler clientCommandHandler;
    private CommandHandler serverCommandHandler;

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
        checkAndUpdate();

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

        BACKGROUND_SCHEDULER.scheduleWithFixedDelay(this::checkAndUpdate, 5, 5, TimeUnit.MINUTES);

        Log.info("Loaded plugins: " + pluginManager.getPlugins().stream().map(plugin -> plugin.getPluginId()).toList());

        System.out.println("MindustryToolPluginLoader initialized");
    }

    @Override
    public void registerServerCommands(CommandHandler handler) {
        serverCommandHandler = handler;
        plugins.values()
                .stream()
                .map(value -> value.get())
                .filter(value -> value != null)
                .forEach((plugin) -> plugin.registerServerCommands(handler));

    }

    @Override
    public void registerClientCommands(CommandHandler handler) {
        clientCommandHandler = handler;
        plugins.values()
                .stream()
                .map(value -> value.get())
                .filter(value -> value != null)
                .forEach((plugin) -> plugin.registerClientCommands(handler));
    }

    public void onEvent(Object event) {
        try {
            plugins.values()
                    .stream()
                    .map(value -> value.get())
                    .filter(value -> value != null)
                    .forEach((plugin) -> plugin.onEvent(event));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void checkAndUpdate() {
        for (var plugin : PLUGINS) {
            try {
                checkAndUpdate(plugin);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    public void checkAndUpdate(PluginData plugin) throws Exception {
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

        if (updatedAt == null) {
            Log.info("Fail to check newest version: " + plugin.name);
            return;
        }

        if (updatedAt != null && Objects.equals(updatedAt, lastUpdated) && Files.exists(path)) {
            return;
        }

        // Download new plugin
        Log.info("Downloading updated plugin: " + plugin.name);
        HttpRequest downloadRequest = HttpRequest.newBuilder()
                .uri(URI.create("https://api.mindustry-tool.com/api/v3/plugins/download?path=" + plugin.url))
                .build();

        var downloadResponse = client.send(downloadRequest, HttpResponse.BodyHandlers.ofByteArray());

        if (downloadResponse.statusCode() >= 300) {
            Log.info("Failed to download plugin: " + plugin.url + " " + downloadResponse.statusCode());

            if (Files.exists(path)) {
                Files.delete(path);
            }

            return;
        }

        try {
            PluginWrapper loaded = pluginManager.getPlugin(plugin.getId());

            if (loaded != null) {
                pluginManager.deletePlugin(loaded.getPluginId());

                Log.info("Unloaded plugin: " + plugin.name);
            }
        } catch (Exception e) {
            Log.err("Failed to unload plugin: " + plugin.name, e);
        } finally {
            plugins.remove(plugin.id);
        }

        try {
            new Fi(path.toAbsolutePath().toString()).writeBytes(downloadResponse.body());
        } catch (Exception e) {
            e.printStackTrace();
        }

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

            pluginManager.startPlugin(pluginId);
            var instance = wrapper.getPlugin();

            if (instance instanceof MindustryToolPlugin mindustryToolPlugin) {
                Log.info("Init plugin: " + mindustryToolPlugin.getClass().getName());

                mindustryToolPlugin.init();

                if (clientCommandHandler != null) {
                    mindustryToolPlugin.registerClientCommands(clientCommandHandler);
                }

                if (serverCommandHandler != null) {
                    mindustryToolPlugin.registerServerCommands(serverCommandHandler);
                }

                plugins.put(pluginId, new WeakReference<>(mindustryToolPlugin));
            } else {
                Log.info("Invalid plugin: " + instance.getClass().getName());
            }

            Log.info("Plugin updated and reloaded: " + plugin.name);

        } catch (Exception e) {
            plugins.remove(plugin.id);
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
