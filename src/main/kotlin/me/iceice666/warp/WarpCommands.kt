package me.iceice666.warp

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.BoolArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.exceptions.CommandSyntaxException
import me.iceice666.logger
import net.minecraft.command.CommandRegistryAccess
import net.minecraft.server.command.CommandManager
import net.minecraft.server.command.ServerCommandSource
import net.minecraft.text.ClickEvent
import net.minecraft.text.Text
import net.minecraft.util.Formatting
import net.minecraft.util.Identifier
import java.util.*

/**
 * Manages all warp-related commands
 */
object WarpCommands {
    // Command success/failure constants
    private const val COMMAND_SUCCESS = 1
    private const val COMMAND_FAILURE = 0

    // Text formatting constants
    private val SUCCESS_FORMAT = Formatting.GREEN
    private val ERROR_FORMAT = Formatting.RED
    private val INFO_FORMAT = Formatting.YELLOW
    private val HEADER_FORMAT = Formatting.GOLD
    private const val PUBLIC_PREFIX = "global_"

    /**
     * Registers all warp commands
     */
    fun register(
        dispatcher: CommandDispatcher<ServerCommandSource>,
        registryAccess: CommandRegistryAccess,
        environment: CommandManager.RegistrationEnvironment
    ) {
        // Create a warp command
        val warpsCommand = CommandManager.literal("warps")
            .requires { it.hasPermissionLevel(0) } // Any player can use

        // /warps add <name> [public]
        warpsCommand.then(
            CommandManager.literal("add")
                .then(
                    CommandManager.argument("name", StringArgumentType.word())
                        .then(
                            CommandManager.argument("public", BoolArgumentType.bool())
                                .executes { context -> executeAddWarp(context, true) }
                        )
                        .executes { context -> executeAddWarp(context, false) }
                )
        )

        // /warps delete <name>
        warpsCommand.then(
            CommandManager.literal("delete")
                .then(
                    CommandManager.argument("name", StringArgumentType.word())
                        .executes(::executeDeleteWarp)
                )
        )

        // /warps modify <name> <name|set_public> <newValue>
        warpsCommand.then(
            CommandManager.literal("modify")
                .then(
                    CommandManager.argument("name", StringArgumentType.word())
                        .then(
                            CommandManager.literal("name")
                                .then(
                                    CommandManager.argument("newName", StringArgumentType.word())
                                        .executes { context -> executeModifyWarp(context, true) }
                                )
                        )
                        .then(
                            CommandManager.literal("set_public")
                                .then(
                                    CommandManager.argument("public", BoolArgumentType.bool())
                                        .executes { context -> executeModifyWarp(context, false) }
                                )
                        )
                )
        )

        // /warps list - show all accessible warps
        warpsCommand.then(
            CommandManager.literal("list")
                .executes(::executeListWarps)
        )

        // /warp <name> - direct teleport to warp
        dispatcher.register(
            CommandManager.literal("warp")
                .requires { it.hasPermissionLevel(0) }.then(
                    CommandManager.argument("name", StringArgumentType.word())
                        .suggests { context, builder ->
                            val player = try {
                                context.source.playerOrThrow // Ensures player exists
                            } catch (e: CommandSyntaxException) {
                                context.source.sendError("This command can only be executed by a player")
                                return@suggests builder.buildFuture()
                            }
                            val points = WarpModInitializer.getWarpManager().getAccessibleWarps(player.uuid)

                            points.forEach { point ->
                                builder.suggest(point.name)
                            }


                            builder.buildFuture()
                        }
                        .executes(::executeTeleport)
                )
        )

        dispatcher.register(warpsCommand)
        logger.info("Registered warp commands")
    }

