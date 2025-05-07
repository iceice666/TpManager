package me.iceice666.warp

import com.mojang.brigadier.arguments.BoolArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.suggestion.Suggestions
import com.mojang.brigadier.suggestion.SuggestionsBuilder
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.minecraft.registry.RegistryKey
import net.minecraft.registry.RegistryKeys
import net.minecraft.server.command.CommandManager.argument
import net.minecraft.server.command.CommandManager.literal
import net.minecraft.server.command.ServerCommandSource
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.text.Text
import java.util.concurrent.CompletableFuture

object WarpCommands {
    private val dao = WarpPointDao()

    fun register() {
        CommandRegistrationCallback.EVENT.register { dispatcher, _, _ ->
            dispatcher.register(
                literal("warp")
                    .then(
                        argument("name", StringArgumentType.string())
                            .suggests { context, builder -> buildSuggestion(context, builder) }
                            .executes(::executeWarp)
                    )
            )

            dispatcher.register(literal("warps").apply {
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
            val ownerName = server.userCache.getByUuid(ownerUuid)?.profile?.name
                ?: server.playerManager.getPlayer(ownerUuid)?.name
            
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
                val (warp, warpName, feedbackText) = when {
                    // Format: public:<n> - search only public warps
                    nameArg.startsWith("public:") -> {
                        val warpName = nameArg.substringAfter("public:")
                        val warp = dao.getPublicWarps(warpName).firstOrNull()
                            ?: throw IllegalArgumentException("Public warp '$warpName' not found")
                        Triple(warp, nameArg, "Warped to public warp '$warpName'")
                    }
                    // Format: <player_name>:<n> - search that player's public warps
                    nameArg.contains(":") -> {
                        val ownerName = nameArg.substringBefore(":")
                        val warpName = nameArg.substringAfter(":")
                        val warp = dao.getSpecificPlayerPublicWarps(ownerName, player.server, warpName).firstOrNull()
                            ?: throw IllegalArgumentException("Public warp '$warpName' from player '$ownerName' not found")
                        Triple(warp, nameArg, "Warped to $ownerName's public warp '$warpName'")
                    }
                    // Format: <n> - search player's warps, then public
                    else -> {
                        // First try player's own warps
                        val ownWarp = dao.getPlayerWarps(player.uuid, nameArg).firstOrNull()
                        if (ownWarp != null) {
                            Triple(ownWarp, nameArg, "Warped to your warp '$nameArg'")
                        } else {
                            // Then try public warps if none found
                            val publicWarp = dao.getPublicWarps(nameArg).firstOrNull()
                                ?: throw IllegalArgumentException("Warp '$nameArg' not found (checked both your warps and public warps)")
                            Triple(publicWarp, nameArg, "Warped to public warp '$nameArg'")
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
            { player -> dao.getAccessiblePoints(player.uuid) },
            { warps ->
                val publicPoints = warps.filter { it -> it.isPublic }
                val privatePoints = warps.filter { it -> !it.isPublic }

                var result = Text.literal("")

                result.append(Text.literal("===== Your Warps =====\n"))



                result

            }
        )
    }
}
