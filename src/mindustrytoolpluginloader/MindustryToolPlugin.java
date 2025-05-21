package mindustrytoolpluginloader;

import arc.util.CommandHandler;

public interface MindustryToolPlugin {

    public void init();

    public void registerServerCommands(CommandHandler handler);

    public void registerClientCommands(CommandHandler handler);
}
