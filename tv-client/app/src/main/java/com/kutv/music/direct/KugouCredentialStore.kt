package com.kutv.music.direct

import android.annotation.SuppressLint
import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONObject

interface KugouCredentialStore {
    fun loadCredentials(): KugouCredentials?
    fun loadAccount(): KugouAccount?
    fun save(credentials: KugouCredentials, account: KugouAccount? = null)
    fun saveAccount(account: KugouAccount)
    fun clear()
}

class MemoryKugouCredentialStore : KugouCredentialStore {
    private var credentials: KugouCredentials? = null
    private var account: KugouAccount? = null

    override fun loadCredentials(): KugouCredentials? = credentials
    override fun loadAccount(): KugouAccount? = account?.withPrivacySafeNickname()
    override fun save(credentials: KugouCredentials, account: KugouAccount?) {
        this.credentials = credentials
        this.account = account?.withPrivacySafeNickname()
    }

    override fun saveAccount(account: KugouAccount) {
        this.account = account.withPrivacySafeNickname()
    }

    override fun clear() {
        credentials = null
        account = null
    }
}

class EncryptedKugouCredentialStore(context: Context) : KugouCredentialStore {
    private val preferences = EncryptedSharedPreferences.create(
        context.applicationContext,
        FILE,
        MasterKey.Builder(context.applicationContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    override fun loadCredentials(): KugouCredentials? = loadEnvelope()?.credentials

    override fun loadAccount(): KugouAccount? = loadEnvelope()?.account

    override fun save(credentials: KugouCredentials, account: KugouAccount?) {
        writeEnvelope(CredentialEnvelope(credentials, account))
    }

    override fun saveAccount(account: KugouAccount) {
        val current = loadEnvelope() ?: return
        writeEnvelope(current.copy(account = account))
    }

    @SuppressLint("UseKtx") // commit() failure must be observable for credential durability.
    override fun clear() {
        check(preferences.edit().remove(KEY_ENVELOPE).commit()) { "Unable to clear KuGou credentials" }
    }

    @SuppressLint("UseKtx") // apply() is intentional for disposable corrupt data.
    private fun loadEnvelope(): CredentialEnvelope? {
        val raw = preferences.getString(KEY_ENVELOPE, null) ?: return null
        return runCatching { decode(raw) }.getOrElse {
            // apply() updates the in-memory view immediately; a corrupt value
            // does not require the durability guarantee used for valid auth.
            preferences.edit().remove(KEY_ENVELOPE).apply()
            null
        }
    }

    @SuppressLint("UseKtx") // commit() failure must be observable before auth success returns.
    private fun writeEnvelope(envelope: CredentialEnvelope) {
        check(preferences.edit().putString(KEY_ENVELOPE, encode(envelope)).commit()) {
            "Unable to persist KuGou credentials"
        }
    }

    private fun encode(envelope: CredentialEnvelope): String = JSONObject().apply {
        put("version", 1)
        put("credentials", JSONObject().apply {
            put("userid", envelope.credentials.userId)
            put("token", envelope.credentials.token)
            put("vip_token", envelope.credentials.vipToken ?: JSONObject.NULL)
            put("vip_type", envelope.credentials.vipType)
        })
        put("account", envelope.account?.let { account ->
            JSONObject().apply {
                put("userid", account.userId)
                put("nickname", account.nickname)
                put("avatar", account.avatarUrl ?: JSONObject.NULL)
                put("membership", account.membership)
            }
        } ?: JSONObject.NULL)
    }.toString()

    private fun decode(raw: String): CredentialEnvelope {
        val json = JSONObject(raw)
        require(json.getInt("version") == 1)
        val auth = json.getJSONObject("credentials")
        val credentials = requireNotNull(parseKugouCredentials(auth)) { "Incomplete stored credentials" }
        val account = json.optJSONObject("account")?.let { value ->
            KugouAccount(
                userId = value.getString("userid"),
                nickname = value.getString("nickname"),
                avatarUrl = kugouJsonText(value.opt("avatar")),
                membership = value.optString("membership", "none"),
            ).withPrivacySafeNickname()
        }
        return CredentialEnvelope(credentials, account)
    }

    private data class CredentialEnvelope(
        val credentials: KugouCredentials,
        val account: KugouAccount?,
    )

    private companion object {
        const val FILE = "kutv_kugou_credentials"
        const val KEY_ENVELOPE = "credentials.v1"
    }
}
