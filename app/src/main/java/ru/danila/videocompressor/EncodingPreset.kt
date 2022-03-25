package ru.danila.videocompressor

data class EncodingPreset(
    var videoWidth        : Int,
    var videoHeight       : Int,
    var videoFrameRate    : Int,
    var videoBitRate      : Int,
    var audioSampleRate   : Int,
    var audioChannelCount : Int,
    var audioBitRate      : Int
)