    /**
     * Creates a new warp at player's location
     */
    private fun executeAddWarp(context: CommandContext<ServerCommandSource>, publicSpecified: Boolean): Int {
        val source = context.source
        val player = try {
            source.playerOrThrow // Ensures player exists
        } catch (e: CommandSyntaxException) {
            source.sendError("This command can only be executed by a player")
            return COMMAND_FAILURE
        }

        val name = StringArgumentType.getString(context, "name")
        val isPublic = if (publicSpecified) BoolArgumentType.getBool(context, "public") else false

        // Validate warp name
        if (!name.matches(Regex("^[a-zA-Z0-9_-]+$"))) {
            source.sendError("Warp name can only contain letters, numbers, underscores, and hyphens")
            return COMMAND_FAILURE
        }

        // Generate warp name with prefix if public
        val finalName = if (isPublic) "$PUBLIC_PREFIX$name" else name
        // Generate a unique ID based on player UUID and warp name
        val warpId = "${player.uuidAsString}_$finalName"
        val warpManager = WarpModInitializer.getWarpManager()

        // Check if player already has a warp with this name
        if (warpManager.hasWarpWithName(player.uuid, finalName)) {
            source.sendError("You already have a warp named '$finalName'")
            return COMMAND_FAILURE
        }

        // Check if any accessible warp has this name
        val existingWarp = warpManager.getAccessibleWarps(player.uuid)
            .find { it.name.equals(finalName, ignoreCase = true) }
        if (existingWarp != null) {
            source.sendError("A warp named '$finalName' already exists")
            return COMMAND_FAILURE
        }

        // Add the warp
        val success = warpManager.addWarp(
            warpId,
            player.uuid,
            player.x,
            player.y,
            player.z,
            player.world.registryKey,
            isPublic,
            finalName
        )

        return if (success) {
            val visibilityText = if (isPublic) "public" else "private"
            source.sendSuccess("Warp '$finalName' set as $visibilityText")
            COMMAND_SUCCESS
        } else {
            source.sendError("Failed to set warp '$finalName'")
            COMMAND_FAILURE
        }
    }

    /**
     * Deletes a warp owned by the player
     */
    private fun executeDeleteWarp(context: CommandContext<ServerCommandSource>): Int {
        val source = context.source
        val player = try {
            source.playerOrThrow
        } catch (e: CommandSyntaxException) {
            source.sendError("This command can only be executed by a player")
            return COMMAND_FAILURE
        }

        val name = StringArgumentType.getString(context, "name")

        // Find warp by name for this player
        val warpManager = WarpModInitializer.getWarpManager()
        val warpToDelete = warpManager.getPlayerWarps(player.uuid)
            .find { it.name.equals(name, ignoreCase = true) }
            ?: run {
                source.sendError("You don't have a warp named '$name'")
                return COMMAND_FAILURE
            }

        val success = warpManager.removeWarp(warpToDelete.id, player.uuid)

        return if (success) {
            source.sendSuccess("Warp '$name' deleted")
            COMMAND_SUCCESS
        } else {
            source.sendError("Failed to delete warp '$name'")
            COMMAND_FAILURE
        }
    }

