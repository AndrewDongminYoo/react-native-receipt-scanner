package com.receiptscanner

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** Executes literal shared expectations against the production helpers, without Android stubs. */
@RunWith(Parameterized::class)
class GeometryConformanceTest(
  private val kind: String,
  private val id: String,
  private val fixture: JsonObject,
) {
  @Test
  fun conformsToSharedGeometry() {
    when (kind) {
      "quantizeCases" -> {
        assertEquals(id, fixture["expected"].asInt, OcrGeometry.quantizeQuarterTurn(fixture["degrees"].asFloat))
      }

      "angleCases" -> {
        val angles = fixture.getAsJsonArray("angles").map { it.angle() }
        val histogram = fixture.getAsJsonArray("expectedHistogram").map { it.asInt }.toIntArray()
        assertArrayEquals(id, histogram, OcrGeometry.quarterTurnHistogram(angles))
        val expected = fixture["expectedDominant"].let { if (it.isJsonNull) null else it.asInt }
        assertEquals(id, expected, OcrGeometry.dominantQuarterTurn(angles))
      }

      "correctionCases" -> {
        assertEquals(id, fixture["expected"].asInt, OcrGeometry.correctionForTextAngle(fixture["quarterTurn"].asInt))
      }

      "boxCases" -> {
        val frame = fixture.getAsJsonArray("frame")
        val degrees = fixture["degrees"].asInt
        val rotated = OcrGeometry.rotateClockwise(fixture.getAsJsonArray("box").box(), frame[0].asInt, frame[1].asInt, degrees)
        assertEquals(id, fixture.getAsJsonArray("expectedRotated").box(), rotated)
        // The caller swaps the frame too; exercise clamp in that output frame.
        val swap = ((degrees % 360 + 360) % 360) in listOf(90, 270)
        val width = frame[if (swap) 1 else 0].asInt
        val height = frame[if (swap) 0 else 1].asInt
        assertEquals(id, fixture.getAsJsonArray("expectedFrame").map { it.asInt }, listOf(width, height))
        val expected = fixture["expectedClamped"].let { if (it.isJsonNull) null else it.asJsonArray.box() }
        assertEquals(id, expected, OcrGeometry.clamp(rotated, width, height))
      }

      "quadCases" -> {
        val corners = fixture.getAsJsonArray("corners").map { it.asFloat }.toFloatArray()
        assertEquals(id, fixture["expectedDistorted"].asBoolean, QuadGeometry.isDistorted(corners))
      }

      else -> {
        error("Unknown geometry group: $kind")
      }
    }
  }

  private fun JsonArray.box() = OcrGeometry.Box(get(0).asInt, get(1).asInt, get(2).asInt, get(3).asInt)

  private fun JsonElement.angle(): Float =
    if (asJsonPrimitive.isNumber) {
      asFloat
    } else {
      when (asString) {
        "NaN" -> Float.NaN
        "+Infinity" -> Float.POSITIVE_INFINITY
        "-Infinity" -> Float.NEGATIVE_INFINITY
        else -> error("Unknown non-finite angle token: $this")
      }
    }

  companion object {
    @JvmStatic
    @Parameterized.Parameters(name = "{0}: {1}")
    fun cases(): List<Array<Any>> {
      val stream = requireNotNull(GeometryConformanceTest::class.java.getResourceAsStream("/v1/contract.json"))
      val corpus = stream.bufferedReader(Charsets.UTF_8).use { JsonParser.parseReader(it).asJsonObject }
      require(corpus["schemaVersion"].asInt == 1) { "Unsupported geometry corpus version" }
      val geometry = corpus.getAsJsonObject("geometry")
      return listOf("quantizeCases", "angleCases", "correctionCases", "boxCases", "quadCases").flatMap { kind ->
        val cases = geometry.getAsJsonArray(kind)
        require(cases.size() > 0) { "Empty geometry group: $kind" }
        cases.map { value ->
          val fixture = value.asJsonObject
          arrayOf(kind, fixture["id"].asString, fixture)
        }
      }
    }
  }
}
