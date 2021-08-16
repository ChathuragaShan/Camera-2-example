package com.chathurangashan.camera2example.viewmodel

import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.chathurangashan.camera2example.data.enums.OperationErrorType
import com.chathurangashan.camera2example.repositories.BaseRepository
import com.chathurangashan.camera2example.utils.OperationError
import com.chathurangashan.camera2example.utils.SingleLiveEvent


abstract class BaseViewModel(repository: BaseRepository): ViewModel() {

    /**
     * Dedicated live data object which use to communicate error details occurs in view model
     * layer to upper layers
     */
    private val _operationErrorLiveData =  MutableLiveData<SingleLiveEvent<OperationError>>()

    /**
     * Dedicated live data object to which use to communicate the data processing status to the UI
     * so that it can know when to hide and show loading animation
     */
    val isProcessing = MutableLiveData<Boolean>()

    /**
     * Dedicated live data object which use to marge error details occurs in viewModel and
     * Repository layer and been observed by UI to make necessary UI changes.
     */
    val operationErrorLiveData: MediatorLiveData<SingleLiveEvent<OperationError>> = MediatorLiveData()

    init {

        operationErrorLiveData.addSource(repository.operationErrorLiveDate){
            operationErrorLiveData.value = it
            isProcessing.value = false
        }

        operationErrorLiveData.addSource(_operationErrorLiveData){
            operationErrorLiveData.value = it
            isProcessing.value = false

        }

    }

    fun validationError(fieldErrors: Map<String, Any> = mapOf(), errorId: Int = 1){

        val operationError = OperationError
            .Builder(OperationErrorType.VALIDATION_ERROR)
            .errorId(errorId)
            .fieldError(fieldErrors)
            .build()

        _operationErrorLiveData.value = SingleLiveEvent(operationError)
    }
}