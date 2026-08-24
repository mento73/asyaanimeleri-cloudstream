package com.anihepsi.dizipal

import com.fasterxml.jackson.annotation.JsonProperty

data class SearchItem(
    @JsonProperty("title")
    val title: String,

    @JsonProperty("url")
    val url: String,

    @JsonProperty("type")
    val type: String,

    @JsonProperty("poster")
    val poster: String?
)
