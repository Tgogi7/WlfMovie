package com.mew.wlfmovie.models

import com.mew.wlfmovie.adapters.AppAdapter

sealed interface Show : AppAdapter.Item {
    var isFavorite: Boolean
}
