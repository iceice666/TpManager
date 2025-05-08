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

package me.iceice666.tp

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.exceptions.CommandSyntaxException
import me.iceice666.Config
import me.iceice666.logger
import net.minecraft.command.argument.EntityArgumentType
import net.minecraft.server.command.CommandManager
import net.minecraft.server.command.ServerCommandSource
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.text.ClickEvent
import net.minecraft.text.Text
import net.minecraft.util.Formatting

/**
 * Manages teleport-related commands (/tpa, /tph, /tpaccept, /tpdecline, /tpm)
 */
object TpmCommands {
    // Command success/failure constants
    private const val COMMAND_SUCCESS = 1
    private const val COMMAND_FAILURE = 0

    // Text formatting constants
    private val SUCCESS_FORMAT = Formatting.GREEN
    private val ERROR_FORMAT = Formatting.RED
    private val INFO_FORMAT = Formatting.YELLOW
    private val HEADER_FORMAT = Formatting.GOLD

    /**
     * Registers all teleport commands
     */
    fun register(
        dispatcher: CommandDispatcher<ServerCommandSource>,
    ) {
        // /tpa <player> - request to teleport to player
        dispatcher.register(
            CommandManager.literal("tpa")
                .requires { it.hasPermissionLevel(0) } // Any player can use
                .then(
                    CommandManager.argument("player", EntityArgumentType.player())
                        .executes { context -> executeTeleportToPlayer(context) }
                )
        )

        // /tph <player> - request player to teleport to you
        dispatcher.register(
            CommandManager.literal("tph")
                .requires { it.hasPermissionLevel(0) } // Any player can use
                .then(
                    CommandManager.argument("player", EntityArgumentType.player())
                        .executes { context -> executeTeleportHere(context) }
                )
        )

        // /tpaccept [player] - accept teleport request
        dispatcher.register(
            CommandManager.literal("tpaccept")
                .requires { it.hasPermissionLevel(0) } // Any player can use
                .then(
                    CommandManager.argument("player", EntityArgumentType.player())
                        .executes { context -> executeTpAccept(context, true) }
                )
                .executes { context -> executeTpAccept(context, false) }
        )

        // /tpdecline [player] - decline teleport request
        dispatcher.register(
            CommandManager.literal("tpdecline")
                .requires { it.hasPermissionLevel(0) } // Any player can use
                .then(
                    CommandManager.argument("player", EntityArgumentType.player())
                        .executes { context -> executeTpDecline(context, true) }
                )
                .executes { context -> executeTpDecline(context, false) }
        )

        // /tpm rule <auto_accept|auto_decline|ask>
        val tpmCommand = CommandManager.literal("tpm")
            .requires { it.hasPermissionLevel(0) } // Any player can use
            .then(
                CommandManager.literal("rule")
                    .then(
                        CommandManager.literal("auto_accept")
                            .executes { context -> executeSetPreference(context, TeleportPreference.AUTO_ACCEPT) }
                    )
                    .then(
                        CommandManager.literal("auto_decline")
                            .executes { context -> executeSetPreference(context, TeleportPreference.AUTO_DECLINE) }
                    )
                    .then(
                        CommandManager.literal("ask")
                            .executes { context -> executeSetPreference(context, TeleportPreference.ASK) }
                    )
                    .executes { context -> executeShowPreference(context) }
            )
            
        // /tpm config - show current configuration
        tpmCommand.then(
            CommandManager.literal("config")
                .executes { context -> executeShowConfig(context) }
        )
            
        // /tpm reload - reload configuration (op only)
        tpmCommand.then(
            CommandManager.literal("reload")
                .requires { it.hasPermissionLevel(2) } // Only operators can use
                .executes { context -> executeReloadConfig(context) }
        )

        // /tpm cooldown - check remaining cooldown time
        tpmCommand.then(
            CommandManager.literal("cooldown")
                .executes { context -> executeCheckCooldown(context) }
        )

        // /tpm sent - show sent teleport requests
        tpmCommand.then(
            CommandManager.literal("sent")
                .executes { context -> executeShowSentRequests(context) }
        )

        // /tpm cancel <player> - cancel a sent teleport request
        tpmCommand.then(
            CommandManager.literal("cancel")
                .then(
                    CommandManager.argument("player", EntityArgumentType.player())
                        .executes { context -> executeCancelRequest(context) }
                )
                .executes { context -> executeShowSentRequests(context) }
        )

        dispatcher.register(tpmCommand)
        logger.info("Registered teleport commands")
    }

