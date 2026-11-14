package com.yhct.pulsemaster.core

import java.io.File

object TestData {
    val dataFile = File("../data/tcm_28_pulses_haptic.json")
    val catalog: PulseCatalog by lazy { PulseCatalog.parse(dataFile.readText(Charsets.UTF_8)) }
    fun p(id: String) = catalog.require(id)
}

class ScriptedUniform(private val values: DoubleArray) : UniformSource {
    private var i = 0
    override fun next(): Double = values[i++ % values.size]
}

fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
