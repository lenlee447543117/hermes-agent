package com.ailaohu.data.local.db

import androidx.room.*
import com.ailaohu.data.local.entity.ContactEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ContactDao {
    @Query("SELECT * FROM contacts WHERE isActive = 1 ORDER BY tileOrder ASC")
    fun getActiveContacts(): Flow<List<ContactEntity>>

    @Query("SELECT * FROM contacts WHERE id = :id")
    suspend fun getContactById(id: Long): ContactEntity?

    @Query("SELECT COUNT(*) FROM contacts WHERE isActive = 1")
    suspend fun getActiveContactCount(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertContact(contact: ContactEntity): Long

    @Update
    suspend fun updateContact(contact: ContactEntity)

    @Delete
    suspend fun deleteContact(contact: ContactEntity)

    @Query("UPDATE contacts SET isActive = 0 WHERE id = :id")
    suspend fun deactivateContact(id: Long)
}
