package io.github.ddsimoes.stgeomjts

import com.vividsolutions.jts.geom.Coordinate
import com.vividsolutions.jts.geom.Geometry
import com.vividsolutions.jts.geom.GeometryFactory
import com.vividsolutions.jts.geom.LineString
import com.vividsolutions.jts.geom.LinearRing
import com.vividsolutions.jts.geom.MultiPolygon
import com.vividsolutions.jts.geom.Point
import com.vividsolutions.jts.geom.Polygon
import com.vividsolutions.jts.geom.impl.PackedCoordinateSequence
import kotlin.math.abs

class StCoordinatesSystem(
    val offsetX: Double,
    val offsetY: Double,
    val offsetZ: Double,
    val resolution: Double
)

enum class StType(val value: Int) {
    POINT(1), LINESTRING(4), POLYGON(8), MULTIPOLYGON(264)
}

class StGeometryReader(private val coordinatesSystem: StCoordinatesSystem) {
    private val geometryFactory = GeometryFactory()
    private val unpacker = Unpacker()
    private var longArray = LongArray(4)

    @Suppress("REDUNDANT_ELSE_IN_WHEN")
    fun read(type: StType, bytes: ByteArray): Geometry {

        unpacker.reset(bytes)

        val length = unpacker.read()
        check(unpacker.offset in 1..6)
        check(length.toInt() == bytes.size - 8)

        val dimensionMask = bytes[5].toUByte().toInt()
        check(dimensionMask and 0x1 == 0) { "NIY: Coordinates with Z dimension not implemented yet." }
        check(dimensionMask and 0x2 == 0) { "NIY: Coordinates with M (measures) not implemented yet." }

        unpacker.offset = 8

        return when (type) {
            StType.POINT -> readPoint()
            StType.LINESTRING -> readLineString()
            StType.POLYGON -> readPolygon()
            StType.MULTIPOLYGON -> readMultiPolygon()
            else -> error("Unsupported geometry type $type")
        }
    }

    private fun readMultiPolygon(): MultiPolygon {
        val resolution = coordinatesSystem.resolution

        val count = readLongValues()

        check(count >= 4) { "Invalid point stream for Polygon. Expected at least 4 values (2 x/y coordinates), got $count values." }

        var index = findSeparator(2,  count, -longArray[0] - 1, -longArray[1])

        if (index == -1) {
            //optimization to not create a list for single polygon
            return geometryFactory.createMultiPolygon(arrayOf(readPolygon(0, count, resolution)))
        }

        val polygons = mutableListOf<Polygon>()

        var idx0 = 0
        while (index != -1) {
            polygons.add(readPolygon(idx0, index - idx0, resolution))
            idx0 = index + 2
            index = findSeparator(idx0 + 2,  count, -longArray[idx0], -longArray[idx0 + 1])
        }

        polygons.add(readPolygon(idx0, count - idx0, resolution))

        return geometryFactory.createMultiPolygon(polygons.toTypedArray())

    }

    private fun findSeparator(offset: Int, size: Int, xSep: Long, ySep: Long): Int {
        for (idx0 in offset until size step 2) {
            if (longArray[idx0] == xSep && longArray[idx0 + 1] == ySep) {
                return idx0
            }
        }

        return -1
    }

    private fun readPolygon(): Polygon {
        val resolution = coordinatesSystem.resolution

        val count = readLongValues()

        check(count >= 4) { "Invalid point stream for Polygon. Expected at least 4 values (2 x/y coordinates), got $count values." }

        return readPolygon(0, count, resolution)
    }

    private fun readPolygon(offset: Int, count: Int, resolution: Double): Polygon {
        val doubleArray = readDoubles(offset, count, resolution)

        val outerShell = readClosedRing(doubleArray, 0, coordinatesSystem.resolution)

        if (outerShell.size == doubleArray.size) {
            return geometryFactory.createPolygon(
                geometryFactory.createLinearRing(PackedCoordinateSequence.Double(outerShell, 2))
            )
        }

        val firstHole = readClosedRing(doubleArray, outerShell.size, coordinatesSystem.resolution)

        if (outerShell.size + firstHole.size == doubleArray.size) {
            return geometryFactory.createPolygon(
                geometryFactory.createLinearRing(PackedCoordinateSequence.Double(outerShell, 2)),
                arrayOf(geometryFactory.createLinearRing(PackedCoordinateSequence.Double(firstHole, 2)))
            )
        }

        val holes = mutableListOf<LinearRing>()
        holes.add(geometryFactory.createLinearRing(PackedCoordinateSequence.Double(firstHole, 2)))

        var holeOffset = outerShell.size + firstHole.size
        while (holeOffset < doubleArray.size) {
            val hole = readClosedRing(doubleArray, holeOffset, coordinatesSystem.resolution)
            holes.add(geometryFactory.createLinearRing(PackedCoordinateSequence.Double(hole, 2)))
            holeOffset += hole.size
        }

        return geometryFactory.createPolygon(
            geometryFactory.createLinearRing(
                PackedCoordinateSequence.Double(
                    outerShell,
                    2
                )
            ), holes.toTypedArray()
        )
    }

