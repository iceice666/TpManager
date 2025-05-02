package me.iceice666

import net.fabricmc.api.ModInitializer
import org.slf4j.LoggerFactory
import  org.slf4j.Logger

val logger: Logger = LoggerFactory.getLogger("tp-manager")

object TpManager : ModInitializer {


    override fun onInitialize() {
// Register commands
        CommandRegistrationCallback.EVENT.register(WarpCommands::register)

        // Initialize warp manager when server starts
        ServerLifecycleEvents.SERVER_STARTED.register { server ->
            WarpModInitializer.initialize(server)
        }
    }
}