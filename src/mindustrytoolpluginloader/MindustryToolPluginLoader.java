package mindustrytoolpluginloader;

import org.pf4j.DefaultPluginManager;
import org.pf4j.PluginManager;

import arc.util.CommandHandler;
import arc.util.Timer;
import mindustry.mod.Plugin;

public class MindustryToolPluginLoader extends Plugin {

    public static CommandHandler clientCommandHandler;
    public static CommandHandler serverCommandHandler;

    @Override
    public void init() {
        PluginManager pluginManager = new DefaultPluginManager();
        PluginUpdater updater = new PluginUpdater(pluginManager);

        Timer.schedule(() -> {
            try {
                updater.checkAndUpdate();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }, 0, 60 * 60);
    }

    @Override
    public void registerServerCommands(CommandHandler handler) {
        serverCommandHandler = handler;
    }

    @Override
    public void registerClientCommands(CommandHandler handler) {
        clientCommandHandler = handler;
    }
}
