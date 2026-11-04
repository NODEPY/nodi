package dev.nodi.android

import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean

/** Outer WSS transport only. Nodi's pinned TLS runs inside the returned socket. */
class NodiInternet(private val token: String, private val base: String = SERVER, private val diagnostic:(String)->Unit = {}) {
    companion object { const val SERVER = "https://nodi-relay.igor-shura2011faik.workers.dev" }
    private val http = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .connectTimeout(10,TimeUnit.SECONDS).readTimeout(30,TimeUnit.SECONDS).build()
    fun api(method:String,path:String,data:JSONObject?=null):JSONObject {
        val body = if(method in listOf("POST","PUT")) (data?.toString() ?: "").toRequestBody("application/json".toMediaType()) else null
        val req=Request.Builder().url(base+path).header("Authorization","Bearer $token").method(method,body).build()
        http.newCall(req).execute().use { r ->
            if(!r.isSuccessful) throw IOException("Інтернет Nodi: HTTP ${r.code}")
            val bytes=r.body?.byteStream()?.readBytesLimited(65536) ?: byteArrayOf()
            return if(bytes.isEmpty()) JSONObject() else JSONObject(String(bytes,Charsets.UTF_8))
        }
    }
    private fun java.io.InputStream.readBytesLimited(max:Int):ByteArray {
        val out=java.io.ByteArrayOutputStream();val b=ByteArray(4096)
        while(true){val n=read(b);if(n<0)break;if(out.size()+n>max)throw IOException("Relay response too large");out.write(b,0,n)}
        return out.toByteArray()
    }
    fun dial(peer:String, checkActive:()->Unit = {}):Socket {
        api("PUT","/v1/peers/$peer")
        val session=api("POST","/v1/sessions",JSONObject().put("peer",peer))
        val listener=ServerSocket(0,1,InetAddress.getByName("127.0.0.1"))
        var client:Socket?=null;var bridge:Socket?=null
        try {
            client=Socket("127.0.0.1",listener.localPort);bridge=listener.accept()
            tunnel(session,bridge,checkActive);return client
        }catch(e:Exception){client?.close();bridge?.close();cancel(session);throw e}
        finally {listener.close()}
    }
    private fun cancel(session:JSONObject){try{api("DELETE","/v1/sessions/"+session.getString("id"))}catch(_:Exception){}}
    private fun tunnel(session:JSONObject,socket:Socket,checkActive:()->Unit = {}) {
        val ready=CountDownLatch(1);val closed=AtomicBoolean(false)
        val inbox=ArrayBlockingQueue<ByteArray>(1);val ack=Semaphore(0)
        var ws:WebSocket?=null
        fun stop(){if(closed.compareAndSet(false,true)){try{socket.close()}catch(_:Exception){};ws?.cancel();ready.countDown();ack.release()}}
        ws=http.newWebSocket(Request.Builder().url(base.replaceFirst("https:","wss:")+"/v1/sessions/"+session.getString("id"))
            .header("Authorization","Bearer $token").header("X-Nodi-Ticket",session.getString("ticket")).build(),object:WebSocketListener(){
            override fun onMessage(webSocket:WebSocket,text:String){if(text=="{\"type\":\"ready\"}")ready.countDown() else stop()}
            override fun onMessage(webSocket:WebSocket,bytes:ByteString){
                if(bytes.size==0){ack.release();return}
                if(bytes.size>65536 || !inbox.offer(bytes.toByteArray()))stop()
            }
            override fun onFailure(webSocket:WebSocket,t:Throwable,response:Response?){diagnostic("WSS failure: "+t.javaClass.simpleName+" HTTP "+response?.code);stop()}
            override fun onClosing(webSocket:WebSocket,code:Int,reason:String){diagnostic("WSS closing: $code $reason");stop()}
            override fun onClosed(webSocket:WebSocket,code:Int,reason:String){stop()}
        })
        try {
            val until=System.nanoTime()+TimeUnit.SECONDS.toNanos(30)
            while(!ready.await(100,TimeUnit.MILLISECONDS)){checkActive();if(System.nanoTime()>=until)throw IOException("Пристрій не відповідає через інтернет")}
            checkActive();if(closed.get())throw IOException("Пристрій не відповідає через інтернет")
        }catch(e:Exception){stop();throw e}
        Thread({try{while(!closed.get()){
            val data=inbox.poll(1,TimeUnit.SECONDS)?:continue
            socket.getOutputStream().write(data)
            if(ws?.send(ByteString.EMPTY)!=true)break
        }}catch(_:Exception){}finally{stop()}},"Nodi-relay-read").apply{isDaemon=true;start()}
        Thread({try{val b=ByteArray(32768);while(!closed.get()){
            val n=socket.getInputStream().read(b);if(n<0)break
            if(ws?.send(b.toByteString(0,n))!=true || !ack.tryAcquire(30,TimeUnit.SECONDS))break
        }}catch(_:Exception){}finally{stop();cancel(session)}},"Nodi-relay-write").apply{isDaemon=true;start()}
    }
    fun receive(port:Int,peers:()->List<String>,status:(String)->Unit):Closeable {
        val stopped=AtomicBoolean(false);val active=ConcurrentHashMap<String,Socket>();val granted=mutableSetOf<String>()
        val timer=Executors.newSingleThreadScheduledExecutor { r->Thread(r,"Nodi-relay-poll").apply{isDaemon=true} }
        timer.scheduleWithFixedDelay({try{
            val allowed=peers().toSet()
            for(pin in allowed)if(pin !in granted){try{api("PUT","/v1/peers/$pin");granted.add(pin)}catch(_:Exception){}}
            for(pin in granted.toList())if(pin !in allowed){api("DELETE","/v1/peers/$pin");granted.remove(pin)}
            active.entries.removeIf{it.value.isClosed}
            val incoming=api("GET","/v1/incoming").getJSONArray("sessions")
            status("Інтернет увімкнено")
            for(i in 0 until incoming.length()){
                if(stopped.get())break
                val s=incoming.getJSONObject(i);val id=s.getString("id")
                if(s.getString("from") !in allowed){cancel(s);continue}
                if(active.containsKey(id)||active.size>=2)continue
                val socket=Socket("127.0.0.1",port);active[id]=socket
                if(stopped.get()){socket.close();break}
                try{tunnel(s,socket)}catch(_:Exception){socket.close();cancel(s)}
            }
            active.entries.removeIf{it.value.isClosed}
        }catch(_:Exception){if(!stopped.get())status("Інтернет недоступний")}},0,10,TimeUnit.SECONDS)
        return Closeable{stopped.set(true);timer.shutdownNow();active.values.forEach{try{it.close()}catch(_:Exception){}};active.clear()}
    }
}
