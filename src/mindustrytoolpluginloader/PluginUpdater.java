package mindustrytoolpluginloader;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
    private static final Path METADATA_PATH = Paths.get("plugin-meta.json");

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

    public void checkAndUpdate() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(GITHUB_API))
                .header("Accept", "application/vnd.github+json")
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        JsonNode release = objectMapper.readTree(response.body());

        JsonNode assets = release.get("assets");
        if (assets == null || !assets.isArray() || assets.isEmpty()) {
            System.out.println("No assets found in release.");
            return;
        }

        String downloadUrl = assets.get(0).get("browser_download_url").asText();
        String updatedAt = assets.get(0).get("updated_at").asText();

        String lastUpdated = null;
        if (Files.exists(METADATA_PATH)) {
            JsonNode meta = objectMapper.readTree(Files.readString(METADATA_PATH));
            lastUpdated = meta.path("updated_at").asText(null);
        }

        if (Objects.equals(updatedAt, lastUpdated)) {
            System.out.println("Plugin is up to date.");
            return;
        }

        // Download new plugin
        System.out.println("Downloading updated plugin...");
        HttpRequest downloadRequest = HttpRequest.newBuilder()
                .uri(URI.create(downloadUrl))
                .build();

        HttpResponse<Path> downloadResponse = client.send(downloadRequest,
                HttpResponse.BodyHandlers.ofFile(PLUGIN_PATH));

        System.out.println("Downloaded to " + downloadResponse.body());

        // Unload current plugin if already loaded
        List<String> loadedPlugins = pluginManager.getPlugins().stream()
                .filter(p -> PLUGIN_PATH.toAbsolutePath().toString().contains(p.getPluginPath().toString()))
                .map(p -> p.getPluginId())
                .toList();

        for (String pluginId : loadedPlugins) {
            pluginManager.stopPlugin(pluginId);
            pluginManager.unloadPlugin(pluginId);
        }

        // Load new version
        String pluginId = pluginManager.loadPlugin(PLUGIN_PATH);
        pluginManager.startPlugin(pluginId);

        var extensions = pluginManager.getExtensions(MindustryToolPlugin.class, pluginId);

        for (var extension : extensions) {
            extension.init();
            extension.registerClientCommands(MindustryToolPluginLoader.clientCommandHandler);
            extension.registerServerCommands(MindustryToolPluginLoader.serverCommandHandler);
        }

        // Save updated metadata
        String metaJson = objectMapper.createObjectNode()
                .put("updated_at", updatedAt)
                .toPrettyString();
        Files.writeString(METADATA_PATH, metaJson);

        System.out.println("Plugin updated and reloaded.");
    }
}
