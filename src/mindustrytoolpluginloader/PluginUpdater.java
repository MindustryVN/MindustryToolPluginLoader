package mindustrytoolpluginloader;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import arc.Events;
import arc.util.CommandHandler;
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

import org.pf4j.PluginManager;

import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.util.List;
import java.util.Objects;

public class PluginUpdater {

    public static record PluginData(String name, String url) {
    }

    private static final List<PluginData> PLUGINS = List.of(
            new PluginData("ServerController.jar",
                    "https://api.github.com/repos/MindustryVN/ServerController/releases/latest"));

    private static final String PLUGIN_DIR = "config/plugins";
    private static final Path METADATA_PATH = Paths.get("config/plugin-meta.json");

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final PluginManager pluginManager;

    public PluginUpdater(PluginManager pluginManager) {
        this.pluginManager = pluginManager;

        try {
            if (!Files.exists(Paths.get(PLUGIN_DIR))) {
                Files.createDirectories(Paths.get(PLUGIN_DIR));
            }

            for (var file : Files.walk(Paths.get(PLUGIN_DIR)).skip(1).toList()) {
                if (PLUGINS.stream().anyMatch(p -> p.name.equals(file.getFileName().toString()))) {
                    continue;
                } else {
                    Files.delete(file);
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void init() {
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

    public void onEvent(Object event) {
        var extensions = pluginManager.getExtensions(MindustryToolPlugin.class);

        for (var extension : extensions) {
            extension.onEvent(event);
        }
    }

    public void initPlugin(PluginData plugin) {
        var filePath = Paths.get(PLUGIN_DIR, plugin.name);
        if (!Files.exists(filePath)) {
            return;
        }

        String pluginId = pluginManager.loadPlugin(filePath);
        pluginManager.startPlugin(pluginId);
        var extensions = pluginManager.getExtensions(MindustryToolPlugin.class, pluginId);

        for (var extension : extensions) {
            System.out.println("Init plugin: " + extension.getClass().getName());
            extension.init();
        }
    }

    public void registerClientCommands(CommandHandler handler) {
        var extensions = pluginManager.getExtensions(MindustryToolPlugin.class);

        for (var extension : extensions) {
            extension.registerClientCommands(handler);
        }
    }

    public void registerServerCommands(CommandHandler handler) {
        var extensions = pluginManager.getExtensions(MindustryToolPlugin.class);

        for (var extension : extensions) {
            extension.registerServerCommands(handler);
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

        // Download new plugin
        System.out.println("Downloading updated plugin: " + plugin.name);
        HttpRequest downloadRequest = HttpRequest.newBuilder()
                .uri(URI.create("https://api.mindustry-tool.com/api/v3/plugins/download?path=" + plugin.url))
                .build();

        if (Files.exists(path)) {
            Files.delete(path);
        }

        HttpResponse<Path> downloadResponse = client.send(downloadRequest,
                HttpResponse.BodyHandlers.ofFile(path));

        if (downloadResponse.statusCode() >= 300) {
            System.out.println("Failed to download plugin: " + plugin.url + " " + downloadResponse.statusCode());
            return;
        }

        System.out.println("Downloaded to " + downloadResponse.body());

        if (meta == null) {
            meta = objectMapper.createObjectNode();
        }

        // Save updated metadata
        meta
                .putObject(plugin.name())
                .put("updated_at", updatedAt).put("url", plugin.url);

        Files.writeString(METADATA_PATH, meta.toPrettyString());

        // Unload current plugin if already loaded
        List<String> loadedPlugins = pluginManager.getPlugins().stream()
                .filter(p -> path.toAbsolutePath().toString().contains(p.getPluginPath().toString()))
                .map(p -> p.getPluginId())
                .toList();

        for (String pluginId : loadedPlugins) {
            pluginManager.stopPlugin(pluginId);
            pluginManager.unloadPlugin(pluginId);
            System.out.println("Unloaded plugin: " + plugin.name);
        }

        // Load new version
        String pluginId = pluginManager.loadPlugin(path);
        pluginManager.startPlugin(pluginId);
        var extensions = pluginManager.getExtensions(MindustryToolPlugin.class, pluginId);

        System.out.println("Loaded plugins: " + loadedPlugins);
        for (var extension : extensions) {
            System.out.println("Init plugin: " + plugin.name + " with extension: " + extension.getClass().getName());
            extension.init();
        }

        System.out.println("Plugin updated and reloaded: " + plugin.name);
    }
}
