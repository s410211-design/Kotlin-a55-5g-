package com.s410211.a55wuwa.telemetry

import java.math.BigDecimal
import java.math.BigInteger

/**
 * The unit is supplied by a source-specific, verified adapter. The parser
 * deliberately does not infer Hz/kHz/MHz from a filename or magnitude.
 */
enum class FrequencyUnit(val multiplierToHz: BigDecimal) {
    HZ(BigDecimal.ONE),
    KHZ(BigDecimal("1000")),
    MHZ(BigDecimal("1000000"))
}

/**
 * Parses one positive decimal token and converts it to integer Hertz.
 * Callers must select [FrequencyUnit] only after validating the node's
 * documented/observed unit on the target firmware.
 */
object GpuFrequencyParser {
    private const val MAX_TOKEN_LENGTH = 64
    private val DECIMAL_TOKEN = Regex("""\+?\d+(?:\.\d+)?""")
    private val MAX_LONG = BigDecimal(BigInteger.valueOf(Long.MAX_VALUE))

    fun parse(
        raw: String,
        source: String,
        sampledAtMillis: Long,
        unit: FrequencyUnit
    ): Reading<Long> {
        val token = raw.trim()
        if (token.isEmpty() || token.length > MAX_TOKEN_LENGTH ||
            !DECIMAL_TOKEN.matches(token)
        ) {
            return Reading.InvalidData(
                source,
                sampledAtMillis,
                "Expected one positive decimal frequency token with an explicit source unit"
            )
        }

        val frequency = token.toBigDecimalOrNull()
            ?: return Reading.InvalidData(source, sampledAtMillis, "Frequency is not a valid decimal")

        if (frequency.signum() <= 0) {
            return Reading.InvalidData(source, sampledAtMillis, "Frequency must be greater than zero")
        }

        val hertz = frequency.multiply(unit.multiplierToHz)
        if (hertz > MAX_LONG) {
            return Reading.InvalidData(source, sampledAtMillis, "Frequency exceeds the supported Long range")
        }

        val exactHertz = try {
            hertz.stripTrailingZeros().longValueExact()
        } catch (_: ArithmeticException) {
            return Reading.InvalidData(
                source,
                sampledAtMillis,
                "Frequency cannot be represented as an exact integer number of Hertz"
            )
        }

        if (exactHertz <= 0L) {
            return Reading.InvalidData(source, sampledAtMillis, "Converted frequency must be positive")
        }

        return Reading.Value(exactHertz, source, "Hz", sampledAtMillis)
    }
}
