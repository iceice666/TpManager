package me.iceice666


import me.iceice666.tp.TeleportManagerInitializer
import net.fabricmc.api.DedicatedServerModInitializer
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import org.slf4j.Logger
import org.slf4j.LoggerFactory

// Top-level logger for the mod
val logger: Logger = LoggerFactory.getLogger("tp-manager")

/**
 * Main entry point for the TpManager mod
 */
object TpManager : DedicatedServerModInitializer {
    override fun onInitializeServer() {
        logger.info("Initializing TpManager mod")

        // Register commands
        CommandRegistrationCallback.EVENT.register { dispatcher, _, _ ->
            (me.iceice666.warp.Commands::register)(dispatcher)
            (me.iceice666.tp.Commands::register)(dispatcher)
        }


        // Initialize managers when server starts
        ServerLifecycleEvents.SERVER_STARTED.register { server ->
            TeleportManagerInitializer.initialize(server)
        }
    }
}