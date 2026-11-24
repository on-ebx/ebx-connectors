# ebx-connectors-extension-sample

A **third-party connector** for the `ebx-connectors` module, built without the module sources: it
shows how to add a connector type from a separate jar.

- `src/main/java/com/example/ebx/connect/NominatimConnector.java`: the connector (OpenStreetMap
  Nominatim geocoder, no key). It extends `AbstractRestConnector` from the SPI jar and declares two
  operations, `geocode` and `reverse`.
- `src/main/resources/META-INF/services/com.on.ebx.connect.spi.Connector`: the registration file,
  one class name per line. The module loads it with `java.util.ServiceLoader` at startup.
- `lib/ebx-connectors-spi.jar`: the compile-time contract (interface `Connector`, `ConnectorConfig`,
  `ConnectorContext`, `Operation`, `FieldInfo`, `ConnectorException`, helpers `AbstractRestConnector`,
  `HttpJsonClient`, `Json`). Built by `ebx-connectors/build-spi.sh`; the sources jar is next to it.
- `build.sh`: compiles against the SPI jar, the EBX API jar (`ebx.jar` of a standard installation or `ebx-lib.jar` of FastTrack) and Jackson (the copies of `ebx-connectors/runtime-libs/` when this folder sits beside it, else those of the server `lib` folder),
  and produces `target/ebx-connectors-extension-sample.jar`.

## Deploying

1. Copy `target/ebx-connectors-extension-sample.jar` into `ebx-connectors/WEB-INF/lib/` (in this
   workspace: `ebx-connectors/src/main/webapp/WEB-INF/lib/`). Third-party libraries the connector
   needs go to the same folder, or to Tomcat's shared `lib`.
2. Restart Tomcat. The type `nominatim` is now in the `Connector/type` list.
3. Create a connector of that type (base URL `https://nominatim.openstreetmap.org`, authentication
   `None`, property `user.agent` = an address that identifies you, as Nominatim's policy requires),
   then a mapping with the graphical designer: **Load the external fields** shows the fields declared
   by `describeFields`.

## Writing your own

Copy this project, rename the class and the `getType()` value, list the operations (`Operation.read`,
`search`, `write` with their required inputs), implement `execute` (return a `JsonNode`, or `null` when
the service knows nothing), and optionally `describeFields` for the designer. Keep the Java 8 syntax
(`--release 8`): the module runs the extension on the server's JVM but is compiled for Java 8.