    /**
     * Handles /tpa command - request to teleport to another player
     */
    private fun executeTeleportToPlayer(context: CommandContext<ServerCommandSource>): Int {
        val source = context.source
        val player = try {
            source.playerOrThrow
        } catch (e: CommandSyntaxException) {
            source.sendError("This command can only be executed by a player")
            return COMMAND_FAILURE
        }

        val targetPlayer = EntityArgumentType.getPlayer(context, "player")

        // Cannot teleport to self
        if (player.uuid == targetPlayer.uuid) {
            source.sendError("You cannot teleport to yourself")
            return COMMAND_FAILURE
        }

        // Create the teleport request (tpa = teleport TO target)
        val teleportManager = TeleportManagerInitializer.getTeleportManager()
        return when (teleportManager.createRequest(player.uuid, targetPlayer.uuid, false)) {
            RequestResult.CREATED -> {
                source.sendSuccess("Teleport request sent to ${targetPlayer.name.string}")

                // Send notification to target player with accept/decline buttons
                sendRequestNotification(targetPlayer, player.name.string, false)
                COMMAND_SUCCESS
            }

            RequestResult.ALREADY_REQUESTED -> {
                source.sendError("You already have a pending request to ${targetPlayer.name.string}")
                COMMAND_FAILURE
            }

            RequestResult.AUTO_ACCEPTED -> {
                source.sendSuccess("Your teleport request was automatically accepted by ${targetPlayer.name.string}")
                COMMAND_SUCCESS
            }

            RequestResult.AUTO_DECLINED -> {
                source.sendError("Your teleport request was automatically declined by ${targetPlayer.name.string}")
                COMMAND_FAILURE
            }

            RequestResult.PLAYER_NOT_FOUND -> {
                source.sendError("Player not found")
                COMMAND_FAILURE
            }

        }
    }

    /**
     * Handles /tph command - request another player to teleport to you
     */
    private fun executeTeleportHere(context: CommandContext<ServerCommandSource>): Int {
        val source = context.source
        val player = try {
            source.playerOrThrow
        } catch (e: CommandSyntaxException) {
            source.sendError("This command can only be executed by a player")
            return COMMAND_FAILURE
        }

        val targetPlayer = EntityArgumentType.getPlayer(context, "player")

        // Cannot teleport self to self
        if (player.uuid == targetPlayer.uuid) {
            source.sendError("You cannot teleport yourself to yourself")
            return COMMAND_FAILURE
        }

        // Create the teleport request (tph = teleport here, thus it's true)
        val teleportManager = TeleportManagerInitializer.getTeleportManager()
        return when (teleportManager.createRequest(player.uuid, targetPlayer.uuid, true)) {
            RequestResult.CREATED -> {
                source.sendSuccess("Teleport here request sent to ${targetPlayer.name.string}")

                // Send notification to target player with accept/decline buttons
                sendRequestNotification(targetPlayer, player.name.string, true)
                COMMAND_SUCCESS
            }

            RequestResult.ALREADY_REQUESTED -> {
                source.sendError("You already have a pending request to ${targetPlayer.name.string}")
                COMMAND_FAILURE
            }

            RequestResult.AUTO_ACCEPTED -> {
                source.sendSuccess("Your teleport here request was automatically accepted by ${targetPlayer.name.string}")
                COMMAND_SUCCESS
            }

            RequestResult.AUTO_DECLINED -> {
                source.sendError("Your teleport here request was automatically declined by ${targetPlayer.name.string}")
                COMMAND_FAILURE
            }

            RequestResult.PLAYER_NOT_FOUND -> {
                source.sendError("Player not found")
                COMMAND_FAILURE
            }

        }
    }

