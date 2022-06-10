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
import com.vividsolutions.jts.geom.MultiPolygon
import com.vividsolutions.jts.geom.Point
import com.vividsolutions.jts.geom.Polygon
import com.vividsolutions.jts.io.WKTReader
import org.supercsv.io.CsvListReader
import org.supercsv.prefs.CsvPreference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private class StData(
    val bytes: ByteArray,
    val geometry: Geometry
) {

    companion object {
        private fun String.bytesFromHex(): ByteArray {
            val byteArray = ByteArray(this.length / 2)

            for (i in 0..byteArray.lastIndex) {
                byteArray[i] = this.hexToByte(i * 2)
            }

            return byteArray
        }


        private fun String.hexToByte(i: Int): Byte {
            return (this[i].hexToInt() shl 4 or this[i + 1].hexToInt()).toByte()
        }

        private fun Char.hexToInt(): Int {
            return when (this) {
                in '0'..'9' -> this.code - '0'.code
                in 'a'..'f' -> 10 + this.code - 'a'.code
                in 'A'..'F' -> 10 + this.code - 'A'.code
                else -> error("Invalid hex character '$this'")
            }
        }
    }

    constructor(hexBytes: String, wkt: String):
            this(hexBytes.replace(" ", "").bytesFromHex(), WKTReader().read(wkt))

    constructor(hexBytes: String, x: Double, y: Double):
            this(hexBytes.replace(" ", "").bytesFromHex(), GeometryFactory().createPoint(Coordinate(x, y)))

}


class StGeometryReaderTest {
    private val examples1 = listOf(
        StData("0c000000010000008080dd9da4178080dd9da417", 0.0, 0.0),
        StData("0c0000000100000080a8b3d7ab1780a8b3d7ab17", 1.0, 1.0),
        StData("0c0000000100000080d08991b31780d08991b317", 2.0, 2.0),
        StData("0c0000000100000080d08991b31780d08991b317", 2.0, 2.0),
        StData("0c0000000100000080d89acff91a80d89acff91a", 63.0, 63.0),
        StData("0c000000010000008080f188811b8080f188811b", 64.0, 64.0),
        StData("0c0000000100000080d8aebad61e80d8aebad61e", 127.0, 127.0),
        StData("0c00000001000000808085f4dd1e808085f4dd1e", 128.0, 128.0),
        StData("0c0000000100000080d8d690902680d8d6909026", 255.0, 255.0),
        StData("0c000000010000008080adca97268080adca9726", 256.0, 256.0),
        StData("0e000000010000008080ddeeb8cc748080ddeeb8cc74", 256000.0, 256000.0),
        StData("0c0000000100000080848cfda41780848cfda417", 0.1, 0.1),
        StData("0c000000010000008088bbdca5178088bbdca517", 0.2, 0.2),
        StData("0c0000000100000080ded086a51780ded086a517", 0.11, 0.11),
        StData("0c0000000100000080b89590a51780b89590a517", 0.12000000000000001, 0.12000000000000001),
    )

    private val srs0 = StCoordinatesSystem(-400.0,	-400.0,	-100000.0, 1/1000000000.0)

    /**
     * Tests the reader for reading of synthetic (not real life) Point only values.
     */
    @Test
    fun testReaderSyntheticPoints() {
        val reader = StGeometryReader(srs0)

        examples1.forEach { example ->
            val stGeom = reader.read(StType.POINT, example.bytes)

            assertTrue(stGeom is Point)
            assertTrue(example.geometry.equalsExact(stGeom, srs0.resolution * 10), "\nExpected :${example.geometry}\nActual   :$stGeom\n")
        }
    }

    @Test
    fun testLines() {
        testLines(StCoordinatesSystem(-500000.0, -8800000.0, 0.0, 0.001), "/lines.csv")
    }

    @Test
    fun testLines2() {
        testLines(srs0, "/lines2.csv")
    }

    private fun testLines(srs: StCoordinatesSystem, csv: String) {
        val reader = StGeometryReader(srs)
        this::class.java.getResourceAsStream(csv)!!.reader().use {
            val csvReader = CsvListReader(it, CsvPreference.STANDARD_PREFERENCE)

            var line = csvReader.read()

            while (line != null) {
                val hexBytes = line[2].trim()
                val wkt = line[3].trim()

                val example = StData(hexBytes, wkt)

                val stGeom = reader.read(StType.LINESTRING, example.bytes)

                assertEquals(LineString::class, stGeom::class)

                assertTrue(
                    example.geometry.equalsExact(stGeom, srs.resolution),
                    "\nExpected :${example.geometry}\nActual   :$stGeom\n"
                )

                line = csvReader.read()
            }
        }
    }

    @Test
    fun testPolygons() {
        val srs = srs0
        val csvPath = "/polygons.csv"
        val reader = StGeometryReader(srs)
        this::class.java.getResourceAsStream(csvPath)!!.reader().use {
            val csv = CsvListReader(it, CsvPreference.STANDARD_PREFERENCE)

            var line = csv.read()

            while (line != null) {
                val hexBytes = line[2].trim()
                val wkt = line[3].trim()

                val example = StData(hexBytes, wkt)

                val stGeom = reader.read(StType.POLYGON, example.bytes)

                assertEquals(Polygon::class, stGeom::class)

                assertTrue(
                    example.geometry.equalsExact(stGeom, srs.resolution * 10),
                    "\nExpected :${example.geometry}\nActual   :$stGeom\n"
                )

                line = csv.read()
            }
        }
    }

    @Test
    fun testMultiPolygons() {
        val srs = srs0
        val csv = "/mpolygons.csv"
        val reader = StGeometryReader(srs)
        this::class.java.getResourceAsStream(csv)!!.reader().use {
            val csvReader = CsvListReader(it, CsvPreference.STANDARD_PREFERENCE)

            var line = csvReader.read()

            while (line != null) {
                val hexBytes = line[2].trim()
                val wkt = line[3].trim()

                val example = StData(hexBytes, wkt)

                val stGeom = reader.read(StType.MULTIPOLYGON, example.bytes)

                assertEquals(MultiPolygon::class, stGeom::class)

                val exampleCoords = example.geometry.coordinates
                val stCoords = stGeom.coordinates

                assertEquals(exampleCoords.size, stCoords.size)

                assertTrue(
                    example.geometry.equalsExact(stGeom, srs.resolution * 10),
                    "\nExpected :${example.geometry}\nActual   :$stGeom\n"
                )

                line = csvReader.read()
            }
        }
    }
}
