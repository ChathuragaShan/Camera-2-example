package com.chathurangashan.camera2example.di.subcomponents

import android.view.View
import com.chathurangashan.camera2example.di.scopes.FragmentScope
import com.chathurangashan.camera2example.di.modules.FragmentModule
import com.chathurangashan.camera2example.repositories.CameraRepository
import com.chathurangashan.camera2example.ui.fragments.CameraFragment
import com.chathurangashan.camera2example.viewmodel.CameraViewModel
import dagger.BindsInstance
import dagger.Subcomponent

@FragmentScope
@Subcomponent(modules = [FragmentModule::class])
interface FragmentSubComponent {

    @Subcomponent.Factory
    interface Factory{
        fun create(@BindsInstance view: View): FragmentSubComponent
    }

    fun inject(fragment: CameraFragment)
    val cameraViewModel: CameraViewModel

}