    /**
     * Handles /tpaccept command - accept a teleport request
     */
    private fun executeTpAccept(context: CommandContext<ServerCommandSource>, specificPlayer: Boolean): Int {
        val source = context.source
        val player = try {
            source.playerOrThrow
        } catch (e: CommandSyntaxException) {
            source.sendError("This command can only be executed by a player")
            return COMMAND_FAILURE
        }

        val teleportManager = TeleportManagerInitializer.getTeleportManager()

        // If no pending requests
        if (!teleportManager.hasPendingRequests(player.uuid)) {
            source.sendError("You don't have any pending teleport requests")
            return COMMAND_FAILURE
        }

        if (specificPlayer) {
            // Accept request from specific player
            val requesterPlayer = EntityArgumentType.getPlayer(context, "player")

            val success = teleportManager.acceptRequest(player.uuid, requesterPlayer.uuid)
            return if (success) {
                source.sendSuccess("Accepted teleport request from ${requesterPlayer.name.string}")
                COMMAND_SUCCESS
            } else {
                source.sendError("No pending request from ${requesterPlayer.name.string}")
                COMMAND_FAILURE
            }
        } else {
            // Accept the most recent request
            val requests = teleportManager.getPendingRequests(player.uuid)
            if (requests.isEmpty()) {
                source.sendError("You don't have any pending teleport requests")
                return COMMAND_FAILURE
            }

            // Get the most recent request (highest expiration time)
            val (requesterId, _) = requests.maxByOrNull { it.value.first } ?: return COMMAND_FAILURE
            val requesterPlayer = source.server.playerManager.getPlayer(requesterId)
                ?: run {
                    source.sendError("The requesting player is no longer online")
                    return COMMAND_FAILURE
                }

            val success = teleportManager.acceptRequest(player.uuid, requesterId)
            return if (success) {
                source.sendSuccess("Accepted teleport request from ${requesterPlayer.name.string}")
                COMMAND_SUCCESS
            } else {
                source.sendError("Failed to accept teleport request")
                COMMAND_FAILURE
            }
        }
    }

    /**
     * Handles /tpdecline command - decline a teleport request
     */
    private fun executeTpDecline(context: CommandContext<ServerCommandSource>, specificPlayer: Boolean): Int {
        val source = context.source
        val player = try {
            source.playerOrThrow
        } catch (e: CommandSyntaxException) {
            source.sendError("This command can only be executed by a player")
            return COMMAND_FAILURE
        }

        val teleportManager = TeleportManagerInitializer.getTeleportManager()

        // If no pending requests
        if (!teleportManager.hasPendingRequests(player.uuid)) {
            source.sendError("You don't have any pending teleport requests")
            return COMMAND_FAILURE
        }

        if (specificPlayer) {
            // Decline request from specific player
            val requesterPlayer = EntityArgumentType.getPlayer(context, "player")

            val success = teleportManager.declineRequest(player.uuid, requesterPlayer.uuid)
            return if (success) {
                source.sendSuccess("Declined teleport request from ${requesterPlayer.name.string}")
                requesterPlayer.sendMessage(
                    Text.literal("${player.name.string} declined your teleport request").formatted(Formatting.RED)
                )
                COMMAND_SUCCESS
            } else {
                source.sendError("No pending request from ${requesterPlayer.name.string}")
                COMMAND_FAILURE
            }
        } else {
            // Decline the most recent request
            val requests = teleportManager.getPendingRequests(player.uuid)
            if (requests.isEmpty()) {
                source.sendError("You don't have any pending teleport requests")
                return COMMAND_FAILURE
            }

            // Get the most recent request (highest expiration time)
            val (requesterId, _) = requests.maxByOrNull { it.value.first } ?: return COMMAND_FAILURE
            val requesterPlayer = source.server.playerManager.getPlayer(requesterId)
                ?: run {
                    source.sendError("The requesting player is no longer online")
                    return COMMAND_FAILURE
                }

            val success = teleportManager.declineRequest(player.uuid, requesterId)
            return if (success) {
                source.sendSuccess("Declined teleport request from ${requesterPlayer.name.string}")
                requesterPlayer.sendMessage(
                    Text.literal("${player.name.string} declined your teleport request").formatted(Formatting.RED)
                )
                COMMAND_SUCCESS
            } else {
                source.sendError("Failed to decline teleport request")
                COMMAND_FAILURE
            }
        }
    }

    /**
     * Handles /tpm rule <preference> - sets teleport preferences
     */
    private fun executeSetPreference(
        context: CommandContext<ServerCommandSource>,
        preference: TeleportPreference
    ): Int {
        val source = context.source
        val player = try {
            source.playerOrThrow
        } catch (e: CommandSyntaxException) {
            source.sendError("This command can only be executed by a player")
            return COMMAND_FAILURE
        }

        val teleportManager = TeleportManagerInitializer.getTeleportManager()
        teleportManager.setPreference(player.uuid, preference)

        val preferenceText = when (preference) {
            TeleportPreference.ASK -> "ask for confirmation"
            TeleportPreference.AUTO_ACCEPT -> "automatically accept"
            TeleportPreference.AUTO_DECLINE -> "automatically decline"
        }

        source.sendSuccess("Teleport preference set to: $preferenceText")
        return COMMAND_SUCCESS
    }