    /**
     * Modifies a warp's name or visibility
     */
    private fun executeModifyWarp(context: CommandContext<ServerCommandSource>, isNameModification: Boolean): Int {
        val source = context.source
        val player = try {
            source.playerOrThrow
        } catch (e: CommandSyntaxException) {
            source.sendError("This command can only be executed by a player")
            return COMMAND_FAILURE
        }

        val name = StringArgumentType.getString(context, "name")
        val warpManager = WarpModInitializer.getWarpManager()
        val warp = warpManager.getPlayerWarps(player.uuid)
            .find { it.name.equals(name, ignoreCase = true) }
            ?: run {
                source.sendError("You don't have a warp named '$name'")
                return COMMAND_FAILURE
            }

        if (isNameModification) {
            val newName = StringArgumentType.getString(context, "newName")
            // Validate new warp name
            if (!newName.matches(Regex("^[a-zA-Z0-9_-]+$"))) {
                source.sendError("New warp name can only contain letters, numbers, underscores, and hyphens")
                return COMMAND_FAILURE
            }
            // Handle public prefix
            val finalNewName = if (warp.isPublic) {
                if (!warp.name.startsWith(PUBLIC_PREFIX)) "$PUBLIC_PREFIX$newName" else newName
            } else {
                newName
            }
            // Check if new name already exists
            if (warpManager.getAccessibleWarps(player.uuid)
                    .any { it.name.equals(finalNewName, ignoreCase = true) && it.id != warp.id }
            ) {
                source.sendError("A warp named '$finalNewName' already exists")
                return COMMAND_FAILURE
            }
            // Update warp with new name
            val updatedWarp = warp.copy(name = finalNewName)
            val success = warpManager.updateWarp(player.uuid, updatedWarp)
            return if (success) {
                source.sendSuccess("Warp '$name' renamed to '$finalNewName'")
                COMMAND_SUCCESS
            } else {
                source.sendError("Failed to rename warp to '$finalNewName'")
                COMMAND_FAILURE
            }
        } else {
            val newIsPublic = BoolArgumentType.getBool(context, "public")
            // If visibility hasn't changed, no update needed
            if (newIsPublic == warp.isPublic) {
                source.sendError("Warp '$name' is already ${if (newIsPublic) "public" else "private"}")
                return COMMAND_FAILURE
            }
            // Handle public prefix for visibility change
            val newName = if (newIsPublic) {
                if (!warp.name.startsWith(PUBLIC_PREFIX)) "$PUBLIC_PREFIX${warp.name}" else warp.name
            } else {
                if (warp.name.startsWith(PUBLIC_PREFIX)) warp.name.removePrefix(PUBLIC_PREFIX) else warp.name
            }
            // Check if new name already exists
            if (newName != warp.name && warpManager.getAccessibleWarps(player.uuid)
                    .any { it.name.equals(newName, ignoreCase = true) && it.id != warp.id }
            ) {
                source.sendError("A warp named '$newName' already exists")
                return COMMAND_FAILURE
            }
            // Update warp with new visibility and name
            val updatedWarp = warp.copy(isPublic = newIsPublic, name = newName)
            val success = warpManager.updateWarp(player.uuid, updatedWarp)
            return if (success) {
                source.sendSuccess("Warp '$name' is now ${if (newIsPublic) "public" else "private"}")
                COMMAND_SUCCESS
            } else {
                source.sendError("Failed to update warp '$name'")
                COMMAND_FAILURE
            }
        }
    }

    /**
     * Teleports player to a warp by name
     */
    private fun executeTeleport(context: CommandContext<ServerCommandSource>): Int {
        val source = context.source
        val player = try {
            source.playerOrThrow
        } catch (e: CommandSyntaxException) {
            source.sendError("This command can only be executed by a player")
            return COMMAND_FAILURE
        }

        val name = StringArgumentType.getString(context, "name")
        val warpManager = WarpModInitializer.getWarpManager()

        // First try player's own warps, then public warps
        val warpToTeleport = findWarpByPriority(player.uuid, name, warpManager)
            ?: run {
                source.sendError("Warp '$name' not found")
                return COMMAND_FAILURE
            }

        // Get the dimension and teleport the player
        val dimensionKey = warpToTeleport.dimension
        val command = buildTeleportCommand(source.name, dimensionKey, warpToTeleport)

        try {
            source.server.commandManager.dispatcher.execute(command, source.server.commandSource)
            source.sendSuccess("Teleported to warp '$name'")
            return COMMAND_SUCCESS
        } catch (e: Exception) {
            logger.error("Failed to execute teleport command: $command", e)
            source.sendError("Failed to teleport to warp '$name'")
            return COMMAND_FAILURE
        }
    }

