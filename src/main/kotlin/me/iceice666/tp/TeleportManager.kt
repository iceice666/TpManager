package me.iceice666.tp

import me.iceice666.Config
import me.iceice666.logger
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.minecraft.registry.tag.FluidTags
import net.minecraft.server.MinecraftServer
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.text.Text
import net.minecraft.util.Formatting
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Manages teleport requests between players
 */
class TeleportManager(private val server: MinecraftServer) {
    // Maps player UUID to their pending teleport requests (requesterId -> expirationTime, isTeleportHere)
    private val pendingRequests = ConcurrentHashMap<UUID, ConcurrentHashMap<UUID, Pair<Long, Boolean>>>()

    // Maps player UUID to their teleport preferences
    private val playerPreferences = ConcurrentHashMap<UUID, TeleportPreference>()

    // Maps player UUID to their last teleport time for cooldown
    private val lastTeleportTime = ConcurrentHashMap<UUID, Long>()

    // Configuration instance
    private var config = Config.Companion.get()

    // Scheduled executor for cleanup tasks
    private val scheduledExecutor = Executors.newSingleThreadScheduledExecutor()

    init {
        // Schedule periodic cleanup of expired requests (every 30 seconds)
        scheduledExecutor.scheduleAtFixedRate(
            { cleanExpiredRequests(true) },
            30, 30, TimeUnit.SECONDS
        )
    }

    /**
     * Reloads configuration from disk
     */
    fun reloadConfig() {
        Config.Companion.reload()
        config = Config.Companion.get()
        logger.info(
            "TeleportManager config reloaded: cooldown=${config.teleportCooldownSeconds}s, " +
                    "expiration=${config.requestExpirationTimeSeconds}s, safetyCheck=${config.enableSafetyCheck}"
        )
    }

    /**
     * Creates a teleport request from one player to another
     *
     * @return RequestResult indicating success, failure reason, or auto-accept
     */
    fun createRequest(requesterId: UUID, targetId: UUID, isTeleportHere: Boolean): RequestResult {
        // Validate that both players exist
        val requester = server.playerManager.getPlayer(requesterId)
        val target = server.playerManager.getPlayer(targetId)

        if (requester == null || target == null) {
            return RequestResult.PLAYER_NOT_FOUND
        }

        // Check for existing request
        val targetRequests = pendingRequests.getOrPut(targetId) { ConcurrentHashMap() }
        if (targetRequests.containsKey(requesterId)) {
            return RequestResult.ALREADY_REQUESTED
        }

        // Check target's preferences
        when (playerPreferences.getOrDefault(targetId, TeleportPreference.ASK)) {
            TeleportPreference.AUTO_ACCEPT -> {
                // Auto-accept logic
                executeTeleport(requesterId, targetId, isTeleportHere)
                return RequestResult.AUTO_ACCEPTED
            }

            TeleportPreference.AUTO_DECLINE -> {
                return RequestResult.AUTO_DECLINED
            }

            else -> {
                // Create a new request
                val expirationTime = System.currentTimeMillis() + config.getRequestExpirationTimeMillis()
                targetRequests[requesterId] = Pair(expirationTime, isTeleportHere)
                return RequestResult.CREATED
            }
        }
    }

    /**
     * Accepts a teleport request
     *
     * @return true if request was found and accepted, false otherwise
     */
    fun acceptRequest(targetId: UUID, requesterId: UUID): Boolean {
        // Clean expired requests first
        cleanExpiredRequests()

        // Check if request exists
        val requests = pendingRequests.get(targetId) ?: return false
        if (!requests.containsKey(requesterId)) {
            return false
        }

        // Check the teleport type
        val isTeleportHere = requests.get(requesterId)?.second ?: false

        // Making sure player is existing
        val requester = server.playerManager.getPlayer(requesterId)
        val target = server.playerManager.getPlayer(targetId)

        if (requester == null || target == null) {
            return false
        }



        val tpResult = executeTeleport(requesterId, targetId, isTeleportHere)
        if (tpResult) requests.remove(requesterId)
        return true
    }

    /**
     * Declines a teleport request
     *
     * @return true if request was found and declined, false otherwise
     */
    fun declineRequest(targetId: UUID, requesterId: UUID): Boolean {
        val requests = pendingRequests[targetId] ?: return false
        return requests.remove(requesterId) != null
    }

    /**
     * Cancels a sent teleport request
     *
     * @param requesterId UUID of the player who sent the request
     * @param targetId UUID of the player who received the request
     * @return true if request was found and cancelled, false otherwise
     */
    fun cancelRequest(requesterId: UUID, targetId: UUID): Boolean {
        val requests = pendingRequests[targetId] ?: return false
        return requests.remove(requesterId) != null
    }

    /**
     * Gets all sent requests from a player
     *
     * @return Map of target UUID to expiration time and teleport type
     */
    fun getSentRequests(requesterId: UUID): Map<UUID, Pair<Long, Boolean>> {
        cleanExpiredRequests() // Clean expired requests first

        val sentRequests = mutableMapOf<UUID, Pair<Long, Boolean>>()

        // Find all requests sent by this player
        pendingRequests.forEach { (targetId, requests) ->
            requests[requesterId]?.let { requestData ->
                sentRequests[targetId] = requestData
            }
        }

        return sentRequests
    }


