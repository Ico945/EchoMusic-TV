package com.kutv.music.direct

import android.annotation.SuppressLint
import android.content.Context
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID

interface DirectKeyValueStore {
    fun get(key: String): String?
    fun put(key: String, value: String)
    fun remove(key: String)
}

class MemoryDirectKeyValueStore(
    private val values: MutableMap<String, String> = mutableMapOf(),
) : DirectKeyValueStore {
    override fun get(key: String): String? = values[key]
    override fun put(key: String, value: String) {
        values[key] = value
    }

    override fun remove(key: String) {
        values.remove(key)
    }
}

class SharedPreferencesDirectKeyValueStore(context: Context) : DirectKeyValueStore {
    private val preferences = context.applicationContext.getSharedPreferences(
        "kutv_kugou_device",
        Context.MODE_PRIVATE,
    )

    override fun get(key: String): String? = preferences.getString(key, null)

    @SuppressLint("UseKtx") // Device registration must not outpace durable identity storage.
    override fun put(key: String, value: String) {
        check(preferences.edit().putString(key, value).commit()) { "Unable to persist device identity" }
    }

    @SuppressLint("UseKtx") // Callers require an observable persistence result.
    override fun remove(key: String) {
        check(preferences.edit().remove(key).commit()) { "Unable to clear device identity" }
    }
}

class KugouDeviceStore(
    private val persistence: DirectKeyValueStore,
    private val seedGenerator: () -> String = ::secureSeed,
) {
    @Synchronized
    fun getOrCreate(): KugouDeviceIdentity {
        decode(persistence.get(KEY))?.let { existing ->
            val canonicalMid = md5(existing.guid)
            if (existing.mid == canonicalMid) return existing
            // Old builds generated MID independently from GUID. KuGou binds
            // DFID/MID during media URL checks, so migrate to calculateMid's
            // MD5(GUID) source and force one safe device re-registration.
            return existing.copy(mid = canonicalMid, dfid = null).also(::persist)
        }
        val seed = seedGenerator()
        require(seed.matches(Regex("^[a-fA-F0-9]{64}$"))) { "Device seed must be 64 hex chars" }
        return derive(seed.lowercase()).also(::persist)
    }

    @Synchronized
    fun updateDfid(dfid: String?) {
        val current = getOrCreate()
        val normalized = normalizedDfid(dfid)
        persist(current.copy(dfid = normalized))
    }

    private fun derive(seed: String): KugouDeviceIdentity {
        val guid = sha256("kugou:guid:$seed").take(32)
        return KugouDeviceIdentity(
            guid = guid,
            mid = md5(guid),
            uuid = UUID.nameUUIDFromBytes("kugou:uuid:$seed".toByteArray(Charsets.UTF_8)).toString(),
        )
    }

    private fun persist(identity: KugouDeviceIdentity) {
        persistence.put(
            KEY,
            JSONObject()
                .put("version", 1)
                .put("guid", identity.guid)
                .put("mid", identity.mid)
                .put("uuid", identity.uuid)
                .put("dfid", identity.dfid ?: JSONObject.NULL)
                .put("mac", KugouDeviceIdentity.PRIVACY_SAFE_MAC)
                .toString(),
        )
    }

    private fun decode(raw: String?): KugouDeviceIdentity? = runCatching {
        val json = JSONObject(raw ?: return null)
        val guid = json.getString("guid")
        val mid = json.getString("mid")
        val uuid = json.getString("uuid")
        require(guid.matches(Regex("^[a-f0-9]{32}$")))
        require(mid.matches(Regex("^[a-f0-9]{32}$")))
        require(uuid.isNotBlank())
        KugouDeviceIdentity(
            guid = guid,
            mid = mid,
            uuid = uuid,
            dfid = normalizedDfid(json.opt("dfid")),
        )
    }.getOrNull()

    private companion object {
        const val KEY = "kugou.device.v1"

        fun secureSeed(): String = ByteArray(32).also(SecureRandom()::nextBytes).toHex()

        fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .toHex()

        fun md5(value: String): String = MessageDigest.getInstance("MD5")
            .digest(value.toByteArray(Charsets.UTF_8))
            .toHex()

        fun ByteArray.toHex(): String = joinToString("") { byte ->
            "%02x".format(byte.toInt() and 0xff)
        }
    }
}
