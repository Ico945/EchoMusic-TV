package com.kutv.music.direct

import java.util.Base64
import org.json.JSONObject
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class KugouDeviceRegistrar(
    private val deviceStore: KugouDeviceStore,
    private val executor: KugouRawRequestExecutor,
    private val aesKeyProvider: (() -> String)? = null,
) {
    private val registrationMutex = Mutex()

    suspend fun ensureRegistered(): KugouDeviceIdentity = registrationMutex.withLock {
        registerIfNeeded()
    }

    private suspend fun registerIfNeeded(): KugouDeviceIdentity {
        val identity = deviceStore.getOrCreate()
        if (!identity.dfid.isNullOrBlank()) return identity

        val payload = JSONObject()
            .put("availableRamSize", 4_983_533_568L)
            .put("availableRomSize", 48_114_719L)
            .put("availableSDSize", 48_114_717L)
            .put("basebandVer", "")
            .put("batteryLevel", 100)
            .put("batteryStatus", 3)
            .put("brand", "KUTV")
            .put("buildSerial", "unknown")
            .put("device", "kutv-tv")
            .put("imei", identity.guid)
            .put("imsi", "")
            .put("manufacturer", "KUTV")
            .put("uuid", identity.guid)
            .put("accelerometer", false)
            .put("accelerometerValue", "")
            .put("gravity", false)
            .put("gravityValue", "")
            .put("gyroscope", false)
            .put("gyroscopeValue", "")
            .put("light", false)
            .put("lightValue", "")
            .put("magnetic", false)
            .put("magneticValue", "")
            .put("orientation", false)
            .put("orientationValue", "")
            .put("pressure", false)
            .put("pressureValue", "")
            .put("step_counter", false)
            .put("step_counterValue", "")
            .put("temperature", false)
            .put("temperatureValue", "")
        val encrypted = aesKeyProvider?.let { KugouCrypto.playlistEncrypt(payload, it()) }
            ?: KugouCrypto.playlistEncrypt(payload)
        val p = KugouCrypto.rsaEncryptPkcs1(
            JSONObject()
                .put("aes", encrypted.key)
                .put("uid", 0)
                .put("token", "")
                .toString(),
            KugouCrypto.LITE_PUBLIC_RSA_KEY,
        ).uppercase()
        val raw = executor.executeRaw(
            KugouRequest(
                method = "POST",
                baseUrl = "https://userservice.kugou.com",
                path = "/risk/v2/r_register_dev",
                query = linkedMapOf("part" to 1, "platid" to 1, "p" to p),
                headers = mapOf("content-type" to "application/octet-stream"),
                body = encrypted.encoded,
                signMode = KugouSignMode.ANDROID,
            ),
        )
        if (raw.statusCode !in 200..299) {
            throw KugouNetworkException(KugouNetworkErrorKind.HTTP, raw.statusCode)
        }
        val encryptedBytes = raw.bodyBytes ?: raw.body.toByteArray(Charsets.ISO_8859_1)
        val decoded = runCatching {
            KugouCrypto.playlistDecrypt(
                PlaylistCiphertext(
                    key = encrypted.key,
                    encoded = Base64.getEncoder().encodeToString(encryptedBytes),
                ),
            ) as JSONObject
        }.getOrElse { throw KugouNetworkException(KugouNetworkErrorKind.DATA) }
        if (decoded.optInt("status", -1) != 1 || decoded.optInt("error_code", 0) != 0) {
            throw KugouNetworkException(KugouNetworkErrorKind.DATA)
        }
        val dfid = normalizedDfid(decoded.opt("dfid"))
            ?: normalizedDfid(decoded.optJSONObject("data")?.opt("dfid"))
            ?: throw KugouNetworkException(KugouNetworkErrorKind.DATA)
        deviceStore.updateDfid(dfid)
        return deviceStore.getOrCreate()
    }
}
