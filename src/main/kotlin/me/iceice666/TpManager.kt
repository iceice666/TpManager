package me.iceice666

import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import org.slf4j.LoggerFactory
import org.slf4j.Logger

// Top-level logger for the mod
val logger: Logger = LoggerFactory.getLogger("tp-manager")

/**
 * Main entry point for the TpManager mod
 */
object TpManager : ModInitializer {
    override fun onInitialize() {
        logger.info("Initializing TpManager mod")

        // Register commands
        CommandRegistrationCallback.EVENT.register(WarpCommands::register)
        CommandRegistrationCallback.EVENT.register(TeleportCommands::register)

        // Initialize managers when server starts
        ServerLifecycleEvents.SERVER_STARTED.register { server ->
            WarpModInitializer.initialize(server)
            TeleportManagerInitializer.initialize(server)
        }
    }
}