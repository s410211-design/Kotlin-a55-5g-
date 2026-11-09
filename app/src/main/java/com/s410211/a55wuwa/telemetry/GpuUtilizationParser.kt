package com.s410211.a55wuwa.telemetry

import java.math.BigDecimal
import java.math.BigInteger
import java.math.MathContext

object InstantPercentageParser {
    fun parse(raw: String, source: String, sampledAtMillis: Long): Reading<Double> {
        val text = raw.trim().removeSuffix("%").trim()
        val parsed = text.toBigDecimalOrNull()
            ?: return Reading.InvalidData(source, sampledAtMillis, "Expected one finite decimal percentage")

        if (parsed < BigDecimal.ZERO || parsed > BigDecimal("100")) {
            return Reading.InvalidData(source, sampledAtMillis, "Percentage must be within 0..100")
        }
        val value = parsed.toDouble()
        if (!value.isFinite()) {
            return Reading.InvalidData(source, sampledAtMillis, "Percentage is not finite")
        }
        return Reading.Value(value, source, "%", sampledAtMillis)
    }
}

/**
 * Parses two monotonically increasing decimal counters: "busy total".
 * First sample establishes a baseline. Keep one parser instance per source
 * and share it across direct sysfs and Shizuku access paths.
 */
class CumulativeBusyTotalParser(private val source: String) {
    private var previousBusy: BigInteger? = null
    private var previousTotal: BigInteger? = null

    @Synchronized
    fun reset() {
        previousBusy = null
        previousTotal = null
    }

    @Synchronized
    fun parse(raw: String, sampledAtMillis: Long): Reading<Double> {
        val parts = raw.trim().split(Regex("\\s+"))
        if (parts.size != 2) {
            return Reading.InvalidData(
                source, sampledAtMillis, "Expected exactly two decimal counters: busy total"
            )
        }
        val busy = parts[0].toBigIntegerOrNull()
        val total = parts[1].toBigIntegerOrNull()
        if (busy == null || total == null || busy.signum() < 0 || total.signum() < 0) {
            return Reading.InvalidData(
                source, sampledAtMillis, "Counters must be non-negative decimal integers"
            )
        }

        val oldBusy = previousBusy
        val oldTotal = previousTotal
        if (oldBusy == null || oldTotal == null) {
            previousBusy = busy
            previousTotal = total
            return Reading.AwaitingSample(
                source, sampledAtMillis, "Baseline stored; a second sample is required"
            )
        }
        if (busy < oldBusy || total < oldTotal) {
            previousBusy = busy
            previousTotal = total
            return Reading.InvalidData(
                source, sampledAtMillis, "Counter rollback/reset detected; baseline restarted"
            )
        }

        val deltaBusy = busy.subtract(oldBusy)
        val deltaTotal = total.subtract(oldTotal)
        // Advance the baseline after each syntactically valid sample.
        previousBusy = busy
        previousTotal = total

        if (deltaTotal.signum() <= 0) {
            return Reading.InvalidData(source, sampledAtMillis, "Total counter did not increase")
        }
        if (deltaBusy.signum() < 0 || deltaBusy > deltaTotal) {
            return Reading.InvalidData(
                source, sampledAtMillis, "Busy delta is outside the valid total interval"
            )
        }

        val percent = BigDecimal(deltaBusy)
            .multiply(BigDecimal("100"))
            .divide(BigDecimal(deltaTotal), MathContext.DECIMAL64)
            .toDouble()
        if (!percent.isFinite() || percent !in 0.0..100.0) {
            return Reading.InvalidData(
                source, sampledAtMillis, "Calculated percentage is outside 0..100"
            )
        }
        return Reading.Value(percent, source, "%", sampledAtMillis)
    }
}
