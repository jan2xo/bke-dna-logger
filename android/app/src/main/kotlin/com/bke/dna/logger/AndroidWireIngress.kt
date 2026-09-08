package com.bke.dna.logger

/** Native Android seam that GeckoView's message delegate will feed next. */
class AndroidWireIngress {
    fun accept(rawMessage: ByteArray): String = DnaWireContract.validate(rawMessage)
}
