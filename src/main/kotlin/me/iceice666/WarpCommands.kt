package me.iceice666

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.BoolArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.context.CommandContext
import net.minecraft.command.CommandRegistryAccess
import net.minecraft.server.command.CommandManager
import net.minecraft.server.command.ServerCommandSource
import net.minecraft.text.Text
import net.minecraft.util.Formatting
import java.util.Optional

object WarpCommands {
    fun register(
        dispatcher: CommandDispatcher<ServerCommandSource>,
        registryAccess: CommandRegistryAccess,
        environment: CommandManager.RegistrationEnvironment
    ) {
        // Create a warp command
        val warpCommand = CommandManager.literal("warp")
            .requires { it.hasPermissionLevel(0) }

        // /warp set <name> [public]
        warpCommand.then(
            CommandManager.literal("set")
                .then(
                    CommandManager.argument("name", StringArgumentType.word())
                        .then(
                            CommandManager.argument("public", BoolArgumentType.bool())
                                .executes { context -> executeSetWarp(context, true) }
                        )
                        .executes { context -> executeSetWarp(context, false) }
                )
        )

        // /warp delete <name>
        warpCommand.then(
            CommandManager.literal("delete")
                .then(
                    CommandManager.argument("name", StringArgumentType.word())
                        .executes { context -> executeDeleteWarp(context) }
                )
        )

        // /warp <name>
        warpCommand.then(
            CommandManager.argument("name", StringArgumentType.word())
                .executes { context -> executeTeleport(context) }
        )

        // /warp list
        warpCommand.then(
            CommandManager.literal("list")
                .executes { context -> executeListWarps(context) }
        )

        dispatcher.register(warpCommand)
    }

    private fun executeSetWarp(context: CommandContext<ServerCommandSource>, publicSpecified: Boolean): Int {
        val source = context.source
        val player = source.player ?: return 0
        val name = StringArgumentType.getString(context, "name")
        val isPublic = if (publicSpecified) BoolArgumentType.getBool(context, "public") else false

        // Generate a unique ID based on player UUID and warp name
        val warpId = "${player.uuidAsString}_$name"

        val warpManager = WarpModInitializer.getWarpManager()

        // Check if player already has a warp with this name
        if (warpManager.hasWarpWithName(player.uuid, name)) {
            source.sendFeedback(
                { Text.literal("You already have a warp named '$name'").formatted(Formatting.RED) },
                false
            )
            return 0
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
            name
        )

        if (success) {
            val visibilityText = if (isPublic) "public" else "private"
            source.sendFeedback(
                { Text.literal("Warp '$name' set as $visibilityText").formatted(Formatting.GREEN) },
                false
            )
            return 1
        } else {
            source.sendFeedback(
                { Text.literal("Failed to set warp '$name'").formatted(Formatting.RED) },
                false
            )
            return 0
        }
    }

    private fun executeDeleteWarp(context: CommandContext<ServerCommandSource>): Int {
        val source = context.source
        val player = source.player ?: return 0
        val name = StringArgumentType.getString(context, "name")

        // Find warp by name for this player
        val warpManager = WarpModInitializer.getWarpManager()
        val playerWarps = warpManager.getPlayerWarps(player.uuid)
        val warpToDelete = playerWarps.find { it.name.equals(name, ignoreCase = true) }

        if (warpToDelete == null) {
            source.sendFeedback(
                { Text.literal("You don't have a warp named '$name'").formatted(Formatting.RED) },
                false
            )
            return 0
        }

        val success = warpManager.removeWarp(warpToDelete.id, player.uuid)

        if (success) {
            source.sendFeedback(
                { Text.literal("Warp '$name' deleted").formatted(Formatting.GREEN) },
                false
            )
            return 1
        } else {
            source.sendFeedback(
                { Text.literal("Failed to delete warp '$name'").formatted(Formatting.RED) },
                false
            )
            return 0
        }
    }

    private fun executeTeleport(context: CommandContext<ServerCommandSource>): Int {
        val source = context.source
        val player = source.player ?: return 0
        val name = StringArgumentType.getString(context, "name")

        val warpManager = WarpModInitializer.getWarpManager()
        val accessibleWarps = warpManager.getAccessibleWarps(player.uuid)

        // First try to find the player's own warp
        var warpToTeleport = accessibleWarps.find {
            it.owner == player.uuid && it.name.equals(name, ignoreCase = true)
        }

        // If not found, look for public warps with this name
        if (warpToTeleport == null) {
            warpToTeleport = accessibleWarps.find {
                it.isPublic && it.name.equals(name, ignoreCase = true)
            }
        }

        if (warpToTeleport == null) {
            source.sendFeedback(
                { Text.literal("Warp '$name' not found").formatted(Formatting.RED) },
                false
            )
            return 0
        }

        // Get the dimension
        val dimensionKey = warpToTeleport.dimension


        // Teleport the player
        val command =
            "execute in $dimensionKey at ${source.name} run tp ${warpToTeleport.x} ${warpToTeleport.y} ${warpToTeleport.z}"
        source.server.commandManager.dispatcher.execute(command, source)


        source.sendFeedback(
            { Text.literal("Teleported to warp '$name'").formatted(Formatting.GREEN) },
            false
        )

        return 1
    }

    private fun executeListWarps(context: CommandContext<ServerCommandSource>): Int {
        val source = context.source
        val player = source.player ?: return 0

        val warpManager = WarpModInitializer.getWarpManager()
        val accessibleWarps = warpManager.getAccessibleWarps(player.uuid)

        if (accessibleWarps.isEmpty()) {
            source.sendFeedback(
                { Text.literal("You don't have any warps").formatted(Formatting.YELLOW) },
                false
            )
            return 0
        }

        // Group warps by owner (your own vs others)
        val yourWarps = accessibleWarps.filter { it.owner == player.uuid }
        val otherWarps = accessibleWarps.filter { it.owner != player.uuid }

        source.sendFeedback(
            { Text.literal("=== Your Warps ===").formatted(Formatting.GOLD) },
            false
        )

        yourWarps.forEach { warp ->
            val visibilityText = if (warp.isPublic) "[PUBLIC]" else "[PRIVATE]"
            source.sendFeedback(
                {
                    Text.literal("$visibilityText ${warp.name} - ${warp.dimension} at (${warp.x.toInt()}, ${warp.y.toInt()}, ${warp.z.toInt()})")
                        .formatted(Formatting.YELLOW)
                },
                false
            )
        }

        if (otherWarps.isNotEmpty()) {
            source.sendFeedback(
                { Text.literal("=== Public Warps ===").formatted(Formatting.GOLD) },
                false
            )

            otherWarps.forEach { warp ->
                val ownerName =
                    (source.server.userCache?.getByUuid(warp.owner) ?: Optional.empty())
                        .map { profile -> profile.name }
                        .orElse("Unknown")
                source.sendFeedback(
                    {
                        Text.literal("${warp.name} (by $ownerName) - ${warp.dimension} at (${warp.x.toInt()}, ${warp.y.toInt()}, ${warp.z.toInt()})")
                            .formatted(Formatting.YELLOW)
                    },
                    false
                )
            }
        }

        return 1
    }
}