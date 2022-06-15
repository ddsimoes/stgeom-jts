# STGeometry parser

Parser/reader for the ArcSDE compressed binary stream format for Kotlin and Java.

Build geometries in the JTS (Java Topology Suite) format.

## Usage

```kotlin
    //parse a point stream
    fun parsePoint(bytes: ByteArray): Point {
        //These values should be read from st_spatial_references table
        val srs = StCoordinatesSystem(-400.0, -400.0, -100000.0, 1000000000.0)
        val reader  = StGeometryReader(srs)
        return reader.read(StType.POINT, bytes) as Point
    }
```
