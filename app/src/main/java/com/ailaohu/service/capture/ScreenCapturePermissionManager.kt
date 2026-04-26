package com.ailaohu.service.capture

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import javax.inject.Inject
import javax.inject.Singleton

private val Context.screenCaptureDataStore: DataStore<Preferences> by preferencesDataStore(name = "screen_capture_prefs")

@Singleton
class ScreenCapturePermissionManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "ScreenCapturePermMgr"
    }

    private object Keys {
        val HAS_PERMISSION = booleanPreferencesKey("has_screen_capture_permission")
        val LAST_RESULT_CODE = intPreferencesKey("last_screen_capture_result_code")
    }

    val hasPermission: Flow<Boolean> = context.screenCaptureDataStore.data.map { 
        it[Keys.HAS_PERMISSION] ?: false 
    }

    fun hasPermissionSync(): Boolean {
        return runBlocking {
            context.screenCaptureDataStore.data.map { it[Keys.HAS_PERMISSION] ?: false }.first()
        }
    }

    suspend fun setPermissionGranted(resultCode: Int) {
        context.screenCaptureDataStore.edit { prefs ->
            prefs[Keys.HAS_PERMISSION] = true
            prefs[Keys.LAST_RESULT_CODE] = resultCode
        }
    }

    suspend fun clearPermission() {
        context.screenCaptureDataStore.edit { prefs ->
            prefs[Keys.HAS_PERMISSION] = false
            prefs.remove(Keys.LAST_RESULT_CODE)
        }
    }

    fun requestPermission(activity: Activity, requestCode: Int) {
        val mediaProjectionManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        activity.startActivityForResult(mediaProjectionManager.createScreenCaptureIntent(), requestCode)
    }

    fun handlePermissionResult(requestCode: Int, resultCode: Int, data: Intent?, expectedRequestCode: Int, onGranted: (suspend () -> Unit)? = null) {
        if (requestCode == expectedRequestCode && resultCode == Activity.RESULT_OK && data != null) {
            runBlocking {
                setPermissionGranted(resultCode)
                onGranted?.invoke()
            }
        }
    }
}
