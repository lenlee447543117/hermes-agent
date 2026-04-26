package com.ailaohu.data.repository

import com.ailaohu.data.local.db.ContactDao
import com.ailaohu.data.local.entity.ContactEntity
import com.ailaohu.util.Constants
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ContactRepository @Inject constructor(private val contactDao: ContactDao) {

    fun getActiveContacts(): Flow<List<ContactEntity>> = contactDao.getActiveContacts()
    suspend fun getContactById(id: Long): ContactEntity? = contactDao.getContactById(id)
    suspend fun canAddMore(): Boolean = contactDao.getActiveContactCount() < Constants.MAX_CONTACT_TILES

    suspend fun addContact(contact: ContactEntity): Long {
        return contactDao.insertContact(contact)
    }

    suspend fun updateContact(contact: ContactEntity) {
        contactDao.updateContact(contact.copy(updatedAt = System.currentTimeMillis()))
    }

    suspend fun removeContact(contact: ContactEntity) {
        contactDao.deactivateContact(contact.id)
    }
}
