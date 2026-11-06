package dev.nodi.android

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Device-scoped credential, encrypted by a non-exportable Android Keystore key. */
object NodiInternetStore {
    private const val ALIAS="dev.nodi.internet.v1"
    private const val PREFIX="nodi-internet1-"
    private fun file(c:Context)=AtomicFile(File(c.noBackupFilesDir,"internet-v1.enc"))
    private fun key():SecretKey {
        val store=KeyStore.getInstance("AndroidKeyStore").apply{load(null)}
        (store.getKey(ALIAS,null) as? SecretKey)?.let{return it}
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply{
            init(KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    @Synchronized fun activate(c:Context,code:String) {
        require(code.length<2048 && code.startsWith(PREFIX)){"Неправильний код інтернет-доступу"}
        val config=JSONObject(String(Base64.getUrlDecoder().decode(code.removePrefix(PREFIX)),Charsets.UTF_8))
        val pin=config.getString("fingerprint");val token=config.getString("token")
        require(pin.matches(Regex("[a-f0-9]{64}"))&&token.matches(Regex("[a-f0-9]{64}"))){"Неправильний код інтернет-доступу"}
        NodiIdentity.create(persistent=true).use{identity->
            val own=MessageDigest.getInstance("SHA-256").digest(identity.certificate.encoded).hex()
            require(own==pin){"Цей код виданий іншому пристрою"}
        }
        val cipher=Cipher.getInstance("AES/GCM/NoPadding").apply{init(Cipher.ENCRYPT_MODE,key())}
        val bytes=cipher.iv+cipher.doFinal(config.toString().toByteArray(Charsets.UTF_8))
        val f=file(c);val out=f.startWrite()
        try{out.write(bytes);f.finishWrite(out)}catch(e:Exception){f.failWrite(out);throw e}
    }
    @Synchronized fun client(c:Context):NodiInternet?=try{
        val bytes=file(c).readFully();require(bytes.size in 29..4096)
        val cipher=Cipher.getInstance("AES/GCM/NoPadding").apply{init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,bytes.copyOfRange(0,12)))}
        val config=JSONObject(String(cipher.doFinal(bytes.copyOfRange(12,bytes.size)),Charsets.UTF_8))
        NodiIdentity.create(persistent=true).use{identity->require(MessageDigest.getInstance("SHA-256").digest(identity.certificate.encoded).hex()==config.getString("fingerprint"))}
        NodiInternet(config.getString("token"))
    }catch(_:Exception){null}
    @Synchronized fun ensure(c:Context):NodiInternet {
        client(c)?.let{return it}
        NodiIdentity.create(persistent=true).use { identity ->
            val pin=MessageDigest.getInstance("SHA-256").digest(identity.certificate.encoded).hex()
            val api=NodiInternet("")
            val nonce=api.api("POST","/v1/enroll/challenge",JSONObject().put("id",pin)).getString("nonce")
            val signature=java.security.Signature.getInstance("SHA256withECDSA").apply{
                initSign(identity.key);update("nodi-enroll-v1\n$pin\n$nonce".toByteArray(Charsets.UTF_8))
            }.sign()
            val result=api.api("POST","/v1/enroll",JSONObject().put("id",pin).put("nonce",nonce)
                .put("certificate",Base64.getEncoder().encodeToString(identity.certificate.encoded))
                .put("signature",Base64.getEncoder().encodeToString(signature)).put("testInstall",BuildConfig.NODI_TEST_INSTALL))
            val config=JSONObject().put("fingerprint",pin).put("token",result.getString("token"))
            activate(c,PREFIX+Base64.getUrlEncoder().withoutPadding().encodeToString(config.toString().toByteArray(Charsets.UTF_8)))
        }
        return client(c) ?: error("Не вдалося зберегти інтернет-доступ")
    }
    @Synchronized fun clear(c:Context){file(c).delete()}
}