    /**
     * Lists all warps accessible to the player with interactive options
     */
    private fun executeListWarps(context: CommandContext<ServerCommandSource>): Int {
        val source = context.source
        val player = try {
            source.playerOrThrow
        } catch (e: CommandSyntaxException) {
            source.sendError("This command can only be executed by a player")
            return COMMAND_FAILURE
        }

        val warpManager = WarpModInitializer.getWarpManager()
        val accessibleWarps = warpManager.getAccessibleWarps(player.uuid)

        if (accessibleWarps.isEmpty()) {
            source.sendInfo("You don't have any warps")
            return COMMAND_SUCCESS
        }

        val (yourWarps, otherWarps) = accessibleWarps.partition { it.owner == player.uuid }

        if (yourWarps.isNotEmpty()) {
            source.sendFeedback({ Text.literal("=== Your Warps ===").styled { it.withColor(HEADER_FORMAT) } }, false)
            yourWarps.forEach { warp ->
                val warpLine = buildWarpLine(warp, true, null)
                source.sendFeedback({ warpLine }, false)
            }
        }

        if (otherWarps.isNotEmpty()) {
            source.sendFeedback({ Text.literal("=== Public Warps ===").styled { it.withColor(HEADER_FORMAT) } }, false)
            otherWarps.forEach { warp ->
                val ownerName = source.server.userCache
                    ?.getByUuid(warp.owner)
                    ?.map { profile -> profile.name }
                    ?.orElse("Unknown") ?: "Unknown"
                val warpLine = buildWarpLine(warp, false, ownerName)
                source.sendFeedback({ warpLine }, false)
            }
        }

        return COMMAND_SUCCESS
    }

    /**
     * Builds an interactive text line for a warp
     */
    private fun buildWarpLine(warp: WarpPoint, isOwnWarp: Boolean, ownerName: String?): Text {
        val visibilityText = if (warp.isPublic) "[PUBLIC]" else "[PRIVATE]"
        val baseText = if (isOwnWarp) {
            Text.literal("${warp.name} $visibilityText at (${warp.x.toInt()}, ${warp.y.toInt()}, ${warp.z.toInt()}) in ${warp.dimension.path}\n")
        } else {
            Text.literal("${warp.name} by $ownerName at (${warp.x.toInt()}, ${warp.y.toInt()}, ${warp.z.toInt()}) in ${warp.dimension.path}\n")
        }

        val teleportText = Text.literal("[Teleport]")
            .styled {
                it.withColor(Formatting.GREEN)
                    .withClickEvent(ClickEvent.RunCommand("/warp ${warp.name}"))
            }

        if (isOwnWarp) {
            val removeText = Text.literal(" [Remove]")
                .styled {
                    it.withColor(Formatting.RED)
                        .withClickEvent(ClickEvent.RunCommand("/warps delete ${warp.name}"))
                }
            val modifyText = Text.literal(" [Modify]")
                .styled {
                    it.withColor(Formatting.BLUE)
                        .withClickEvent(ClickEvent.SuggestCommand("/warps modify ${warp.name}"))
                }
            return baseText.append(teleportText).append(removeText).append(modifyText)
        } else {
            return baseText.append(teleportText)
        }
    }

    /**
     * Find a warp by name with priority order: player's own warps first, then public warps
     */
    private fun findWarpByPriority(playerId: UUID, name: String, warpManager: WarpManager): WarpPoint? {
        val accessibleWarps = warpManager.getAccessibleWarps(playerId)

        // First try to find the player's own warp
        return accessibleWarps.find { it.owner == playerId && it.name.equals(name, ignoreCase = true) }
            ?: accessibleWarps.find { it.isPublic && it.name.equals(name, ignoreCase = true) }
    }

    /**
     * Builds a command string for teleporting to a warp
     */
    private fun buildTeleportCommand(playerName: String, dimension: Identifier, warp: WarpPoint): String =
        "execute in $dimension run tp $playerName ${warp.x} ${warp.y} ${warp.z}"

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
}