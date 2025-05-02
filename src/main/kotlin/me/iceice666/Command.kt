package me.iceice666

import com.mojang.brigadier.CommandDispatcher
import net.minecraft.server.command.CommandManager
import net.minecraft.server.command.ServerCommandSource

object Command {

    fun register(dispatcher: CommandDispatcher<ServerCommandSource>) {
        val warpCommand = CommandManager.literal("warp")

    }
}