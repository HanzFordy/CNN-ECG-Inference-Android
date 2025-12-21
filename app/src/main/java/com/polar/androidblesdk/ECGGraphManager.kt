package com.polar.androidblesdk

import java.util.ArrayDeque

// Struktur data sederhana untuk Marker
data class MarkerEntry(val x: Float, val y: Float, val label: String)
data class PredictionMarker(val xIndex: Long, val yValue: Float, val label: String)

class EcgGraphManager(private val maxVisibleEntries: Int = 400) {
    var lastGraphIndex: Long = 0
        private set

    private val pendingMarkers: ArrayDeque<PredictionMarker> = ArrayDeque()

    data class ProcessResult(
        val startX: Long,
        val endX: Long,
        val minVisibleX: Float,
        val markersToShow: List<MarkerEntry>
    )

    fun enqueueMarker(marker: PredictionMarker) {
        pendingMarkers.addLast(marker)
    }

    fun processChunk(chunkSize: Int): ProcessResult {
        if (chunkSize <= 0) {
            return ProcessResult(
                startX = lastGraphIndex,
                endX = lastGraphIndex,
                minVisibleX = computeMinVisibleX(lastGraphIndex),
                markersToShow = emptyList()
            )
        }

        val startX = lastGraphIndex
        val endX = lastGraphIndex + chunkSize - 1
        lastGraphIndex += chunkSize

        val minVisibleX = computeMinVisibleX(lastGraphIndex)
        val markers = mutableListOf<MarkerEntry>()

        // Cek apakah ada marker yang index-nya masuk dalam rentang chunk ini
        while (pendingMarkers.isNotEmpty()) {
            val peek = pendingMarkers.first()
            if (peek.xIndex <= endX) {
                val marker = pendingMarkers.removeFirst()
                // Validasi nilai Y agar tidak crash grafik
                val y = if (marker.yValue.isNaN() || marker.yValue.isInfinite()) 0f else marker.yValue
                markers.add(
                    MarkerEntry(
                        x = marker.xIndex.toFloat(),
                        y = y,
                        label = marker.label
                    )
                )
            } else {
                // Marker berikutnya ada di masa depan, berhenti cek
                break
            }
        }

        return ProcessResult(
            startX = startX,
            endX = endX,
            minVisibleX = minVisibleX,
            markersToShow = markers
        )
    }

    private fun computeMinVisibleX(currentLastIndex: Long): Float {
        return if (currentLastIndex > maxVisibleEntries) {
            (currentLastIndex - maxVisibleEntries).toFloat()
        } else {
            0f
        }
    }

    fun reset() {
        lastGraphIndex = 0
        pendingMarkers.clear()
    }
}