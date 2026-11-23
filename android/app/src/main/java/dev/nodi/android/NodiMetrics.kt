package dev.nodi.android

import android.content.Context
import org.json.JSONObject

object NodiMetrics {
    fun consent(c:Context)=c.getSharedPreferences("nodi_privacy",Context.MODE_PRIVATE).getBoolean("usage",false)
    fun consent(c:Context,value:Boolean){c.getSharedPreferences("nodi_privacy",Context.MODE_PRIVATE).edit().putBoolean("usage",value).apply()}
    fun report(c:Context,event:String){
        if(BuildConfig.NODI_TEST_INSTALL||!consent(c))return
        val context=c.applicationContext
        Thread({try{NodiInternetStore.ensure(context).api("POST","/v1/usage",JSONObject().put("consent",true).put("platform","android").put("event",event))}catch(_:Exception){}},"Nodi-usage").apply{isDaemon=true;start()}
    }
}
