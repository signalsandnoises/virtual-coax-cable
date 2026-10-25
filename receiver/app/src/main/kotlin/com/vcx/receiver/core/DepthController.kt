package com.vcx.receiver.core

import kotlin.math.abs

/**
 * PI controller: depth error (ms, measured - target) -> resampling offset (ppm, > 0 = consume faster).
 *
 * Plant: d(depth_ms)/dt = (clockOffsetPpm - ratePpm) / 1000. With P alone the loop time constant is
 * 1000 / kp seconds (kp = 150 -> 6.7 s); a 10 ms error starts at the 1000 ppm clamp (= 1 ms/s drain)
 * and is inside 1 ms after ~16 s. The integrator learns the PC/phone clock offset. It only
 * integrates inside +-[integrateBandMs] and while the output is not clamped (anti-windup), so a large
 * start-up error does not wind it up and cause overshoot/oscillation.
 */
class DepthController(
    val kp: Double = 150.0,          // ppm per ms of error
    val ki: Double = 3.0,            // ppm per (ms * s)
    val maxPpm: Double = 1000.0,
    val integrateBandMs: Double = 2.5,
) {
    var integralPpm = 0.0
        private set
    var ppm = 0.0
        private set

    fun reset() {
        integralPpm = 0.0
        ppm = 0.0
    }

    fun update(errorMs: Double, dtSec: Double): Double {
        val p = kp * errorMs
        if (abs(errorMs) < integrateBandMs && abs(p + integralPpm) < maxPpm) {
            integralPpm = (integralPpm + ki * errorMs * dtSec).coerceIn(-maxPpm, maxPpm)
        }
        ppm = (p + integralPpm).coerceIn(-maxPpm, maxPpm)
        return ppm
    }
}
