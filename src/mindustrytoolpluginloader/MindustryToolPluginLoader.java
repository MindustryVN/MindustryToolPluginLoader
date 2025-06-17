package mindustrytoolpluginloader;

import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.pf4j.DefaultPluginManager;
import org.pf4j.PluginManager;

import arc.util.CommandHandler;
import mindustry.mod.Plugin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import arc.Events;
import arc.files.Fi;
import arc.util.Log;
import lombok.Data;
import mindustry.game.EventType.BlockBuildEndEvent;
import mindustry.game.EventType.GameOverEvent;
import mindustry.game.EventType.MenuOptionChooseEvent;
import mindustry.game.EventType.PlayEvent;
import mindustry.game.EventType.PlayerChatEvent;
import mindustry.game.EventType.PlayerConnect;
import mindustry.game.EventType.PlayerJoin;
import mindustry.game.EventType.PlayerLeave;
import mindustry.game.EventType.ServerLoadEvent;
import mindustry.game.EventType.TapEvent;

import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.util.List;
import java.util.Objects;

public class MindustryToolPluginLoader extends Plugin {

    public final PluginManager pluginManager = new DefaultPluginManager();
    public final UUID SERVER_ID = UUID.fromString(System.getenv("SERVER_ID"));

    public final ScheduledExecutorService BACKGROUND_SCHEDULER = Executors
            .newSingleThreadScheduledExecutor();

    @Data
    public static class PluginData {
        final String name;
        final String url;
    }

    private static final List<PluginData> PLUGINS = List.of(
            new PluginData("ServerController.jar",
                    "https://api.github.com/repos/MindustryVN/ServerController/releases/latest"));

    private static final String PLUGIN_DIR = "config/plugins";
    private static final Path METADATA_PATH = Paths.get("config/plugin-meta.json");

    private final ObjectMapper objectMapper = new ObjectMapper();

    private CommandHandler clientCommandHandler;
    private CommandHandler serverCommandHandler;

    @Override
    public void init() {
        try {
            if (!Files.exists(Paths.get(PLUGIN_DIR))) {
                Files.createDirectories(Paths.get(PLUGIN_DIR));
            }

            for (var file : new Fi(PLUGIN_DIR).list()) {
                if (PLUGINS.stream().anyMatch(p -> p.name.equals(file.name().toString()))) {
                    continue;
                } else {
                    if (file.isDirectory()) {
                        file.deleteDirectory();
                    } else {
                        file.delete();
                    }
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }

        BACKGROUND_SCHEDULER.scheduleWithFixedDelay(() -> {
            try {
                checkAndUpdate();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }, 5, 5, TimeUnit.MINUTES);

        System.out.println("MindustryToolPluginLoader initialized");

        Events.on(GameOverEvent.class, this::onEvent);
        Events.on(PlayEvent.class, this::onEvent);
        Events.on(PlayerJoin.class, this::onEvent);
        Events.on(PlayerLeave.class, this::onEvent);
        Events.on(PlayerChatEvent.class, this::onEvent);
        Events.on(ServerLoadEvent.class, this::onEvent);
        Events.on(PlayerConnect.class, this::onEvent);
        Events.on(BlockBuildEndEvent.class, this::onEvent);
        Events.on(TapEvent.class, this::onEvent);
        Events.on(MenuOptionChooseEvent.class, this::onEvent);

        for (var plugin : PLUGINS) {
            initPlugin(plugin);
        }
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
        pluginManager.getPlugins()
                .stream()
                .map(wrapper -> wrapper.getPlugin())
                .filter(plugin -> plugin instanceof MindustryToolPlugin)
                .map(plugin -> (MindustryToolPlugin) plugin)
                .forEach(plugin -> plugin.onEvent(event));
    }

    public void initPlugin(PluginData plugin) {
        var filePath = Paths.get(PLUGIN_DIR, plugin.name);
        if (!Files.exists(filePath)) {
            Log.info("Plugin not found: " + plugin.name);
            return;
        }

        String pluginId;
        try {
            pluginId = pluginManager.loadPlugin(filePath);
        } catch (Exception e) {
            e.printStackTrace();
            try {
                Files.delete(filePath);
            } catch (IOException e1) {
                e1.printStackTrace();
            }
            throw new RuntimeException("Failed to load plugin: " + plugin.name);
        }
        try {
            pluginManager.startPlugin(pluginId);
            var wrapper = pluginManager.getPlugin(pluginId);

            if (wrapper == null) {
                throw new RuntimeException("Plugin not found: " + pluginId);
            }

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
            } else {
                Log.info("Invalid plugin: " + instance.getClass().getName());
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void checkAndUpdate() throws Exception {
        for (var plugin : PLUGINS) {
            checkAndUpdate(plugin);
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
        if (updatedAt != null && Objects.equals(updatedAt, lastUpdated) && Files.exists(path)) {
            return;
        }
        // Unload current plugin if already loaded
        List<String> loadedPlugins = pluginManager.getPlugins()
                .stream()
                .filter(p -> path.toAbsolutePath().toString().contains(p.getPluginPath().toString()))
                .map(p -> p.getPluginId())
                .toList();

        for (String pluginId : loadedPlugins) {
            pluginManager.stopPlugin(pluginId);
            pluginManager.unloadPlugin(pluginId);
            Log.info("Unloaded plugin: " + plugin.name);
        }

        // Download new plugin
        Log.info("Downloading updated plugin: " + plugin.name);
        HttpRequest downloadRequest = HttpRequest.newBuilder()
                .uri(URI.create("https://api.mindustry-tool.com/api/v3/plugins/download?path=" + plugin.url))
                .build();

        if (Files.exists(path)) {
            Files.delete(path);
        }

        HttpResponse<Path> downloadResponse = client.send(downloadRequest,
                HttpResponse.BodyHandlers.ofFile(path));

        if (downloadResponse.statusCode() >= 300) {
            Log.info("Failed to download plugin: " + plugin.url + " " + downloadResponse.statusCode());
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

        initPlugin(plugin);

        Log.info("Plugin updated and reloaded: " + plugin.name);
    }
}