    /**
     * Sets a player's teleport preference
     */
    fun setPreference(playerId: UUID, preference: TeleportPreference) {
        playerPreferences[playerId] = preference
    }

    /**
     * Gets a player's current teleport preference
     */
    fun getPreference(playerId: UUID): TeleportPreference {
        return playerPreferences.getOrDefault(playerId, TeleportPreference.ASK)
    }

    /**
     * Gets the remaining cooldown time for a player in seconds
     *
     * @return Remaining cooldown time in seconds, 0 if no cooldown
     */
    fun getRemainingCooldown(playerId: UUID): Int {
        val currentTime = System.currentTimeMillis()
        val lastTp = lastTeleportTime[playerId] ?: 0L
        val elapsed = currentTime - lastTp
        val cooldown = config.getTeleportCooldownMillis()
        return if (elapsed >= cooldown) 0 else ((cooldown - elapsed) / 1000).toInt()
    }

    /**
     * Gets all pending requests for a player
     */
    fun getPendingRequests(playerId: UUID): Map<UUID, Pair<Long, Boolean>> {
        // Clean expired requests first
        cleanExpiredRequests()
        return pendingRequests[playerId] ?: emptyMap()
    }

    /**
     * Checks if a player has any pending requests
     */
    fun hasPendingRequests(playerId: UUID): Boolean {
        // Clean expired requests first
        cleanExpiredRequests()
        return pendingRequests[playerId]?.isNotEmpty() ?: false
    }

    /**
     * Cleans up expired requests
     *
     * @param notifyPlayers Whether to notify players about expired requests
     */
    private fun cleanExpiredRequests(notifyPlayers: Boolean = false) {
        val currentTime = System.currentTimeMillis()
        val expiredRequests = mutableListOf<Triple<UUID, UUID, Boolean>>() // targetId, requesterId, isTeleportHere

        // Find expired requests
        pendingRequests.forEach { (targetId, requests) ->
            requests.entries.removeIf { (requesterId, value) ->
                val (expirationTime, isTeleportHere) = value
                val isExpired = currentTime > expirationTime

                if (isExpired && notifyPlayers) {
                    expiredRequests.add(Triple(targetId, requesterId, isTeleportHere))
                }

                isExpired
            }
        }

        // Remove empty request maps
        pendingRequests.entries.removeIf { (_, requests) -> requests.isEmpty() }

        // Notify players about expired requests if needed
        if (notifyPlayers) {
            expiredRequests.forEach { (targetId, requesterId, isTeleportHere) ->
                val targetPlayer = server.playerManager.getPlayer(targetId)
                val requesterPlayer = server.playerManager.getPlayer(requesterId)

                if (targetPlayer != null && requesterPlayer != null) {
                    val requestType = if (isTeleportHere) "teleport to them" else "teleport to you"
                    targetPlayer.sendMessage(
                        Text.literal("Teleport request from ${requesterPlayer.name.string} to $requestType has expired")
                            .formatted(Formatting.YELLOW)
                    )
                    requesterPlayer.sendMessage(
                        Text.literal("Your teleport request to ${targetPlayer.name.string} has expired")
                            .formatted(Formatting.YELLOW)
                    )
                }
            }
        }
    }

    /**
     * Checks if player is on teleport cooldown
     *
     * @return true if player can teleport, false if still on cooldown
     */
    private fun canPlayerTeleport(playerId: UUID): Boolean {
        val currentTime = System.currentTimeMillis()
        val lastTp = lastTeleportTime[playerId] ?: 0L
        return (currentTime - lastTp) >= config.getTeleportCooldownMillis()
    }

    /**
     * Checks if the destination is safe for teleportation
     *
     * @return true if destination is safe, false otherwise
     */
    private fun isDestinationSafe(destination: ServerPlayerEntity): Boolean {
        // Skip safety check if disabled in config
        if (!config.enableSafetyCheck) {
            return true
        }

        val world = destination.world
        val pos = destination.blockPos

        // Check if destination is not in a solid block
        if (!world.getBlockState(pos).isAir && !world.getBlockState(pos.up()).isAir) {
            return false
        }

        // Check if there's a safe place to stand (non-air block below feet)
        if (world.getBlockState(pos.down()).isAir) {
            return false
        }

        // Check if destination is not in lava or fire
        val blockState = world.getBlockState(pos)
        if (blockState.fluidState.isIn(FluidTags.LAVA)) {
            return false
        }

        return true
    }

    /**
     * Updates the player's teleport cooldown
     */
    private fun updateTeleportCooldown(playerId: UUID) {
        lastTeleportTime[playerId] = System.currentTimeMillis()
    }

