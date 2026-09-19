package com.aurora.app.database.repositories

import com.aurora.app.database.AuroraDatabase
import com.aurora.app.database.entities.FavoriteEntity

class FavoriteRepository(private val database: AuroraDatabase) {
    private val favoriteDao = database.favoriteDao()

    fun add(trackId: Long): Long {
        return favoriteDao.addFavorite(FavoriteEntity(trackId = trackId))
    }

    fun remove(trackId: Long): Int = favoriteDao.removeByTrackId(trackId)

    fun isFavorite(trackId: Long): Boolean = favoriteDao.getByTrackId(trackId) != null

    fun getAll(): List<FavoriteEntity> = favoriteDao.getAll()

    fun clearAll(): Int = favoriteDao.clearAll()
}
