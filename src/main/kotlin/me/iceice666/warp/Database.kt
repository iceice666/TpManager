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

import net.minecraft.server.MinecraftServer
import net.minecraft.util.math.Vec3d
import net.minecraft.util.Identifier
import java.util.UUID
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction

data class WarpPoint(
    val name: String,
    val position: Vec3d,
    val dimension: Identifier,
    val isPublic: Boolean,
    val owner: UUID
)

object WarpPoints : Table("warp_points") {
    val name = varchar("name", 50)
    val x = double("x")
    val y = double("y")
    val z = double("z")
    val dimension = varchar("dimension", 100)
    val isPublic = bool("is_public").index()
    val owner = uuid("owner").index()

    override val primaryKey = PrimaryKey(name, owner)
}

class WarpPointDao {
    private val dimensionCache = mutableMapOf<String, Identifier>()

    init {
        transaction {
            SchemaUtils.create(WarpPoints)
        }
    }

    fun add(warpPoint: WarpPoint) {
        transaction {
            WarpPoints.insert {
                it[name] = warpPoint.name
                it[x] = warpPoint.position.x
                it[y] = warpPoint.position.y
                it[z] = warpPoint.position.z
                it[dimension] = warpPoint.dimension.toString()
                it[isPublic] = warpPoint.isPublic
                it[owner] = warpPoint.owner
            }
        }
    }

    fun remove(name: String, owner: UUID) {
        transaction {
            WarpPoints.deleteWhere { (WarpPoints.name eq name) and (WarpPoints.owner eq owner) }
        }
    }



    fun getAccessiblePoints(owner: UUID): List<WarpPoint> {
        return transaction {
            WarpPoints.select((WarpPoints.owner eq owner) or WarpPoints.isPublic)
                .map { it.toWarpPoint() }
        }
    }

    fun getAccessiblePoints(owner: UUID, name: String): List<WarpPoint> {
        return transaction {
            WarpPoints.select((WarpPoints.name eq name) and((WarpPoints.owner eq owner) or WarpPoints.isPublic))
                .map { it.toWarpPoint() }
        }
    }
    
    fun getPlayerWarps(owner: UUID, name: String): List<WarpPoint> {
        return transaction {
            WarpPoints.select((WarpPoints.name eq name) and (WarpPoints.owner eq owner))
                .map { it.toWarpPoint() }
        }
    }
    
    fun getPublicWarps(name: String): List<WarpPoint> {
        return transaction {
            WarpPoints.select((WarpPoints.name eq name) and WarpPoints.isPublic)
                .map { it.toWarpPoint() }
        }
    }
    
    fun getSpecificPlayerPublicWarps(ownerName: String, server: MinecraftServer, name: String): List<WarpPoint> {
        val playerUUID = server.userCache?.findByName(ownerName)?.orElse(null)?.id ?: return emptyList()
        return transaction {
            WarpPoints.select((WarpPoints.name eq name) and (WarpPoints.owner eq playerUUID) and WarpPoints.isPublic)
                .map { it.toWarpPoint() }
        }
    }

    fun update(warpPoint: WarpPoint) {
        transaction {
            WarpPoints.update({ (WarpPoints.name eq warpPoint.name) and (WarpPoints.owner eq warpPoint.owner) }) {
                it[x] = warpPoint.position.x
                it[y] = warpPoint.position.y
                it[z] = warpPoint.position.z
                it[dimension] = warpPoint.dimension.toString()
                it[isPublic] = warpPoint.isPublic
            }
        }
    }

    private fun ResultRow.toWarpPoint() = WarpPoint(
        name = this[WarpPoints.name],
        position = Vec3d(this[WarpPoints.x], this[WarpPoints.y], this[WarpPoints.z]),
        dimension = dimensionCache.getOrPut(this[WarpPoints.dimension]) {
            Identifier.of(
                "minecraft",
                this[WarpPoints.dimension]
            )
        },
        isPublic = this[WarpPoints.isPublic],
        owner = this[WarpPoints.owner]
    )
}