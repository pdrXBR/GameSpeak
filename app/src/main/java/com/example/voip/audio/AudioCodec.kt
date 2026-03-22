package com.example.voip.audio

interface AudioCodec {
    fun encode(pcmData: ByteArray): ByteArray
    fun decode(encodedData: ByteArray): ByteArray
}

class PassThroughCodec : AudioCodec {
    override fun encode(pcmData: ByteArray): ByteArray = pcmData
    override fun decode(encodedData: ByteArray): ByteArray = encodedData
}