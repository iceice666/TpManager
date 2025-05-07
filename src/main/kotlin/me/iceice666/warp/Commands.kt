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

package me.iceice666.warp

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.BoolArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.suggestion.Suggestions
import com.mojang.brigadier.suggestion.SuggestionsBuilder
import me.iceice666.getUsernameByUuid
import net.minecraft.registry.RegistryKey
import net.minecraft.registry.RegistryKeys
import net.minecraft.server.command.CommandManager.argument
import net.minecraft.server.command.CommandManager.literal
import net.minecraft.server.command.ServerCommandSource
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.text.ClickEvent
import net.minecraft.text.HoverEvent
import net.minecraft.text.MutableText
import net.minecraft.text.Text
import net.minecraft.util.Formatting
import java.util.concurrent.CompletableFuture

object Commands {
    private val dao = WarpPointDao()

    fun register(
        dispatcher: CommandDispatcher<ServerCommandSource>,
    ) {
        dispatcher.register(
            literal("warp")
                .requires { it.hasPermissionLevel(0) }
                .then(
                    argument("name", StringArgumentType.string())
                        .suggests { context, builder -> buildSuggestion(context, builder) }
                        .executes(::executeWarp)
                )
        )

        dispatcher.register(
            literal("warps")
                .requires { it.hasPermissionLevel(0) }
                .apply {
                    then(
                        literal("add")
                            .then(
                                argument("name", StringArgumentType.string())
                                    .then(
                                        argument("public", BoolArgumentType.bool())
                                            .executes(::executeAddWarp)
                                    )
                            )
                    )
                    then(
                        literal("remove")
                            .then(
                                argument("name", StringArgumentType.string())
                                    .executes(::executeRemoveWarp)
                            )
                    )
                    then(
                        literal("modify")
                            .then(
                                argument("name", StringArgumentType.string())
                                    .then(
                                        literal("isPublic")
                                            .then(
                                                argument("public", BoolArgumentType.bool())
                                                    .executes(::executeModifyIsPublic)
                                            )
                                    )
                                    .then(
                                        literal("name")
                                            .then(
                                                argument("newName", StringArgumentType.string())
                                                    .executes(::executeModifyName)
                                            )
                                    )
                                    .then(
                                        literal("position")
                                            .then(
                                                literal("setHere")
                                                    .executes(::executeModifyPosition)
                                            )
                                    )
                            )
                    )
                    then(literal("list").executes(::executeListWarps))
                })

    }

    private fun buildSuggestion(
        context: CommandContext<ServerCommandSource>,
        builder: SuggestionsBuilder
    ): CompletableFuture<Suggestions> {
        val player = context.source.playerOrThrow
        val server = player.server
        val playerWarps = dao.getAccessiblePoints(player.uuid).groupBy { it.owner }

        // First, suggest player's own warps with no prefix
        playerWarps[player.uuid]?.forEach {
            builder.suggest(it.name)
        }

        // Add suggestion for public warps format 
        playerWarps.values.flatten().filter { it.isPublic }.distinctBy { it.name }.forEach {
            builder.suggest("public:${it.name}")
        }

        // Add suggestions for other players' public warps
        playerWarps.entries.filter { it.key != player.uuid }.forEach { (ownerUuid, warps) ->
            val ownerName: String? = server.getUsernameByUuid(ownerUuid)




            if (ownerName != null) {
                warps.filter { it.isPublic }.forEach {
                    builder.suggest("$ownerName:${it.name}")
                }
            }
        }

        return builder.buildFuture()
    }

    private fun <T> withPlayerAndFeedback(
        ctx: CommandContext<ServerCommandSource>,
        action: (ServerPlayerEntity) -> T,
        successMessage: (T) -> Text,
        errorMessage: Text? = null
    ): Int {
        return try {
            val player = ctx.source.playerOrThrow
            val result = action(player)
            ctx.source.sendFeedback({ successMessage(result) }, false)
            1
        } catch (e: Exception) {
            if (errorMessage != null) ctx.source.sendError(errorMessage)
            0
        }
    }

