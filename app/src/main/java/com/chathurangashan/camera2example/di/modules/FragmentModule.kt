package com.chathurangashan.camera2example.di.modules

import android.view.View
import androidx.navigation.Navigation
import com.chathurangashan.camera2example.di.scopes.FragmentScope
import com.chathurangashan.camera2example.repositories.CameraRepository
import dagger.Module
import dagger.Provides

@Module
class FragmentModule {

    @FragmentScope
    @Provides
    fun navigation(view : View) = Navigation.findNavController(view)
}