package com.mew.wlfmovie.models

import com.mew.wlfmovie.adapters.AppAdapter

open class Provider(
    val name: String,
    val logo: String,
    val language: String,

    val provider: com.mew.wlfmovie.providers.Provider,
    var isFavorite: Boolean = false,
) : AppAdapter.Item {


    override lateinit var itemType: AppAdapter.Type
}