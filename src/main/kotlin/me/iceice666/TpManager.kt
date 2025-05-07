/*
 *     TpManager - A Minecraft mod for managing teleportation points
 *     Copyright (C) 2025-Present Brian Duan <iceice666@outlook.com>
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU Affero General Public License as published
 *     by the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU Affero General Public License for more details.
 *
 *     You should have received a copy of the GNU Affero General Public License
 *     along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

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