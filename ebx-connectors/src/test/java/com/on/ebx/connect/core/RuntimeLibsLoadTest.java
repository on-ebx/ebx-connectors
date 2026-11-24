package com.on.ebx.connect.core;

import java.io.File;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * The jars of runtime-libs/ load together, offline (audit C2-15). Run it with the runtime-libs/ jars ALONE on the class path
 * (no EBX library, no module classes): it proves that the netty artifacts are one 4.1 family, that the entry classes of the
 * Azure Service Bus client and of its netty HTTP client initialise on it, and that the LZ4 codec of the Kafka client loads
 * and round-trips bytes. No network is used. The APIs EBX supplies (slf4j-api, jackson-annotations/core/databind) are added by the
 * caller, nothing else.
 */
public final class RuntimeLibsLoadTest
{
	private static final String NETTY = "4.1.137.Final";

	public static void main(final String[] pArgs) throws Exception
	{
		nettyIsOneFamily();
		nettyClassesInitialise();
		azureEntryClassesLoad();
		lz4RoundTrip();
		kafkaLz4Streams();
		snappyRoundTrip();
		protonRoundTrip();
		System.out.println("PASS: runtime-libs/ load together offline: netty " + NETTY + " only, Azure Service Bus entry classes, kafka-clients with lz4, snappy-java native round trip, proton-j through azure-core-amqp");
	}

	private static void nettyIsOneFamily() throws Exception
	{
		// every netty-*.jar of the class path (the jars that shade their own netty under another package, couchbase-core-io and the
		// Cassandra driver, are not netty artifacts here) declares its version in META-INF/io.netty.versions.properties
		final Map<String, String> versions = new TreeMap<String, String>();
		for (final String entry : System.getProperty("java.class.path").split(File.pathSeparator))
		{
			final File jar = new File(entry);
			if (!jar.getName().startsWith("netty-") || !jar.getName().endsWith(".jar") || jar.getName().startsWith("netty-tcnative"))
			{
				continue;
			}
			final ZipFile zip = new ZipFile(jar);
			try
			{
				final ZipEntry e = zip.getEntry("META-INF/io.netty.versions.properties");
				check(e != null, jar.getName() + " carries no io.netty.versions.properties");
				final Properties props = new Properties();
				final InputStream in = zip.getInputStream(e);
				try
				{
					props.load(in);
				}
				finally
				{
					in.close();
				}
				final String artifact = jar.getName().replaceAll("-\\d.*$", "");
				final String v = props.getProperty(artifact + ".version");
				check(v != null, jar.getName() + " does not declare " + artifact + ".version");
				versions.put(jar.getName(), v);
			}
			finally
			{
				zip.close();
			}
		}
		check(versions.size() >= 15, "netty jars found on the class path: " + versions);
		for (final Map.Entry<String, String> e : versions.entrySet())
		{
			check(NETTY.equals(e.getValue()), e.getKey() + " is " + e.getValue() + ", expected " + NETTY + " (one family only)");
		}
		for (final String name : versions.keySet())
		{
			check(!name.startsWith("netty-codec-base") && !name.startsWith("netty-codec-compression"), name + " (netty 4.2 only) must not be on the class path");
		}
	}

	private static void nettyClassesInitialise() throws Exception
	{
		for (final String c : Arrays.asList("io.netty.buffer.PooledByteBufAllocator", "io.netty.channel.nio.NioEventLoopGroup",
			"io.netty.handler.codec.http.HttpClientCodec", "io.netty.handler.codec.http2.Http2FrameCodec",
			"io.netty.handler.proxy.HttpProxyHandler", "io.netty.resolver.dns.DnsNameResolverBuilder",
			"io.netty.handler.codec.dns.DatagramDnsQuery", "io.netty.handler.codec.socks.SocksMessage",
			"io.netty.handler.codec.compression.ZlibCodecFactory", "io.netty.handler.ssl.SslContextBuilder",
			"io.netty.channel.epoll.Epoll", "io.netty.channel.kqueue.KQueue"))
		{
			Class.forName(c, true, RuntimeLibsLoadTest.class.getClassLoader());
		}
		final Class<?> alloc = Class.forName("io.netty.buffer.PooledByteBufAllocator");
		final Object buffer = alloc.getMethod("buffer", int.class).invoke(alloc.getField("DEFAULT").get(null), 16);
		check(buffer != null, "a pooled buffer is allocated");
		buffer.getClass().getMethod("release").invoke(buffer);
	}