    private fun readClosedRing(doubleArray: DoubleArray, offset: Int, precision: Double): DoubleArray {

        check(offset < doubleArray.size - 2)

        val x0 = doubleArray[offset]
        val y0 = doubleArray[offset + 1]
        var index = offset + 2

        while (abs(doubleArray[index] - x0) > precision || abs(doubleArray[index + 1] - y0) > precision)  {
            index += 2
        }

        val ringArray = DoubleArray(index + 2 - offset)
        System.arraycopy(doubleArray, offset, ringArray, 0, ringArray.size)

        ringArray[ringArray.size - 2] = ringArray[0]
        ringArray[ringArray.size - 1] = ringArray[1]
        return ringArray
    }

    private fun readPoint(): Point {
        val resolution = coordinatesSystem.resolution
        val x = unpacker.read() * resolution + coordinatesSystem.offsetX
        val y = unpacker.read() * resolution + coordinatesSystem.offsetY

        check(unpacker.remaining() == 0) {
            "Invalid point stream has unexpected ${unpacker.remaining()} bytes left after x/y coordinate."
        }

        return geometryFactory.createPoint(Coordinate(x, y))
    }

    private fun readLineString(): LineString {
        val resolution = coordinatesSystem.resolution

        val count = readLongValues()

        check(count >= 4) { "Invalid point stream for LineString. Expected at least 4 values (2 x/y coordinates), got $count values." }

        val doubleArray = readDoubles(0, count, resolution)

        return geometryFactory.createLineString(PackedCoordinateSequence.Double(doubleArray, 2))
    }

    private fun readDoubles(offset: Int, count: Int, resolution: Double): DoubleArray {
        val doubleArray = DoubleArray(count)

        var offsetX = coordinatesSystem.offsetX
        var offsetY = coordinatesSystem.offsetY

        for (idx0 in 0 until count step 2) {
            val idx1 = idx0 + 1
            doubleArray[idx0] = longArray[offset + idx0] * resolution + offsetX
            doubleArray[idx1] = longArray[offset + idx1] * resolution + offsetY
            offsetX = doubleArray[idx0]
            offsetY = doubleArray[idx1]
        }
        return doubleArray
    }

    private fun readLongValues(): Int {
        var index = 0
        while (unpacker.remaining() > 0) {
            if (longArray.size <= index) {
                longArray = LongArray(longArray.size * 2).apply {
                    System.arraycopy(longArray, 0, this, 0, longArray.size)
                }
            }
            longArray[index] = unpacker.read()
            index++
        }
        return index
    }

}

private class Unpacker(private var bytes: ByteArray = ByteArray(0)) {

    var offset: Int = 0

    fun reset(bytes: ByteArray) {
        this.bytes = bytes
        this.offset = 0
    }

    fun remaining() = bytes.size - offset

    fun read(): Long {
        val firstByte = bytes[offset].toUByte().toUInt()

        offset++

        var isLast = isLastByteSet(firstByte)
        val isNegative = isNegativeSet(firstByte)
        var value: Long = (firstByte and 0x3fu).toLong()

        var shiftAmount = 6

        while (!isLast) {
            val byte = bytes[offset].toUByte().toUInt()
            isLast = isLastByteSet(byte)
            val bits = (byte and 0x7fu).toLong() shl shiftAmount
            value = value or bits
            shiftAmount += 7
            offset++
        }

        if (isNegative) {
            return -value
        }

        return value
    }

    private fun isLastByteSet(byte: UInt) = (byte and 0x80u) == 0u
    private fun isNegativeSet(byte: UInt) = (byte and 0x40u) != 0u

}