    /**
     * Handles /tpm rule (without args) - shows current teleport preference
     */
    private fun executeShowPreference(context: CommandContext<ServerCommandSource>): Int {
        val source = context.source
        val player = try {
            source.playerOrThrow
        } catch (e: CommandSyntaxException) {
            source.sendError("This command can only be executed by a player")
            return COMMAND_FAILURE
        }

        val teleportManager = TeleportManagerInitializer.getTeleportManager()
        val preference = teleportManager.getPreference(player.uuid)

        val preferenceText = when (preference) {
            TeleportPreference.ASK -> "ask for confirmation"
            TeleportPreference.AUTO_ACCEPT -> "automatically accept"
            TeleportPreference.AUTO_DECLINE -> "automatically decline"
        }

        source.sendHeader("Current teleport preference: $preferenceText")

        // Send clickable options
        val options = Text.literal("Change to: ")
            .formatted(Formatting.GRAY)

        val askOption = Text.literal("[Ask] ")
            .styled { style ->
                style.withColor(Formatting.GREEN)
                    .withClickEvent(ClickEvent.SuggestCommand("/tpm rule ask"))
            }

        val acceptOption = Text.literal("[Auto-Accept] ")
            .styled { style ->
                style.withColor(Formatting.BLUE)
                    .withClickEvent(ClickEvent.SuggestCommand("/tpm rule auto_accept"))
            }

        val declineOption = Text.literal("[Auto-Decline]")
            .styled { style ->
                style.withColor(Formatting.RED)
                    .withClickEvent(ClickEvent.SuggestCommand("/tpm rule auto_decline"))
            }

        source.sendFeedback({ options.append(askOption).append(acceptOption).append(declineOption) }, false)
        return COMMAND_SUCCESS
    }

    /**
     * Sends a teleport request notification to a player with accept/decline buttons
     */
    private fun sendRequestNotification(
        targetPlayer: ServerPlayerEntity,
        requesterName: String,
        isTeleportHere: Boolean
    ) {
        val requestType = if (isTeleportHere) "teleport to them" else "teleport to you"

        val message = Text.literal("$requesterName has requested to $requestType.\n")
            .formatted(Formatting.YELLOW)

        val acceptButton = Text.literal("[Accept] ")
            .styled { style ->
                style.withColor(Formatting.GREEN)
                    .withClickEvent(ClickEvent.RunCommand("/tpaccept $requesterName"))
            }

        val declineButton = Text.literal("[Decline]")
            .styled { style ->
                style.withColor(Formatting.RED)
                    .withClickEvent(ClickEvent.RunCommand("/tpdecline $requesterName"))
            }

        targetPlayer.sendMessage(message.append(acceptButton).append(declineButton))
    }

    /**
     * Send a success message to the command source
     */
    private fun ServerCommandSource.sendSuccess(message: String) {
        this.sendFeedback({ Text.literal(message).formatted(SUCCESS_FORMAT) }, false)
    }

    /**
     * Send an error message to the command source
     */
    private fun ServerCommandSource.sendError(message: String) {
        this.sendFeedback({ Text.literal(message).formatted(ERROR_FORMAT) }, false)
    }

    /**
     * Handles /tpm cooldown - checks teleport cooldown status
     */
    private fun executeCheckCooldown(context: CommandContext<ServerCommandSource>): Int {
        val source = context.source
        val player = try {
            source.playerOrThrow
        } catch (e: CommandSyntaxException) {
            source.sendError("This command can only be executed by a player")
            return COMMAND_FAILURE
        }

        val teleportManager = TeleportManagerInitializer.getTeleportManager()
        val remainingCooldown = teleportManager.getRemainingCooldown(player.uuid)

        if (remainingCooldown <= 0) {
            source.sendSuccess("You can teleport now")
        } else {
            source.sendInfo("You cannot teleport for another $remainingCooldown seconds")
        }

        return COMMAND_SUCCESS
    }

