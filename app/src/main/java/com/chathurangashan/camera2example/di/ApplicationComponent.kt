package com.chathurangashan.camera2example.di

import android.content.Context
import com.chathurangashan.camera2example.di.modules.AppModule
import com.chathurangashan.camera2example.di.subcomponents.ActivitySubComponent
import com.chathurangashan.camera2example.di.subcomponents.FragmentSubComponent
import com.chathurangashan.camera2example.di.subcomponents.SubComponentModule
import com.squareup.inject.assisted.dagger2.AssistedModule
import dagger.BindsInstance
import dagger.Component
import dagger.Module
import javax.inject.Singleton

@Singleton
@Component(modules = [AppModule::class, SubComponentModule::class])
interface ApplicationComponent {

    @Component.Factory
    interface Factory {
        fun create(@BindsInstance applicationContext: Context): ApplicationComponent
    }

    fun activityComponent() : ActivitySubComponent.Factory
    fun fragmentComponent() : FragmentSubComponent.Factory

}
