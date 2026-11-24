# Third-party components packaged in ebx-connectors.war

Every JAR of `runtime-libs/` is copied to `WEB-INF/lib` of the war. This file,
the license texts of `licenses/` and the vendor files of this directory
(`*-LICENSE.txt`, `*-NOTICE*`) are copied to `META-INF/licenses/` of the war.
`build-test.sh` fails when a JAR of `runtime-libs/` is not listed below.

License texts: `licenses/Apache-2.0.txt`, `licenses/MIT-Microsoft.txt`,
`licenses/MIT-0-reactive-streams.txt`, `licenses/BSD-3-Clause-JSch.txt`,
`licenses/BSD-2-Clause-zstd-jni.txt`, `licenses/MIT-slf4j.txt`. Upstream NOTICE files that the JARs do not
embed: `licenses/NOTICE-netty.txt`, `licenses/NOTICE-kafka.txt`,
`licenses/NOTICE-snappy-java.txt`, `licenses/NOTICE-jackson.txt`. JARs marked *embedded* carry their own
`META-INF/LICENSE` / `NOTICE`, which stay inside the JAR.

| JAR | Component | License | Notice / text |
|---|---|---|---|
| `activemq-client-5.19.11.jar` | Apache ActiveMQ Classic client | Apache-2.0 | embedded |
| `amqp-client-5.35.0.jar` | RabbitMQ Java client | Apache-2.0 (dual MPL-2.0 / GPL-2.0, Apache-2.0 elected) | Apache-2.0.txt |
| `avro-1.11.4.jar` | Apache Avro Object Container File runtime | Apache-2.0 | AVRO-LICENSE.txt, AVRO-NOTICE.txt |
| `azure-core-1.59.0.jar` | Azure SDK core | MIT | MIT-Microsoft.txt |
| `azure-core-amqp-2.12.1.jar` | Azure SDK core AMQP | MIT | MIT-Microsoft.txt |
| `azure-core-http-netty-1.16.6.jar` | Azure SDK Netty HTTP client | MIT | MIT-Microsoft.txt |
| `azure-identity-1.18.4.jar` | Azure Identity | MIT | MIT-Microsoft.txt |
| `azure-json-1.5.1.jar` | Azure JSON | MIT | MIT-Microsoft.txt |
| `azure-messaging-servicebus-7.17.20.jar` | Azure Service Bus client | MIT | MIT-Microsoft.txt |
| `azure-xml-1.2.1.jar` | Azure XML | MIT | MIT-Microsoft.txt |
| `bson-5.11.0.jar` | MongoDB BSON | Apache-2.0 | MONGODB-LICENSE.txt, MONGODB-THIRD-PARTY-NOTICES |
| `cassandra-driver-core-3.11.5-shaded.jar` | DataStax Java driver for Apache Cassandra (shaded Netty, Metrics) | Apache-2.0 | Apache-2.0.txt, NOTICE-netty.txt |
| `couchbase-core-io-2.6.2.jar` | Couchbase JVM Core IO | Apache-2.0 | embedded, COUCHBASE-LICENSE.txt, COUCHBASE-NOTICE.txt |
| `couchbase-java-client-3.6.2.jar` | Couchbase Java SDK | Apache-2.0 | COUCHBASE-LICENSE.txt, COUCHBASE-NOTICE.txt |
| `guava-33.7.2-jre.jar` | Google Guava 33.7.2 | Apache-2.0 | Apache-2.0.txt |
| `failureaccess-1.0.3.jar` | Google Guava failureaccess 1.0.3 (Guava runtime dependency) | Apache-2.0 | Apache-2.0.txt |
| `h2.jar` | H2 Database Engine 2.1.212 | MPL-2.0 or EPL-1.0 (dual); source: https://github.com/h2database/h2database | https://h2database.com/html/license.html |
| `hawtbuf-1.11.jar` | FuseSource HawtBuf | Apache-2.0 | Apache-2.0.txt |
| `jakarta.jms-api-2.0.3.jar` | Jakarta Messaging API | EPL-2.0 or GPL-2.0 with Classpath Exception; source: https://github.com/jakartaee/messaging | embedded |
| `jackson-annotations-2.14.2.jar` | Jackson annotations 2.14.2 (FasterXML; the module's JSON layer; a standard ON EBX lib/ has none, ON EBX keeps a relocated private copy) | Apache-2.0 | embedded, Apache-2.0.txt |
| `jackson-core-2.14.2.jar` | Jackson core 2.14.2 (FasterXML) | Apache-2.0 | embedded, Apache-2.0.txt, NOTICE-jackson.txt |
| `jackson-databind-2.14.2.jar` | Jackson databind 2.14.2 (FasterXML) | Apache-2.0 | embedded, Apache-2.0.txt |
| `jackson-datatype-jsr310-2.14.2.jar` | Jackson datatype JSR-310 (same version as the Jackson above; needed by azure-core) | Apache-2.0 | Apache-2.0.txt |
| `jna-5.13.0.jar` | Java Native Access | Apache-2.0 or LGPL-2.1 (dual, Apache-2.0 elected) | embedded |
| `jna-platform-5.17.0.jar` | Java Native Access platform | Apache-2.0 or LGPL-2.1 (dual, Apache-2.0 elected) | embedded |
| `jsch.jar` | JSch 2.28.7 (com.github.mwiede fork, maintained successor of JCraft JSch) | BSD-3-Clause | BSD-3-Clause-JSch.txt |
| `kafka-clients.jar` | Apache Kafka clients 3.9.2 | Apache-2.0 | Apache-2.0.txt, NOTICE-kafka.txt |
| `lz4-java-1.12.1.jar` | LZ4 for Java 1.12.1 (`at.yawk.lz4`, the maintained fork of `org.lz4`) | Apache-2.0 | Apache-2.0.txt |
| `mongodb-driver-core-5.11.0.jar` | MongoDB Java driver core | Apache-2.0 | MONGODB-LICENSE.txt, MONGODB-THIRD-PARTY-NOTICES |
| `mongodb-driver-sync-5.11.0.jar` | MongoDB Java driver sync | Apache-2.0 | MONGODB-LICENSE.txt, MONGODB-THIRD-PARTY-NOTICES |
| `msal4j-1.23.1.jar` | Microsoft Authentication Library for Java | MIT | MIT-Microsoft.txt |
| `msal4j-persistence-extension-1.3.0.jar` | MSAL4J persistence extension | MIT | MIT-Microsoft.txt |
| `mssql-jdbc.jar` | Microsoft JDBC Driver for SQL Server 12.10.0 | MIT | MIT-Microsoft.txt |
| `mysql-connector-j-26.7.0.jar` | MySQL Connector/J 26.7.0 (Oracle) | GPL-2.0 with the Oracle Universal FOSS Exception 1.0, or Oracle commercial license. **Redistribution inside a non-GPL product must be cleared before release.** | see https://github.com/mysql/mysql-connector-j |
| `netty-*.jar` (all Netty 4.1.137.Final artifacts, including `netty-tcnative-*`) | The Netty Project | Apache-2.0 | Apache-2.0.txt, NOTICE-netty.txt; `netty-tcnative-boringssl-static-*` embedded (BoringSSL: OpenSSL / ISC licenses) |
| `ojdbc8.jar` | Oracle JDBC driver 23.9.0.25.07 | Oracle Free Distribution, Hosting, and Use Terms and Conditions (redistribution allowed unmodified, with these terms) | embedded (`META-INF/license.txt`) |
| `postgresql.jar` | PostgreSQL JDBC driver 42.7.13 | BSD-2-Clause | embedded |
| `proton-j-0.34.1.jar` | Apache Qpid Proton-J | Apache-2.0 | embedded |
| `qpid-proton-j-extensions-1.2.6.jar` | Qpid Proton-J extensions (Microsoft) | MIT | MIT-Microsoft.txt |
| `reactive-streams-1.0.4.jar` | Reactive Streams | MIT-0 | MIT-0-reactive-streams.txt |
| `reactor-core-3.7.19.jar` | Project Reactor core | Apache-2.0 | embedded |
| `reactor-netty-core-1.2.18.jar` | Reactor Netty core | Apache-2.0 | embedded |
| `reactor-netty-http-1.2.18.jar` | Reactor Netty HTTP | Apache-2.0 | embedded |
| `slf4j-api-1.7.32.jar` | SLF4J API 1.7.32 (QOS.ch), API only, no binding: logging of the drivers (Kafka, ActiveMQ, AMQP, MSAL4J, Couchbase...) is a no-op unless the server supplies a binding | MIT | MIT-slf4j.txt |
| `snappy-java.jar` | snappy-java (Xerial) | Apache-2.0 | Apache-2.0.txt, NOTICE-snappy-java.txt |
| `zstd-jni.jar` | zstd-jni | BSD-2-Clause | BSD-2-Clause-zstd-jni.txt |