    /**
     * Handles /tpm sent - shows pending sent teleport requests
     */
    private fun executeShowSentRequests(context: CommandContext<ServerCommandSource>): Int {
        val source = context.source
        val player = try {
            source.playerOrThrow
        } catch (e: CommandSyntaxException) {
            source.sendError("This command can only be executed by a player")
            return COMMAND_FAILURE
        }

        val teleportManager = TeleportManagerInitializer.getTeleportManager()
        val sentRequests = teleportManager.getSentRequests(player.uuid)

        if (sentRequests.isEmpty()) {
            source.sendInfo("You don't have any pending sent teleport requests")
            return COMMAND_SUCCESS
        }

        source.sendHeader("Your Pending Sent Teleport Requests")

        sentRequests.forEach { (targetId, requestData) ->
            val (expirationTime, isTeleportHere) = requestData
            val targetPlayer = source.server.playerManager.getPlayer(targetId)
            if (targetPlayer != null) {
                val requestType = if (isTeleportHere) "teleport to you" else "teleport to them"
                val remainingTime = ((expirationTime - System.currentTimeMillis()) / 1000).toInt()

                // Base message text
                val message =
                    Text.literal("To ${targetPlayer.name.string} ($requestType) - Expires in $remainingTime seconds ")
                        .formatted(INFO_FORMAT)

                // Cancel button
                val cancelButton = Text.literal("[Cancel]")
                    .styled { style ->
                        style.withColor(Formatting.RED)
                            .withClickEvent(ClickEvent.RunCommand("/tpm cancel ${targetPlayer.name.string}"))
                    }

                source.sendFeedback({ message.append(cancelButton) }, false)
            }
        }

        return COMMAND_SUCCESS
    }

    /**
     * Handles /tpm cancel <player> - cancels a sent teleport request
     */
    private fun executeCancelRequest(context: CommandContext<ServerCommandSource>): Int {
        val source = context.source
        val player = try {
            source.playerOrThrow
        } catch (e: CommandSyntaxException) {
            source.sendError("This command can only be executed by a player")
            return COMMAND_FAILURE
        }

        val targetPlayer = EntityArgumentType.getPlayer(context, "player")
        val teleportManager = TeleportManagerInitializer.getTeleportManager()

        val success = teleportManager.cancelRequest(player.uuid, targetPlayer.uuid)

        return if (success) {
            source.sendSuccess("Cancelled teleport request to ${targetPlayer.name.string}")
            targetPlayer.sendMessage(
                Text.literal("${player.name.string} cancelled their teleport request").formatted(Formatting.YELLOW)
            )
            COMMAND_SUCCESS
        } else {
            source.sendError("You don't have a pending request to ${targetPlayer.name.string}")
            COMMAND_FAILURE
        }
    }

    /**
     * Send an info message to the command source
     */
    private fun ServerCommandSource.sendInfo(message: String) {
        this.sendFeedback({ Text.literal(message).formatted(INFO_FORMAT) }, false)
    }

    /**
     * Send a header message to the command source
     */
    private fun ServerCommandSource.sendHeader(message: String) {
        this.sendFeedback({ Text.literal(message).formatted(HEADER_FORMAT) }, false)
    }
    
    /**
     * Handles /tpm config - show current configuration
     */
    private fun executeShowConfig(context: CommandContext<ServerCommandSource>): Int {
        val source = context.source
        val config = Config.Companion.get()
        
        source.sendHeader("TpManager Configuration")
        source.sendInfo("Request Expiration Time: ${config.requestExpirationTimeSeconds} seconds")
        source.sendInfo("Teleport Cooldown: ${config.teleportCooldownSeconds} seconds")
        source.sendInfo("Safety Check: ${if (config.enableSafetyCheck) "Enabled" else "Disabled"}")
        
        // Add reload button for operators
        if (source.hasPermissionLevel(2)) {
            val reloadButton = Text.literal("\n[Reload Configuration]")
                .styled { style ->
                    style.withColor(Formatting.GOLD)
                        .withClickEvent(ClickEvent.RunCommand("/tpm reload"))
                }
            source.sendFeedback({ reloadButton }, false)
        }
        
        return COMMAND_SUCCESS
    }
    
    /**
     * Handles /tpm reload - reload configuration
     */
    private fun executeReloadConfig(context: CommandContext<ServerCommandSource>): Int {
        val source = context.source
        
        // Reload configuration
        try {
            Config.Companion.reload()
            TeleportManagerInitializer.getTeleportManager().reloadConfig()
            
            source.sendSuccess("TpManager configuration reloaded successfully")
            
            // Send updated config button
            val configButton = Text.literal("[View Updated Configuration]")
                .styled { style ->
                    style.withColor(Formatting.GREEN)
                        .withClickEvent(ClickEvent.RunCommand("/tpm config"))
                }
            source.sendFeedback({ configButton }, false)
            
            return COMMAND_SUCCESS
        } catch (e: Exception) {
            logger.error("Failed to reload TpManager configuration", e)
            source.sendError("Failed to reload configuration: ${e.message}")
            return COMMAND_FAILURE
        }
    }
}