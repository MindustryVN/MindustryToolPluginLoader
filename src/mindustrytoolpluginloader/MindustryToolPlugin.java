package mindustrytoolpluginloader;

import org.pf4j.ExtensionPoint;

import arc.util.CommandHandler;

public interface MindustryToolPlugin extends ExtensionPoint {

    public void init();

    public void registerServerCommands(CommandHandler handler);

    public void registerClientCommands(CommandHandler handler);
}
