package cn.daka.local

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class Config(context: Context) {
    private val prefs = context.getSharedPreferences("config", Context.MODE_PRIVATE)
    var enabled: Boolean get() = prefs.getBoolean("enabled", false); set(v) { prefs.edit().putBoolean("enabled", v).commit() }
    var revision: Long get() = prefs.getLong("revision", 0); set(v) { prefs.edit().putLong("revision", v).commit() }
    fun text(key: String, default: String = "") = prefs.getString(key, default) ?: default
    fun set(key: String, value: String) { prefs.edit().putString(key, value).commit() }
    var device: String get() = text("device", "honor8-main"); set(v) = set("device", v)
    val target get() = text("target")
    val organization get() = text("organization")
    val repo get() = text("repo", "sdhgdsfdusjdfs/daka")
    val branch get() = text("branch", "device-logs")
    fun plans(): List<Plan> {
        val raw = text("plans")
        if (raw.isBlank()) return Times.defaults()
        val a = JSONArray(raw)
        return (0 until a.length()).map { i -> a.getJSONObject(i).let { Plan(it.getString("id"), it.getString("name"), it.getInt("minute"), it.getString("kind"), it.getInt("days"), it.getBoolean("enabled")) } }
    }
    fun savePlans(plans: List<Plan>) {
        val a = JSONArray()
        plans.forEach { a.put(JSONObject().put("id", it.id).put("name", it.name).put("minute", it.minute).put("kind", it.kind).put("days", it.days).put("enabled", it.enabled)) }
        set("plans", a.toString()); revision++
    }
    fun rules(): List<DateRule> {
        val a = JSONArray(text("rules", "[]"))
        return (0 until a.length()).map { i -> a.getJSONObject(i).let { r -> val ids = r.getJSONArray("ids"); DateRule(r.getString("date"), (0 until ids.length()).map { ids.getString(it) }.toSet()) } }
    }
    fun saveRules(rules: List<DateRule>) {
        val a = JSONArray(); rules.forEach { a.put(JSONObject().put("date", it.date).put("ids", JSONArray(it.planIds.toList()))) }
        set("rules", a.toString()); revision++
    }
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("daka-local", null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("daka-local", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    @Synchronized fun secret(name: String): String {
        val raw = text("secret-$name")
        if (raw.isBlank()) return ""
        val parts = raw.split(":")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)))
        return String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), Charsets.UTF_8)
    }
    @Synchronized fun saveSecret(name: String, value: String) {
        if (value.isEmpty()) { set("secret-$name", ""); return }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        set("secret-$name", Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(cipher.doFinal(value.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP))
    }
}
