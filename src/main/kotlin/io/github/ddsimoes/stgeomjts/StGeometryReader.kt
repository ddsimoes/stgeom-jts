/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY

 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

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

@Suppress("MemberVisibilityCanBePrivate", "CanBeParameter")
class StCoordinatesSystem(
    val offsetX: Double,
    val offsetY: Double,
    val offsetZ: Double, // not implemented yet
    val precision: Double
) {
    val offsetXLong = (offsetX * precision).toLong()
    val offsetYLong = (offsetY * precision).toLong()
}

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

        val count = readLongValues()

        check(count >= 4) { "Invalid point stream for Polygon. Expected at least 4 values (2 x/y coordinates), got $count values." }

        var index = findSeparator(2,  count, coordinatesSystem.offsetXLong - 1, coordinatesSystem.offsetYLong)

        if (index == -1) {
            //optimization to not create a list for single polygon
            return geometryFactory.createMultiPolygon(arrayOf(readPolygon(0, count)))
        }

        val polygons = mutableListOf<Polygon>()

        var idx0 = 0
        while (index != -1) {
            polygons.add(readPolygon(idx0, index - idx0))
            idx0 = index + 2
            index = findSeparator(idx0 + 2,  count, coordinatesSystem.offsetXLong - 1, coordinatesSystem.offsetYLong)
        }

        polygons.add(readPolygon(idx0, count - idx0))

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
        val count = readLongValues()

        check(count >= 4) { "Invalid point stream for Polygon. Expected at least 4 values (2 x/y coordinates), got $count values." }

        return readPolygon(0, count)
    }

    private fun readPolygon(offset: Int, count: Int): Polygon {
        val doubleArray = readDoubles(offset, count)

        val outerShell = readClosedRing(doubleArray, 0)

        if (outerShell.size == doubleArray.size) {
            return geometryFactory.createPolygon(
                geometryFactory.createLinearRing(PackedCoordinateSequence.Double(outerShell, 2))
            )
        }

        val firstHole = readClosedRing(doubleArray, outerShell.size)

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
            val hole = readClosedRing(doubleArray, holeOffset)
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

    private fun readClosedRing(doubleArray: DoubleArray, offset: Int): DoubleArray {

        check(offset < doubleArray.size - 2)

        val x0 = doubleArray[offset]
        val y0 = doubleArray[offset + 1]
        var index = offset + 2

        while (doubleArray[index] != x0 || doubleArray[index + 1] != y0)  {
            index += 2
        }

        val ringArray = DoubleArray(index + 2 - offset)
        System.arraycopy(doubleArray, offset, ringArray, 0, ringArray.size)

        ringArray[ringArray.size - 2] = ringArray[0]
        ringArray[ringArray.size - 1] = ringArray[1]
        return ringArray
    }

    private fun readPoint(): Point {
        val xLong = unpacker.read()
        val yLong = unpacker.read()
        val x = (xLong + coordinatesSystem.offsetXLong) / coordinatesSystem.precision
        val y = (yLong + coordinatesSystem.offsetYLong) / coordinatesSystem.precision

        check(unpacker.remaining() == 0) {
            "Invalid point stream has unexpected ${unpacker.remaining()} bytes left after x/y coordinate."
        }

        return geometryFactory.createPoint(Coordinate(x, y))
    }

    private fun readLineString(): LineString {
        val count = readLongValues()

        check(count >= 4) { "Invalid point stream for LineString. Expected at least 4 values (2 x/y coordinates), got $count values." }

        val doubleArray = readDoubles(0, count)

        return geometryFactory.createLineString(PackedCoordinateSequence.Double(doubleArray, 2))
    }

    private fun readDoubles(offset: Int, count: Int): DoubleArray {
        val doubleArray = DoubleArray(count)

        for (idx0 in 0 until count step 2) {
            doubleArray[idx0] = longArray[offset + idx0] / coordinatesSystem.precision
            doubleArray[idx0 + 1] = longArray[offset + idx0 + 1] / coordinatesSystem.precision
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

        var ox = coordinatesSystem.offsetXLong
        var oy = coordinatesSystem.offsetYLong
        for (i in 0 until index step 2) {
            longArray[i]  = longArray[i] + ox
            longArray[i + 1]  = longArray[i + 1] + oy
            ox = longArray[i]
            oy = longArray[i + 1]
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
