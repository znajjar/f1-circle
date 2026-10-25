package com.f1circle.app.model

import kotlin.math.abs
import kotlin.math.exp

/**
 * Eases each dot towards its newly computed position so it glides rather than jumps when fresh
 * timing data lands. [raceMs] is how much race time passed during the last [realMs] of screen time.
 */
fun smoothProgress(prev: Map<Int, Double>, f: CircleFrame, realMs: Double, raceMs: Double, snap: Boolean): Map<Int, Double> {
    val alpha = 1 - exp(-realMs / 180.0)
    return f.cars.associate { car ->
        val target = car.progress
        val last = prev[car.driver.number]
        val moving = !car.stopped && !car.finished
        val next = if (snap || last == null || abs(target - last) > 0.06) {
            target
        } else {
            // Advance at reference pace, then ease out whatever error is left
            val predicted = if (moving) last + raceMs / 1000.0 / f.referenceLap else last
            predicted + (target - predicted) * alpha
        }
        car.driver.number to next
    }
}
