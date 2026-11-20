package com.music.zenith.data.tidal

import android.content.Context
import android.content.SharedPreferences
import com.music.zenith.auth.EncryptedPrefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The signed-in Tidal account's tokens, in their own encrypted file — kept out
 * of backups and of the settings export, like [com.music.zenith.auth.AuthStore].
 */
object TidalTokenStore {
    private const val KEY_ACCESS = "access"
    private const val KEY_REFRESH = "refresh"
    private const val KEY_EXPIRES = "expires_at"
    private const val KEY_USER = "user_id"
    private const val KEY_COUNTRY = "country"

    private var prefs: SharedPreferences? = null

    /** Held in memory as well, so tests and the first request after sign-in need no disk. */
    @Volatile private var cached: TidalTokens? = null

    private val _signedIn = MutableStateFlow(false)
    val signedIn: StateFlow<Boolean> = _signedIn.asStateFlow()

    fun init(context: Context) {
        val p = EncryptedPrefs.open(context, "zenith_tidal", "zenith_tidal_plain")
        prefs = p
        cached = p.getString(KEY_ACCESS, null)?.let { access ->
            TidalTokens(
                access = access,
                refresh = p.getString(KEY_REFRESH, null) ?: return@let null,
                expiresAtMs = p.getLong(KEY_EXPIRES, 0L),
                userId = p.getLong(KEY_USER, -1L).takeIf { it >= 0 },
                countryCode = p.getString(KEY_COUNTRY, null),
            )
        }
        _signedIn.value = cached != null
    }

    fun load(): TidalTokens? = cached

    @Synchronized
    fun save(tokens: TidalTokens) {
        // A refresh answer carries no user; keep the one sign-in gave.
        val merged = cached?.let {
            tokens.copy(userId = tokens.userId ?: it.userId, countryCode = tokens.countryCode ?: it.countryCode)
        } ?: tokens
        cached = merged
        prefs?.edit()
            ?.putString(KEY_ACCESS, merged.access)
            ?.putString(KEY_REFRESH, merged.refresh)
            ?.putLong(KEY_EXPIRES, merged.expiresAtMs)
            ?.putLong(KEY_USER, merged.userId ?: -1L)
            ?.putString(KEY_COUNTRY, merged.countryCode)
            ?.apply()
        _signedIn.value = true
    }

    /** [save], but only while [old] is still the stored sign-in; false when it was replaced or cleared. */
    @Synchronized
    fun replaceIf(old: TidalTokens, new: TidalTokens): Boolean {
        if (cached?.refresh != old.refresh) return false
        save(new)
        return true
    }

    @Synchronized
    fun clear() {
        cached = null
        prefs?.edit()?.clear()?.apply()
        _signedIn.value = false
    }
}
