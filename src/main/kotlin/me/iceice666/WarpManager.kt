package me.iceice666

import com.google.gson.GsonBuilder
import com.google.gson.JsonSyntaxException
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.minecraft.registry.RegistryKey
import net.minecraft.server.MinecraftServer
import net.minecraft.util.Identifier
import net.minecraft.world.World
import java.io.File
import java.io.IOException
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/**
 * Immutable data class representing a warp point location
 */
data class WarpPoint(
    val id: String,
    val owner: UUID,
    val x: Double,
    val y: Double,
    val z: Double,
    val dimension: Identifier,
    val isPublic: Boolean,
    val name: String
)

/**
 * Manages warp points for teleportation
 */
class WarpManager(private val server: MinecraftServer) {
    private val warps = ConcurrentHashMap<String, WarpPoint>()
    private val gson = GsonBuilder()
        .setPrettyPrinting()
        .serializeNulls()
        .create()
    private val saveFile = File("warps.json")
    private val configDir = File("config")

    init {
        loadWarps()
        registerEvents()
    }

    /**
     * Registers lifecycle events for the warp manager
     */
    private fun registerEvents() {
        ServerLifecycleEvents.SERVER_STOPPING.register { _ ->
            saveWarps()
        }
    }

    /**
     * Loads warp points from the save file
     */
    private fun loadWarps() {
        try {
            if (!configDir.exists()) {
                configDir.mkdirs()
            }

            if (saveFile.exists()) {
                val json = saveFile.readText()
                val type = object : com.google.gson.reflect.TypeToken<Map<String, WarpPoint>>() {}.type
                gson.fromJson<Map<String, WarpPoint>>(json, type)
                    ?.let { loadedWarps ->
                        warps.putAll(loadedWarps)
                        logger.info("Loaded ${loadedWarps.size} warp points")
                    }
            } else {
                logger.info("No warp save file found, creating new file")
                saveFile.createNewFile()
                saveWarps()
            }
        } catch (e: JsonSyntaxException) {
            logger.error("Error parsing warp save file: ${e.message}")
            // Create backup of corrupted file
            val backupFile = File("${saveFile.path}.backup-${System.currentTimeMillis()}")
            saveFile.copyTo(backupFile)
            logger.info("Created backup of corrupted save file: ${backupFile.name}")
        } catch (e: IOException) {
            logger.error("I/O error loading warps: ${e.message}")
        } catch (e: Exception) {
            logger.error("Unexpected error loading warps", e)
        }
    }

    /**
     * Saves warp points to the save file
     */
    fun saveWarps() {
        try {
            val json = gson.toJson(warps)
            saveFile.writeText(json)
            logger.info("Saved ${warps.size} warp points")
        } catch (e: IOException) {
            logger.error("I/O error saving warps: ${e.message}")
        } catch (e: Exception) {
            logger.error("Unexpected error saving warps", e)
        }
    }

    /**
     * Checks if a player has operator privileges
     */
    private fun isPlayerOperator(playerId: UUID): Boolean =
        server.playerManager.getPlayer(playerId)?.hasPermissionLevel(2) ?: false

    /**
     * Adds a new warp point
     *
     * @return true if warp was added successfully, false if it already exists
     */
    fun addWarp(
        id: String, owner: UUID, x: Double, y: Double, z: Double,
        dimension: RegistryKey<World>, isPublic: Boolean, name: String
    ): Boolean {
        if (warps.containsKey(id)) {
            return false
        }

        val warp = WarpPoint(id, owner, x, y, z, dimension.value, isPublic, name)
        warps[id] = warp
        saveWarps()
        return true
    }

    /**
     * Removes a warp point if the player has permission
     *
     * @return true if warp was removed successfully
     */
    fun removeWarp(id: String, playerId: UUID): Boolean {
        val warp = warps[id] ?: return false

        // Check permissions: must be owner or operator
        if (warp.owner != playerId && !isPlayerOperator(playerId)) {
            return false
        }

        warps.remove(id)
        saveWarps()
        return true
    }

    /**
     * Updates an existing warp point if the player has permission
     *
     * @return true if warp was updated successfully
     */
    fun updateWarp(playerId: UUID, updatedWarp: WarpPoint): Boolean {
        val existingWarp = warps[updatedWarp.id] ?: return false
        if (existingWarp.owner != playerId && !isPlayerOperator(playerId)) {
            return false
        }
        warps[updatedWarp.id] = updatedWarp
        saveWarps()
        return true
    }

    /**
     * Gets all warps accessible to a player (own warps, public warps, all warps for ops)
     */
    fun getAccessibleWarps(playerId: UUID): List<WarpPoint> {
        val isOp = isPlayerOperator(playerId)

        return warps.values
            .filter { warp -> warp.isPublic || warp.owner == playerId || isOp }
            .sortedBy { it.name }
    }

    /**
     * Gets all warps owned by a player
     */
    fun getPlayerWarps(playerId: UUID): List<WarpPoint> =
        warps.values
            .filter { it.owner == playerId }
            .sortedBy { it.name }

    /**
     * Checks if a player has a warp with the given name
     */
    fun hasWarpWithName(playerId: UUID, name: String): Boolean =
        warps.values.any {
            it.owner == playerId && it.name.equals(name, ignoreCase = true)
        }
}

/**
 * Manages initialization and access to the WarpManager
 */
object WarpModInitializer {
    private var warpManager: WarpManager? = null

    /**
     * Initializes the WarpManager with the server instance
     */
    fun initialize(server: MinecraftServer) {
        logger.info("Initializing WarpManager")
        warpManager = WarpManager(server)
    }

    /**
     * Gets the current WarpManager instance
     * @throws IllegalStateException if WarpManager is not initialized
     */
    fun getWarpManager(): WarpManager =
        warpManager ?: throw IllegalStateException("WarpManager not initialized")
}