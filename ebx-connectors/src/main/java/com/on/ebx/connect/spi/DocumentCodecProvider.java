package com.on.ebx.connect.spi;

import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Optional binary document codec discovered through {@link java.util.ServiceLoader}.
 * A provider jar declares this interface in
 * {@code META-INF/services/com.on.ebx.connect.spi.DocumentCodecProvider}; this keeps optional
 * Excel and Parquet libraries outside the core module and its class loader until installed.
 */
public interface DocumentCodecProvider
{
	/** Lower-case format names and aliases handled by this provider, for example xlsx/excel. */
	Set<String> getFormats();

	/** Parses at most {@code pMaxRows}; implementations must reject content above {@code pMaxBytes}. */
	ObjectNode parse(byte[] pContent, String pFormat, int pMaxRows, int pMaxBytes) throws ConnectorException;

	/** Encodes rows and must reject output above {@code pMaxBytes}. */
	byte[] serialise(List<JsonNode> pRows, String pFormat, int pMaxBytes) throws ConnectorException;

	String getContentType(String pFormat);
}
