# Connector runtime libraries

This directory is the single source for third-party libraries owned by the
EBX-Connectors module. The build adds these JARs to its compile/test classpath
and packages them under `WEB-INF/lib` in `ebx-connectors.war`.

Licenses: `THIRD-PARTY-NOTICES.md` lists every JAR with its license, and the build
copies it, `licenses/` and the vendor license/notice files of this directory to
`META-INF/licenses/` in the war. A JAR added here without a line in
`THIRD-PARTY-NOTICES.md` fails the build.

Current libraries:

- `postgresql.jar` — PostgreSQL JDBC driver 42.7.13 (Java 8 build).
- `h2.jar` — H2 JDBC driver 2.1.212, packaged for the dedicated connector independently of the repository server process.
- `ojdbc8.jar` — Oracle JDBC driver 23.9.0.25.07, Java 8-compatible artifact.
- `mssql-jdbc.jar` — Microsoft JDBC driver 12.10.0 for Java 8, shared by SQL Server and Azure SQL offerings.
- `mysql-connector-j-26.7.0.jar` — Oracle MySQL Connector/J for the dedicated MySQL offering.
- `mongodb-driver-sync-5.11.0.jar`, `mongodb-driver-core-5.11.0.jar`, and `bson-5.11.0.jar` — MongoDB synchronous Java driver 5.11.0 (Java 8 bytecode), pinned above the proxy-credential logging fix introduced in 5.9.2. Its Apache-2.0 license and upstream third-party notices are preserved as `MONGODB-LICENSE.txt` and `MONGODB-THIRD-PARTY-NOTICES`.
- `avro-1.11.4.jar` — Apache Avro Object Container File runtime (Java 8 bytecode). Its Apache-2.0 license and notice are preserved as `AVRO-LICENSE.txt` and `AVRO-NOTICE.txt`.
- `jsch.jar` — SFTP/SSH client: `com.github.mwiede:jsch` 2.28.7, the maintained fork of JCraft JSch (same `com.jcraft.jsch` API; adds Ed25519 and modern algorithms, disables SHA-1 `ssh-rsa` by default).
- `kafka-clients.jar` — Apache Kafka client 3.9.2 (last line running on Java 8).
- `cassandra-driver-core-3.11.5-shaded.jar` — pinned Java 8 DataStax driver for the Cassandra connector (Apache License 2.0); the shaded artifact includes its compatible Netty and Metrics runtime.
- `guava-33.7.2-jre.jar` and `failureaccess-1.0.3.jar` — Apache License 2.0 Guava runtime required by DataStax driver 3.11.5 (which supports Guava 19 and later through its compatibility layer). SLF4J comes from `slf4j-api-1.7.32.jar` of this directory; optional JNR/native and compression artifacts are not required by this connector configuration.
- `couchbase-java-client-3.6.2.jar` and `couchbase-core-io-2.6.2.jar` — pinned Java 8-compatible Couchbase SDK for KV and collection-management APIs. Core IO shades its private transport dependencies; the packaged Reactor Core and Reactive Streams plus the packaged SLF4J API complete the runtime. Apache-2.0 terms and notice are preserved as `COUCHBASE-LICENSE.txt` and `COUCHBASE-NOTICE.txt`.
- `lz4-java-1.12.1.jar` (`at.yawk.lz4:lz4-java`, the maintained fork; `org.lz4` ends at a relocation), `snappy-java.jar`, `zstd-jni.jar` — Kafka compression codecs.
- `netty-*-4.1.137.Final*.jar` — ONE netty family (the line `azure-core-http-netty` 1.16.6 declares); never mix 4.1 and 4.2 artifacts. `MANIFEST.txt` lists the coordinates and sha256 of the jars replaced by audit lot L10.
- `amqp-client-5.35.0.jar` — RabbitMQ AMQP client.
- `activemq-client-5.19.11.jar`, `jakarta.jms-api-2.0.3.jar`, `hawtbuf-1.11.jar` — JMS and Apache ActiveMQ runtime.
- `azure-messaging-servicebus-7.17.20.jar`, `azure-identity-1.18.4.jar`, and their Azure Core, AMQP, Reactor, Netty, Proton, MSAL, JNA, and native transport dependencies — Microsoft Azure Service Bus runtime for connection-string and managed-identity authentication.

- `jackson-core-2.14.2.jar`, `jackson-databind-2.14.2.jar`, `jackson-annotations-2.14.2.jar`, `jackson-datatype-jsr310-2.14.2.jar` — Jackson 2.14.2 (Apache-2.0), and `slf4j-api-1.7.32.jar` — SLF4J API only, no binding (MIT).
  A **standard ON EBX 6.3 `lib/` has neither** (it holds `ebx.jar`, the database driver and `jakarta.mail`; ON EBX keeps a relocated private Jackson); the war used to rely on the `lib/` of a
  FastTrack workspace for them and was dead on a standard server (audit X3-07, measured 2026-11-12). They are copied byte for byte from the `lib/` of the FastTrack server
  (`jackson-core.jar`, `jackson-databind.jar`, `jackson-annotations.jar`, `slf4j-api.jar`), their SHA-256 are in `PINNED-WAR-JARS.txt`, and `tools/check_war_selfcontained.py` compares the copies inside the built war with it.
  To move to another version: copy the new jars here, then update `PINNED-WAR-JARS.txt`, `SHA256SUMS`, `MANIFEST.txt` and `THIRD-PARTY-NOTICES.md` together, and rebuild. Jackson must stay one line (core, databind, annotations, jsr310 at the same version).

Do not add EBX, Servlet/Tomcat or database-vendor API jars here: ON EBX and Tomcat supply those, and a second copy of them can cause classloader errors. A FastTrack server `lib/` also holds
Jackson and SLF4J; the web application loader reads `WEB-INF/lib` first, so the war uses its own copies (the same bytes today).
A library that a driver references and that is NOT here is either optional (listed with its reason in `tools/war-optional-refs.txt`, and per connection in `docs/OPERATIONS.md` section 1.1) or a gap
that `tools/check_war_selfcontained.py` reports and fails the build on.
Optional Excel or Parquet support belongs here as a self-contained
`DocumentCodecProvider` JAR and its non-container dependencies.

The module includes a dependency-free, bounded OOXML provider for the supported
first-sheet scalar `.xlsx` slice; legacy `.xls`, macros, formulas and embedded
objects are rejected or excluded. It never interprets binary workbook bytes as text.
No Parquet provider is currently approved or packaged, so Parquet fails explicitly.
Apache Parquet is optional and additionally brings a substantially larger Arrow/Hadoop
dependency surface; it remains deferred until a customer use case selects an approved,
Java-8-compatible provider stack. Provider jars must advertise canonical lower-case
formats through `META-INF/services/com.on.ebx.connect.spi.DocumentCodecProvider`.
The core rejects ambiguous providers, invalid row envelopes, row-limit violations,
empty output and missing content types at the module boundary.

After replacing or adding a library, rebuild the WAR and restart Tomcat. The
deployed module must not also retain a second copy of the same connector-owned
library in the shared Tomcat/EBX library directory.
