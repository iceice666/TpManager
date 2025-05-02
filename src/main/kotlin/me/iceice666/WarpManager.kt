package me.iceice666

import com.google.gson.GsonBuilder
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.minecraft.registry.RegistryKey
import net.minecraft.registry.RegistryKeys
import net.minecraft.server.MinecraftServer
import net.minecraft.util.Identifier
import net.minecraft.world.World
import java.io.File
import java.util.*
import java.util.concurrent.ConcurrentHashMap

// Data class to represent a warp point
data class WarpPoint(
    val id: String,
    val owner: UUID,
    val x: Double,
    val y: Double,
    val z: Double,
    val dimension: RegistryKey<World> ,
    val isPublic: Boolean,
    val name: String
)

// Class to manage warp points
class WarpManager(private val server: MinecraftServer) {
    private val warps = ConcurrentHashMap<String, WarpPoint>()
    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val saveFile = File("config/warps.json")

    init {
        loadWarps()

        // Register save event when server stops
        ServerLifecycleEvents.SERVER_STOPPING.register {
            saveWarps()
        }
    }

    // Load warps from file
    private fun loadWarps() {
        if (saveFile.exists()) {
            try {
                val json = saveFile.readText()
                val type = object : com.google.gson.reflect.TypeToken<Map<String, WarpPoint>>() {}.type
                val loadedWarps: Map<String, WarpPoint> = gson.fromJson(json, type)
                warps.putAll(loadedWarps)
            } catch (e: Exception) {
                println("Error loading warps: ${e.message}")
            }
        } else {
            saveFile.parentFile.mkdirs()
        }
    }

    // Save warps to file
    fun saveWarps() {
        try {
            val json = gson.toJson(warps)
            saveFile.writeText(json)
        } catch (e: Exception) {
            println("Error saving warps: ${e.message}")
        }
    }

    // Add a new warp point
    fun addWarp(
        id: String, owner: UUID, x: Double, y: Double, z: Double,
        dimension: RegistryKey<World> , isPublic: Boolean, name: String
    ): Boolean {
        if (warps.containsKey(id)) {
            return false
        }

        val warp = WarpPoint(id, owner, x, y, z, dimension, isPublic, name)
        warps[id] = warp
        saveWarps()
        return true
    }

    // Remove a warp point
    fun removeWarp(id: String, playerId: UUID): Boolean {
        val warp = warps[id] ?: return false

        // Only the owner or an operator can remove a warp
        // Fixed nullable boolean issue with safe call operator
        if (warp.owner != playerId && !(server.playerManager.getPlayer(playerId)?.hasPermissionLevel(2) ?: false)) {
            return false
        }

        warps.remove(id)
        saveWarps()
        return true
    }

    // Get a warp point
    fun getWarp(id: String, playerId: UUID): WarpPoint? {
        val warp = warps[id] ?: return null

        // Check if player can access this warp
        if (!warp.isPublic && warp.owner != playerId &&
            !(server.playerManager.getPlayer(playerId)?.hasPermissionLevel(2) ?: false)
        ) {
            return null
        }

        return warp
    }

    // Get all accessible warps for a player
    fun getAccessibleWarps(playerId: UUID): List<WarpPoint> {
        val isOp = server.playerManager.getPlayer(playerId)?.hasPermissionLevel(2) ?: false

        return warps.values.filter { warp ->
            warp.isPublic || warp.owner == playerId || isOp
        }
    }

    // Get all warps owned by a player
    fun getPlayerWarps(playerId: UUID): List<WarpPoint> {
        return warps.values.filter { it.owner == playerId }
    }

    // Check if a player has a warp with the given name
    fun hasWarpWithName(playerId: UUID, name: String): Boolean {
        return warps.values.any { it.owner == playerId && it.name.equals(name, ignoreCase = true) }
    }


}


class WarpModInitializer {
    companion object {
        private var warpManager: WarpManager? = null

        fun initialize(server: MinecraftServer) {
            warpManager = WarpManager(server)
        }

        fun getWarpManager(): WarpManager {
            return warpManager ?: throw IllegalStateException("WarpManager not initialized")
        }
    }
}