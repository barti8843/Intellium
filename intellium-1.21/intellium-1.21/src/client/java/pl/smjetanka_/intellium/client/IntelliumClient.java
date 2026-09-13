package pl.smjetanka_.intellium.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;

public class IntelliumClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        IntelRenderer.getInstance().initialize();
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> IntelRenderer.getInstance().close());
    }
}
