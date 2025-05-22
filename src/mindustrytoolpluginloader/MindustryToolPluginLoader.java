package mindustrytoolpluginloader;

import org.pf4j.DefaultPluginManager;
import org.pf4j.PluginManager;

import arc.util.CommandHandler;
import arc.util.Timer;
import mindustry.mod.Plugin;

public class MindustryToolPluginLoader extends Plugin {

    public static final PluginManager pluginManager = new DefaultPluginManager();
    public static final PluginUpdater updater = new PluginUpdater(pluginManager);

    @Override
    public void init() {

        updater.init();

        Timer.schedule(() -> {
            try {
                updater.checkAndUpdate();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }, 0, 60 * 60);

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
}
