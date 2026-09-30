package dev.sopho.scale.data

import android.content.Context
import dev.sopho.scale.ble.normalizeMac
import dev.sopho.scale.ble.parseBindKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Persists the Xiaomi bind key and a couple of preferences.
 *
 * The bind key is the secret Xiaomi derives per device; without it the S200's advertisements cannot
 * be decrypted, so it is the one piece of configuration this app genuinely needs.
 */
class SettingsStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _bindKeyText = MutableStateFlow(prefs.getString(KEY_BIND_KEY, "").orEmpty())
    val bindKeyText: StateFlow<String> = _bindKeyText.asStateFlow()

    private val _macText = MutableStateFlow(prefs.getString(KEY_MAC, "").orEmpty())
    val macText: StateFlow<String> = _macText.asStateFlow()

    private val _autoSave = MutableStateFlow(prefs.getBoolean(KEY_AUTO_SAVE, true))
    val autoSave: StateFlow<Boolean> = _autoSave.asStateFlow()

    /** The parsed key, or null when nothing valid is stored yet. */
    fun bindKey(): ByteArray? = parseBindKey(_bindKeyText.value)

    /**
     * Optional, user-supplied identity address. Used as an extra candidate when working out the
     * CCM nonce, in case Android reports a randomised address for the scale.
     */
    fun configuredMac(): String? = normalizeMac(_macText.value)

    /** True when a key is present and well formed. */
    val hasValidKey: Boolean get() = bindKey() != null

    fun saveBindKey(text: String) {
        val normalised = text.trim()
        prefs.edit().putString(KEY_BIND_KEY, normalised).apply()
        _bindKeyText.value = normalised
    }

    fun saveDeviceMac(text: String) {
        val normalised = text.trim()
        prefs.edit().putString(KEY_MAC, normalised).apply()
        _macText.value = normalised
    }

    fun setAutoSave(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_SAVE, enabled).apply()
        _autoSave.value = enabled
    }

    private companion object {
        const val KEY_BIND_KEY = "bind_key"
        const val KEY_MAC = "device_mac"
        const val KEY_AUTO_SAVE = "auto_save"
    }
}
