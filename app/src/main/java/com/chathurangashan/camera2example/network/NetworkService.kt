package com.chathurangashan.camera2example.network

import com.chathurangashan.camera2example.Config
import com.chathurangashan.camera2example.ThisApplication
import com.chathurangashan.camera2example.data.enums.BuildType
import java.util.concurrent.TimeUnit
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import retrofit2.Retrofit

class NetworkService {

    private var baseURL: String = ""

    companion object {
        fun getInstance(): NetworkService {
            return NetworkService()
        }

        fun getTestInstance(testUrl: HttpUrl): NetworkService {
            return NetworkService(testUrl)
        }
    }

    constructor(){
        baseURL = when(ThisApplication.buildType){
            BuildType.RELEASE -> Config.LIVE_BASE_URL
            BuildType.DEVELOPMENT -> Config.DEV_BASE_URL
            BuildType.TESTING -> ""
        }
    }

    constructor(testUrl: HttpUrl) : this() {
        baseURL = testUrl.toString()
    }

    fun <S> getService(serviceClass: Class<S>): S {

        val httpClient = OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)

        val builder = Retrofit.Builder()
                .baseUrl(baseURL)
                .client(httpClient.build())

        val retrofit = builder.build()

        return retrofit.create(serviceClass)
    }
}