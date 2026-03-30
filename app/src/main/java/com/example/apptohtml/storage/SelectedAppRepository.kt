package com.example.apptohtml.storage

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.apptohtml.model.SelectedAppRef
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

private const val DATASTORE_NAME = "app_to_html_store"
private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = DATASTORE_NAME)

class SelectedAppRepository(private val context: Context) {
    private val selectedAppKey = stringPreferencesKey("selected_app")

    val selectedAppFlow: Flow<SelectedAppRef?> = context.dataStore.data
        .catch {
            if (it is IOException) emit(emptyPreferences()) else throw it
        }
        .map { prefs ->
            prefs[selectedAppKey]?.let(SelectedAppRefCodec::decode)
        }

    suspend fun saveSelectedApp(appRef: SelectedAppRef) {
        context.dataStore.edit { prefs ->
            prefs[selectedAppKey] = SelectedAppRefCodec.encode(appRef)
        }
    }

    suspend fun clearSelectedApp() {
        context.dataStore.edit { prefs ->
            prefs.remove(selectedAppKey)
        }
    }
}
