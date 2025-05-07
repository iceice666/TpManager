package me.iceice666

import net.minecraft.server.MinecraftServer
import java.util.UUID

fun MinecraftServer.getUsernameByUuid(uuid: UUID): String? =
    userCache?.getByUuid(uuid)?.orElse(null)?.name
        ?: playerManager.getPlayer(uuid)?.name?.string