	private static void azureEntryClassesLoad() throws Exception
	{
		for (final String c : Arrays.asList("com.azure.messaging.servicebus.ServiceBusClientBuilder",
			"com.azure.messaging.servicebus.ServiceBusSenderClient", "com.azure.messaging.servicebus.ServiceBusReceiverClient",
			"com.azure.messaging.servicebus.ServiceBusMessage", "com.azure.core.amqp.AmqpTransportType",
			"com.azure.core.http.netty.NettyAsyncHttpClientProvider", "reactor.netty.http.client.HttpClient",
			"reactor.core.publisher.Mono"))
		{
			Class.forName(c, true, RuntimeLibsLoadTest.class.getClassLoader());
		}
		// building the HTTP client wires azure-core-http-netty to reactor-netty and to netty's transport, offline
		final Object builder = Class.forName("com.azure.core.http.netty.NettyAsyncHttpClientBuilder").getConstructor().newInstance();
		final Object client = builder.getClass().getMethod("build").invoke(builder);
		check(client != null, "the Azure netty HTTP client is built");
		// a message needs azure-core's Jackson adapter, hence jackson-datatype-jsr310 beside the server's Jackson
		final Object message = Class.forName("com.azure.messaging.servicebus.ServiceBusMessage").getConstructor(String.class).newInstance("hello");
		check(message != null, "a Service Bus message is built");
	}

	private static void lz4RoundTrip() throws Exception
	{
		final Class<?> factory = Class.forName("net.jpountz.lz4.LZ4Factory");
		final Object f = factory.getMethod("fastestInstance").invoke(null);
		final Object compressor = factory.getMethod("fastCompressor").invoke(f);
		final Object decompressor = factory.getMethod("fastDecompressor").invoke(f);
		final byte[] data = repeat("on-ebx connectors lz4 ", 400);
		final byte[] packed = (byte[]) compressor.getClass().getMethod("compress", byte[].class).invoke(compressor, (Object) data);
		check(packed.length < data.length / 4, "lz4 compresses: " + packed.length + " < " + data.length);
		final byte[] back = (byte[]) decompressor.getClass().getMethod("decompress", byte[].class, int.class).invoke(decompressor, packed, data.length);
		check(Arrays.equals(data, back), "lz4 decompresses to the original bytes");
	}

	private static void kafkaLz4Streams() throws Exception
	{
		// kafka-clients reaches lz4 through its own block stream: the class loads and finds lz4-java (no slf4j needed to load it)
		final Class<?> codec = Class.forName("org.apache.kafka.common.compress.Lz4BlockOutputStream", true, RuntimeLibsLoadTest.class.getClassLoader());
		check(codec != null, "kafka's Lz4BlockOutputStream loads");
		final Class<?> in = Class.forName("org.apache.kafka.common.compress.Lz4BlockInputStream", true, RuntimeLibsLoadTest.class.getClassLoader());
		check(in != null, "kafka's Lz4BlockInputStream loads");
		Class.forName("org.apache.kafka.clients.producer.KafkaProducer", false, RuntimeLibsLoadTest.class.getClassLoader());
		Class.forName("org.apache.kafka.clients.consumer.KafkaConsumer", false, RuntimeLibsLoadTest.class.getClassLoader());
	}

	/** Lot 1.4.1 (audit X3-08): snappy-java 1.1.10.12 loads its embedded native library on this machine and round-trips a buffer (Kafka's snappy compression uses exactly this class). */
	private static void snappyRoundTrip() throws Exception
	{
		final File snappyJar = new File(org.xerial.snappy.Snappy.class.getProtectionDomain().getCodeSource().getLocation().toURI());
		final ZipFile snappyZip = new ZipFile(snappyJar);
		final String version;
		try
		{
			final Properties manifest = new Properties();
			final InputStream mf = snappyZip.getInputStream(snappyZip.getEntry("META-INF/MANIFEST.MF"));
			try
			{
				manifest.load(mf);
			}
			finally
			{
				mf.close();
			}
			version = manifest.getProperty("Implementation-Version");
		}
		finally
		{
			snappyZip.close();
		}
		check("1.1.10.12".equals(version), "snappy-java on the class path is 1.1.10.12: " + version + " (" + snappyJar.getName() + ")");
		final byte[] data = repeat("on-ebx connectors snappy ", 800);
		final byte[] packed = org.xerial.snappy.Snappy.compress(data);
		check(packed.length < data.length / 4, "snappy compresses: " + packed.length + " < " + data.length);
		check(Arrays.equals(data, org.xerial.snappy.Snappy.uncompress(packed)), "snappy decompresses to the original bytes");
		// the framed stream pair, the one Kafka's SnappyFactory wraps
		final java.io.ByteArrayOutputStream framed = new java.io.ByteArrayOutputStream();
		final org.xerial.snappy.SnappyOutputStream out = new org.xerial.snappy.SnappyOutputStream(framed);
		out.write(data);
		out.close();
		final org.xerial.snappy.SnappyInputStream in = new org.xerial.snappy.SnappyInputStream(new java.io.ByteArrayInputStream(framed.toByteArray()));
		final java.io.ByteArrayOutputStream back = new java.io.ByteArrayOutputStream();
		final byte[] buffer = new byte[4096];
		for (int n = in.read(buffer); n >= 0; n = in.read(buffer))
		{
			back.write(buffer, 0, n);
		}
		check(Arrays.equals(data, back.toByteArray()), "the snappy stream pair round-trips");
		System.out.println("SNAPPY snappy-java " + version + " (native library " + org.xerial.snappy.Snappy.getNativeLibraryVersion() + ") loaded and round-tripped " + data.length + " bytes (" + packed.length + " packed)");
	}

