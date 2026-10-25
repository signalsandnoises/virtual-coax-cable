package com.vcx.receiver.core

/**
 * Microsecond clock for the playout chain. A fun interface with a primitive return, so calling it
 * never boxes (a Kotlin `() -> Long` returns a boxed Long through Function0).
 */
fun interface UsClock {
    fun nowUs(): Long
}

/**
 * In-place sort of doubles that never allocates. java.util.Arrays.sort(double[]) can allocate a
 * merge buffer for long, partly sorted inputs (exactly what smooth depth samples look like).
 * Insertion sort for short ranges, heapsort otherwise.
 */
object DoubleSort {
    fun sort(a: DoubleArray, n: Int) {
        if (n <= 32) {
            for (i in 1 until n) {
                val x = a[i]
                var j = i - 1
                while (j >= 0 && a[j] > x) { a[j + 1] = a[j]; j-- }
                a[j + 1] = x
            }
            return
        }
        for (i in n / 2 - 1 downTo 0) siftDown(a, i, n)
        var end = n - 1
        while (end > 0) {
            val t = a[0]; a[0] = a[end]; a[end] = t
            siftDown(a, 0, end)
            end--
        }
    }

    private fun siftDown(a: DoubleArray, start: Int, n: Int) {
        var root = start
        while (true) {
            var child = 2 * root + 1
            if (child >= n) return
            if (child + 1 < n && a[child + 1] > a[child]) child++
            if (a[root] >= a[child]) return
            val t = a[root]; a[root] = a[child]; a[child] = t
            root = child
        }
    }
}