    private fun executeWarp(ctx: CommandContext<ServerCommandSource>): Int {
        val nameArg = StringArgumentType.getString(ctx, "name")
        return withPlayerAndFeedback(
            ctx,
            { player ->
                // Parse the warp name to determine resolution strategy
                val (warp, feedbackText) = when {
                    // Format: public:<n> - search only public warps
                    nameArg.startsWith("public:") -> {
                        val warpName = nameArg.substringAfter("public:")
                        val warp = dao.getPublicWarps(warpName).firstOrNull()
                            ?: throw IllegalArgumentException("Public warp '$warpName' not found")
                        Pair(warp, "Warped to public warp '$warpName'")
                    }
                    // Format: <player_name>:<n> - search that player's public warps
                    nameArg.contains(":") -> {
                        val ownerName = nameArg.substringBefore(":")
                        val warpName = nameArg.substringAfter(":")
                        val warp = dao.getSpecificPlayerPublicWarps(ownerName, player.server, warpName).firstOrNull()
                            ?: throw IllegalArgumentException("Public warp '$warpName' from player '$ownerName' not found")
                        Pair(warp, "Warped to $ownerName's public warp '$warpName'")
                    }
                    // Format: <n> - search player's warps, then public
                    else -> {
                        // First try player's own warps
                        val ownWarp = dao.getPlayerWarps(player.uuid, nameArg).firstOrNull()
                        if (ownWarp != null) {
                            Pair(ownWarp, "Warped to your warp '$nameArg'")
                        } else {
                            // Then try public warps if none found
                            val publicWarp = dao.getPublicWarps(nameArg).firstOrNull()
                                ?: throw IllegalArgumentException("Warp '$nameArg' not found (checked both your warps and public warps)")
                            Pair(publicWarp, "Warped to public warp '$nameArg'")
                        }
                    }
                }

                val world = RegistryKey.of(RegistryKeys.WORLD, warp.dimension)
                player.teleport(
                    player.server.getWorld(world),
                    warp.position.x,
                    warp.position.y,
                    warp.position.z,
                    setOf(),
                    player.yaw,
                    player.pitch,
                    true
                )
                feedbackText
            },
            { Text.of(it) },
            Text.of("Warp '$nameArg' not found")
        )
    }

    private fun executeAddWarp(ctx: CommandContext<ServerCommandSource>): Int {
        val name = StringArgumentType.getString(ctx, "name")
        val isPublic = BoolArgumentType.getBool(ctx, "public")
        return withPlayerAndFeedback(
            ctx,
            { player ->
                val warp = WarpPoint(name, player.pos, player.world.registryKey.value, isPublic, player.uuid)
                dao.add(warp)
                name
            },
            { Text.of("Warp '$it' added.") }
        )
    }

    private fun executeRemoveWarp(ctx: CommandContext<ServerCommandSource>): Int {
        val name = StringArgumentType.getString(ctx, "name")
        return withPlayerAndFeedback(
            ctx,
            { player -> dao.remove(name, player.uuid); name },
            { Text.of("Warp '$it' removed.") }
        )
    }

    private fun executeModifyIsPublic(ctx: CommandContext<ServerCommandSource>): Int {
        val name = StringArgumentType.getString(ctx, "name")
        val isPublic = BoolArgumentType.getBool(ctx, "public")
        return withPlayerAndFeedback(
            ctx,
            { player ->
                val warp = dao.getAccessiblePoints(player.uuid, name).first()
                val updatedWarp = warp.copy(isPublic = isPublic)
                dao.update(updatedWarp)
                name
            },
            { Text.of("Warp '$it' is now ${if (isPublic) "public" else "private"}.") },
            Text.of("Warp '$name' not found.")
        )
    }

    private fun executeModifyName(ctx: CommandContext<ServerCommandSource>): Int {
        val oldName = StringArgumentType.getString(ctx, "name")
        val newName = StringArgumentType.getString(ctx, "newName")
        return withPlayerAndFeedback(
            ctx,
            { player ->
                val warp = dao.getAccessiblePoints(player.uuid, oldName).first()
                dao.remove(oldName, player.uuid)
                dao.add(warp.copy(name = newName))
                newName
            },
            { Text.of("Warp renamed to '$it'.") },
            Text.of("Warp '$oldName' not found.")
        )
    }

    private fun executeModifyPosition(ctx: CommandContext<ServerCommandSource>): Int {
        val name = StringArgumentType.getString(ctx, "name")
        return withPlayerAndFeedback(
            ctx,
            { player ->
                val warp = dao.getAccessiblePoints(player.uuid, name).first()
                val updatedWarp = warp.copy(position = player.pos, dimension = player.world.registryKey.value)
                dao.update(updatedWarp)
                name
            },
            { Text.of("Warp '$it' position updated.") },
            Text.of("Warp '$name' not found.")
        )
    }

