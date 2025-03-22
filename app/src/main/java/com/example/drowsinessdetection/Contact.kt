package com.example.drowsinessdetection

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class Contact(
    val name: String,
    val phoneNumber: String
) : Parcelable
