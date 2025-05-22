package mindustrytoolpluginloader;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import arc.util.CommandHandler;

import org.pf4j.PluginManager;

import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.util.List;
import java.util.Objects;

public class PluginUpdater {

    private static final String GITHUB_API = "https://api.github.com/repos/MindustryVN/MindustryToolPlugin/releases/latest";
    private static final String PLUGIN_DIR = "config/plugins";
    private static final String PLUGIN_NAME = "MindustryToolPlugin.jar";
    private static final Path PLUGIN_PATH = Paths.get(PLUGIN_DIR, PLUGIN_NAME);
    private static final Path METADATA_PATH = Paths.get("config/plugin-meta.json");

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final PluginManager pluginManager;

    public PluginUpdater(PluginManager pluginManager) {
        this.pluginManager = pluginManager;

        if (!Files.exists(Paths.get(PLUGIN_DIR))) {
            try {
                Files.createDirectories(Paths.get(PLUGIN_DIR));
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    public void init() {
        if (!Files.exists(PLUGIN_PATH)) {
            return;
        }
        String pluginId = pluginManager.loadPlugin(PLUGIN_PATH);
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
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://api.mindustry-tool.com/api/v3/plugins/version?path="
                        + GITHUB_API))
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        String updatedAt = response.body();

        String lastUpdated = null;

        if (Files.exists(METADATA_PATH)) {
            JsonNode meta = objectMapper.readTree(Files.readString(METADATA_PATH));
            lastUpdated = meta.path("updated_at").asText(null);
        }

        if (updatedAt != null && Objects.equals(updatedAt, lastUpdated) && Files.exists(PLUGIN_PATH)) {
            return;
        }

        // Download new plugin
        System.out.println("Downloading updated plugin...");
        HttpRequest downloadRequest = HttpRequest.newBuilder()
                .uri(URI.create("https://api.mindustry-tool.com/api/v3/plugins/download?path=" + GITHUB_API))
                .build();

        if (Files.exists(PLUGIN_PATH)) {
            Files.delete(PLUGIN_PATH);
        }

        HttpResponse<Path> downloadResponse = client.send(downloadRequest,
                HttpResponse.BodyHandlers.ofFile(PLUGIN_PATH));

        if (downloadResponse.statusCode() >= 300) {
            System.out.println("Failed to download plugin: " + downloadResponse.statusCode());
            return;
        }

        System.out.println("Downloaded to " + downloadResponse.body());

        // Save updated metadata
        String metaJson = objectMapper.createObjectNode()
                .put("updated_at", updatedAt)
                .toPrettyString();
        Files.writeString(METADATA_PATH, metaJson);

        // Unload current plugin if already loaded
        List<String> loadedPlugins = pluginManager.getPlugins().stream()
                .filter(p -> PLUGIN_PATH.toAbsolutePath().toString().contains(p.getPluginPath().toString()))
                .map(p -> p.getPluginId())
                .toList();

        for (String pluginId : loadedPlugins) {
            pluginManager.stopPlugin(pluginId);
            pluginManager.unloadPlugin(pluginId);
            System.out.println("Unloaded plugin: " + pluginId);
        }

        // Load new version
        String pluginId = pluginManager.loadPlugin(PLUGIN_PATH);
        pluginManager.startPlugin(pluginId);
        var extensions = pluginManager.getExtensions(MindustryToolPlugin.class, pluginId);

        System.out.println("Loaded plugins: " + loadedPlugins);
        for (var extension : extensions) {
            System.out.println("Init plugin: " + extension.getClass().getName());
            extension.init();
        }

        System.out.println("Plugin updated and reloaded.");
    }
}