    /**
     * Executes the teleport between two players
     */
    private fun executeTeleport(requesterId: UUID, targetId: UUID, isTeleportHere: Boolean) : Boolean {
        val requester = server.playerManager.getPlayer(requesterId)
        val target = server.playerManager.getPlayer(targetId)

        if (requester == null || target == null) {
            return true
        }

        // Determine who is teleporting
        val teleportingPlayer = if (isTeleportHere) target else requester
        val destinationPlayer = if (isTeleportHere) requester else target

        // Check cooldown
        if (!canPlayerTeleport(teleportingPlayer.uuid)) {
            val remainingSeconds = getRemainingCooldownSeconds(teleportingPlayer.uuid)
            teleportingPlayer.sendMessage(
                Text.literal("You cannot teleport for another $remainingSeconds seconds").formatted(Formatting.RED)
            )
            return false
        }

        // Check if destination is safe
        if (!isDestinationSafe(destinationPlayer)) {
            teleportingPlayer.sendMessage(
                Text.literal("Destination is unsafe. Teleport canceled").formatted(Formatting.RED)
            )
            destinationPlayer.sendMessage(
                Text.literal("Your location is unsafe for teleportation").formatted(Formatting.RED)
            )
            return false
        }

        // Execute the teleport
        if (isTeleportHere) {
            // Teleport target to requester
            teleportPlayer(target, requester)
            target.sendMessage(Text.literal("Teleported to ${requester.name.string}").formatted(Formatting.GREEN))
            requester.sendMessage(
                Text.literal("${target.name.string} was teleported to you").formatted(Formatting.GREEN)
            )
        } else {
            // Teleport requester to target
            teleportPlayer(requester, target)
            requester.sendMessage(Text.literal("Teleported to ${target.name.string}").formatted(Formatting.GREEN))
            target.sendMessage(
                Text.literal("${requester.name.string} was teleported to you").formatted(Formatting.GREEN)
            )
        }

        // Set cooldown for teleported player
        updateTeleportCooldown(teleportingPlayer.uuid)

        return true
    }

    /**
     * Get remaining cooldown in seconds for a player
     */
    private fun getRemainingCooldownSeconds(playerId: UUID): Int {
        val currentTime = System.currentTimeMillis()
        val lastTp = lastTeleportTime[playerId] ?: 0L
        val elapsed = currentTime - lastTp
        val cooldown = config.getTeleportCooldownMillis()
        return if (elapsed >= cooldown) 0 else ((cooldown - elapsed) / 1000).toInt()
    }

    /**
     * Teleports a player to another player's location
     */
    private fun teleportPlayer(player: ServerPlayerEntity, destination: ServerPlayerEntity) {
        // Use the entity's exact position for more precise teleporting
        val x = destination.x
        val y = destination.y
        val z = destination.z
        val dimensionKey = destination.world.registryKey.value

        // Build teleport command that preserves rotation
        val tpCommand =
            "execute in $dimensionKey run tp ${player.uuid} $x $y $z ${destination.yaw} ${destination.pitch}"

        // Execute teleport command
        player.server.commandManager.dispatcher.execute(tpCommand, player.server.commandSource)
    }
}

/**
 * Enum for teleport request preferences
 */
enum class TeleportPreference {
    ASK,           // Default - ask before teleporting
    AUTO_ACCEPT,   // Automatically accept all teleport requests
    AUTO_DECLINE   // Automatically decline all teleport requests
}

/**
 * Result of a teleport request creation
 */
enum class RequestResult {
    CREATED,           // Request was created successfully
    ALREADY_REQUESTED, // A request already exists
    PLAYER_NOT_FOUND,  // Player not found
    AUTO_ACCEPTED,     // Request was automatically accepted
    AUTO_DECLINED      // Request was automatically declined
}

/**
 * Singleton for teleport manager access
 */
object TeleportManagerInitializer {
    private var teleportManager: TeleportManager? = null

    /**
     * Initializes the TeleportManager with the server instance
     */
    fun initialize(server: MinecraftServer) {
        logger.info("Initializing TeleportManager")

        // Load configuration
        val config = Config.Companion.load()
        logger.info(
            "Loaded TpManager configuration: cooldown=${config.teleportCooldownSeconds}s, " +
                    "expiration=${config.requestExpirationTimeSeconds}s, safetyCheck=${config.enableSafetyCheck}"
        )

        // Create teleport manager
        teleportManager = TeleportManager(server)

        // Register shutdown hook to clean up resources
        ServerLifecycleEvents.SERVER_STOPPING.register {
            shutdown()
        }
    }

    /**
     * Shutdown and cleanup resources
     */
    fun shutdown() {
        logger.info("Shutting down TeleportManager")
        teleportManager?.let {
            // Access the private scheduler field using reflection and shut it down
            try {
                val schedulerField = TeleportManager::class.java.getDeclaredField("scheduledExecutor")
                schedulerField.isAccessible = true
                val scheduler = schedulerField.get(it) as ScheduledExecutorService
                scheduler.shutdown()
                logger.info("TeleportManager scheduler shutdown successfully")
            } catch (e: Exception) {
                logger.error("Error shutting down TeleportManager scheduler", e)
            }
        }
        teleportManager = null
    }

    /**
     * Gets the current TeleportManager instance
     * @throws IllegalStateException if TeleportManager is not initialized
     */
    fun getTeleportManager(): TeleportManager =
        teleportManager ?: throw IllegalStateException("TeleportManager not initialized")
}
