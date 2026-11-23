package dev.nodi.android

import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Room code is an out-of-band encryption key; only encrypted peers reach relay. */
class NodiRooms(private val client:NodiInternet) {
    companion object {
        private const val alphabet="ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        fun normalize(raw:String):String=raw.trim().uppercase().replace("-","").replace(" ","").also{require(it.length==16&&it.all{c->c in alphabet}){"Код кімнати має містити 16 символів"}}
        private fun digest(s:String)=MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
        fun id(code:String)=digest("nodi-room-id-v1\n$code").hex()
        fun encode(code:String,peer:JSONObject):String {
            val cipher=Cipher.getInstance("AES/GCM/NoPadding")
            val nonce=ByteArray(12).also{SecureRandom().nextBytes(it)}
            cipher.init(Cipher.ENCRYPT_MODE,SecretKeySpec(digest("nodi-room-key-v1\n$code"),"AES"),GCMParameterSpec(128,nonce))
            cipher.updateAAD(id(code).toByteArray(Charsets.UTF_8))
            return Base64.getEncoder().encodeToString(nonce+cipher.doFinal(peer.toString().toByteArray(Charsets.UTF_8)))
        }
        fun decode(code:String,blob:String):JSONObject {
            val bytes=Base64.getDecoder().decode(blob);require(bytes.size>=28)
            val cipher=Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE,SecretKeySpec(digest("nodi-room-key-v1\n$code"),"AES"),GCMParameterSpec(128,bytes.copyOfRange(0,12)))
            cipher.updateAAD(id(code).toByteArray(Charsets.UTF_8))
            return JSONObject(String(cipher.doFinal(bytes.copyOfRange(12,bytes.size)),Charsets.UTF_8))
        }
    }
    private fun delete(id:String){try{client.api("DELETE","/v1/rooms/$id")}catch(_:Exception){}}
    fun host(own:JSONObject,onCode:(String)->Unit,approve:(String,String)->Boolean,save:(TrustedDevice)->Unit) {
        val random=SecureRandom();val code=(1..16).map{alphabet[random.nextInt(32)]}.joinToString("")
        val token=ByteArray(32).also{random.nextBytes(it)}.hex();own.put("token",token)
        val id=id(code);client.api("POST","/v1/rooms",JSONObject().put("id",id).put("blob",encode(code,own)))
        var success=false
        try {
            val display=code.chunked(4).joinToString("-");onCode(display)
            val until=System.nanoTime()+java.util.concurrent.TimeUnit.MINUTES.toNanos(5)
            while(System.nanoTime()<until){
                val r=client.api("GET","/v1/rooms/$id")
                if(r.optString("guest").isNotEmpty()) {
                    val peer=decode(code,r.getString("blob"));require(peer.getString("fingerprint")==r.getString("guest"))
                    require(approve(peer.getString("name"),display)){"Підключення скасовано"}
                    save(TrustedDevice(peer.getString("name"),"192.0.2.1:1",peer.getString("fingerprint"),token))
                    client.api("POST","/v1/rooms/$id/approve",JSONObject());success=true;return
                }
                Thread.sleep(2000)
            }
            error("Код кімнати прострочений")
        }finally{if(!success)delete(id)}
    }
    fun join(raw:String,own:JSONObject,save:(TrustedDevice)->Unit) {
        val code=normalize(raw);val id=id(code)
        val joined=client.api("POST","/v1/rooms/join",JSONObject().put("id",id).put("blob",encode(code,own)))
        try {
            val until=System.nanoTime()+java.util.concurrent.TimeUnit.MINUTES.toNanos(5)
            while(System.nanoTime()<until){
                val r=client.api("GET","/v1/rooms/$id")
                if(r.getBoolean("approved")) {
                    val p=decode(code,r.getString("blob"));require(p.getString("fingerprint")==joined.getString("host")&&p.getString("fingerprint")==r.getString("host"))
                    save(TrustedDevice(p.getString("name"),"192.0.2.1:1",p.getString("fingerprint"),p.getString("token")));return
                }
                Thread.sleep(2000)
            }
            error("Код кімнати прострочений")
        }finally{delete(id)}
    }
}