	/**
	 * Lot 1.4.1 (audit X3-08): proton-j 0.35.0 is used by azure-core-amqp, offline. The Service Bus serializer of the war (package-private class of
	 * azure-messaging-servicebus, which calls azure-core-amqp, which calls proton-j) turns a ServiceBusMessage into a proton Message; the message is
	 * encoded to AMQP bytes and decoded again; a proton engine connection produces its AMQP header and open frame.
	 */
	private static void protonRoundTrip() throws Exception
	{
		final Class<?> serializerClass = Class.forName("com.azure.messaging.servicebus.ServiceBusMessageSerializer");
		final java.lang.reflect.Constructor<?> constructor = serializerClass.getDeclaredConstructor();
		constructor.setAccessible(true);
		final Object serializer = constructor.newInstance();
		final Method serialize = serializerClass.getMethod("serialize", Object.class);
		serialize.setAccessible(true);
		final com.azure.messaging.servicebus.ServiceBusMessage source = new com.azure.messaging.servicebus.ServiceBusMessage("hello proton");
		source.setMessageId("m-1");
		source.getApplicationProperties().put("origin", "offline-test");
		final org.apache.qpid.proton.message.Message message = (org.apache.qpid.proton.message.Message) serialize.invoke(serializer, source);
		check("m-1".equals(message.getMessageId()), "azure-core-amqp built a proton message with the id");
		final byte[] wire = new byte[4096];
		final int length = message.encode(wire, 0, wire.length);
		check(length > 0, "the proton message is encoded: " + length + " bytes");
		final org.apache.qpid.proton.message.Message decoded = org.apache.qpid.proton.message.Message.Factory.create();
		decoded.decode(wire, 0, length);
		check("m-1".equals(decoded.getMessageId()), "the decoded message keeps its id");
		check(decoded.getBody() != null, "the decoded message has a body");
		check("offline-test".equals(decoded.getApplicationProperties().getValue().get("origin")), "the application property written by azure-core-amqp survives the AMQP round trip");
		// the engine: an AMQP connection opened and bound to a transport writes its protocol header and open frame, with no socket
		final org.apache.qpid.proton.engine.Connection connection = org.apache.qpid.proton.engine.Connection.Factory.create();
		connection.setHostname("localhost");
		final org.apache.qpid.proton.engine.Transport transport = org.apache.qpid.proton.engine.Transport.Factory.create();
		transport.bind(connection);
		connection.open();
		check(transport.pending() >= 8, "the proton transport has the AMQP header and the open frame to send: " + transport.pending());
		final java.nio.ByteBuffer head = transport.head();
		check(head.get(0) == 'A' && head.get(1) == 'M' && head.get(2) == 'Q' && head.get(3) == 'P', "the first bytes of the connection are the AMQP protocol header");
		System.out.println("PROTON " + new File(org.apache.qpid.proton.engine.Connection.class.getProtectionDomain().getCodeSource().getLocation().toURI()).getName()
			+ " used by azure-core-amqp: Service Bus message serialised, encoded (" + length + " bytes) and decoded; engine writes the AMQP header");
	}

	private static byte[] repeat(final String pText, final int pTimes)
	{
		final StringBuilder sb = new StringBuilder();
		for (int i = 0; i < pTimes; i++)
		{
			sb.append(pText);
		}
		return sb.toString().getBytes(StandardCharsets.UTF_8);
	}

	private static void check(final boolean pOk, final String pWhat)
	{
		if (!pOk)
		{
			throw new AssertionError(pWhat);
		}
	}
}