    private fun executeListWarps(ctx: CommandContext<ServerCommandSource>): Int {
        return withPlayerAndFeedback(
            ctx,
            { player -> Pair(player, dao.getAccessiblePoints(player.uuid)) },
            { (player, warps) ->
                val publicPoints = warps.filter { it.isPublic }
                val privatePoints = warps.filter { it -> !it.isPublic && it.owner == player.uuid }
                val othersPublicPoints = warps.filter { it.isPublic && it.owner != player.uuid }

                val result = Text.empty()

                // Player's private warps section
                if (privatePoints.isNotEmpty()) {
                    result.append(Text.literal("===== Your Private Warps =====\n").styled { it.withBold(true) })
                    privatePoints.forEach { warp ->
                        appendWarpEntry(result, warp, player)
                    }
                    result.append(Text.literal("\n"))
                }

                // Player's public warps section
                val ownPublicPoints = publicPoints.filter { it.owner == player.uuid }
                if (ownPublicPoints.isNotEmpty()) {
                    result.append(Text.literal("===== Your Public Warps =====\n").styled { it.withBold(true) })
                    ownPublicPoints.forEach { warp ->
                        appendWarpEntry(result, warp, player)
                    }
                    result.append(Text.literal("\n"))
                }

                // Others' public warps section
                if (othersPublicPoints.isNotEmpty()) {
                    result.append(
                        Text.literal("===== Other Players' Public Warps =====\n").styled { it.withBold(true) })

                    // Group by owner for better organization
                    val byOwner = othersPublicPoints.groupBy { it.owner }
                    byOwner.forEach { (ownerUuid, ownerWarps) ->
                        val ownerName = player.server.getUsernameByUuid(ownerUuid)
                            ?: ownerUuid.toString().substring(0, 8)

                        result.append(Text.literal("$ownerName's warps:\n").styled { it.withItalic(true) })

                        ownerWarps.forEach { warp ->
                            appendWarpEntry(result, warp, player, includeOwnerActions = false)
                        }
                    }
                }

                if (warps.isEmpty()) {
                    result.append(
                        Text.literal("No warps available. Create one with /warps add <name> <public>")
                            .styled { it.withItalic(true) })
                }

                result
            }
        )
    }

    private fun appendWarpEntry(
        text: MutableText,
        warp: WarpPoint,
        player: ServerPlayerEntity,
        includeOwnerActions: Boolean = true
    ) {
        val isOwner = warp.owner == player.uuid
        val canModify = isOwner && includeOwnerActions

        // Name with teleport action
        text.append(
            Text.literal("• ${warp.name}").styled { style ->
                style.withClickEvent(ClickEvent.RunCommand("/warp ${warp.name}"))
                    .withHoverEvent(
                        HoverEvent.ShowText(
                            Text.literal("Click to teleport to ${warp.name}")
                        )
                    )
                    .withColor(if (warp.isPublic) Formatting.GREEN else Formatting.GOLD)
            }
        )

        // Position info
        val posText = String.format(
            " (%.1f, %.1f, %.1f) [%s]",
            warp.position.x, warp.position.y, warp.position.z,
            warp.dimension.path
        )
        text.append(Text.literal(posText).styled { it.withColor(Formatting.GRAY) })

        // Owner actions
        if (canModify) {
            // Remove button
            text.append(Text.literal(" [✕]").styled { style ->
                style.withClickEvent(ClickEvent.RunCommand("/warps remove ${warp.name}"))
                    .withHoverEvent(
                        HoverEvent.ShowText(
                            Text.literal("Remove this warp")
                        )
                    )
                    .withColor(Formatting.RED)
            })

            // Modify position button
            text.append(Text.literal(" [↓]").styled { style ->
                style.withClickEvent(ClickEvent.RunCommand("/warps modify ${warp.name} position setHere"))
                    .withHoverEvent(
                        HoverEvent.ShowText(
                            Text.literal("Update position to current location")
                        )
                    )
                    .withColor(Formatting.AQUA)
            })

            // Toggle visibility button
            val visibilityText = if (warp.isPublic) "[→🔒]" else "[→🔓]"
            val newVisibility = !warp.isPublic
            text.append(Text.literal(" $visibilityText").styled { style ->
                style.withClickEvent(
                    ClickEvent.RunCommand(

                        "/warps modify ${warp.name} isPublic $newVisibility"
                    )
                )
                    .withHoverEvent(
                        HoverEvent.ShowText(

                            Text.literal("Make ${if (newVisibility) "public" else "private"}")
                        )
                    )
                    .withColor(if (warp.isPublic) Formatting.GOLD else Formatting.GREEN)
            })

            // Rename button
            text.append(Text.literal(" [✎]").styled { style ->
                style.withClickEvent(ClickEvent.SuggestCommand("/warps modify ${warp.name} name "))
                    .withHoverEvent(
                        HoverEvent.ShowText(

                            Text.literal("Rename this warp")
                        )
                    )
                    .withColor(Formatting.YELLOW)
            })
        }

        text.append(Text.literal("\n"))
    }
}
