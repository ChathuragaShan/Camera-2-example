package com.chathurangashan.camera2example.di.modules

import com.chathurangashan.camera2example.network.ApiService
import com.chathurangashan.camera2example.network.NetworkService
import dagger.Module
import dagger.Provides
import javax.inject.Singleton

@Module
class AppModule {

    @Singleton
    @Provides
    fun provideRetrofit() = NetworkService.getInstance().getService(ApiService::class.java)
}