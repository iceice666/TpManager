package me.iceice666

import me.iceice666.tp.TeleportCommands
import me.iceice666.tp.TeleportManagerInitializer
import me.iceice666.warp.WarpCommands
import me.iceice666.warp.WarpModInitializer
import net.fabricmc.api.DedicatedServerModInitializer
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import org.slf4j.LoggerFactory
import org.slf4j.Logger

// Top-level logger for the mod
val logger: Logger = LoggerFactory.getLogger("tp-manager")

/**
 * Main entry point for the TpManager mod
 */
object TpManager : DedicatedServerModInitializer {
    override fun onInitializeServer() {
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