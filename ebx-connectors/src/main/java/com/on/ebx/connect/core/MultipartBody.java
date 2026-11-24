package com.on.ebx.connect.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.UUID;

/** Small immutable multipart/form-data builder suitable for REST connector uploads. */
public final class MultipartBody
{
	private final String boundary;
	private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
	public MultipartBody() { this("ebx-" + UUID.randomUUID().toString()); }
	MultipartBody(final String pBoundary) { this.boundary = pBoundary; }
	public String contentType() { return "multipart/form-data; boundary=" + this.boundary; }
	public MultipartBody field(final String pName, final String pValue) { return part(pName, null, "text/plain; charset=UTF-8", utf8(pValue)); }
	public MultipartBody file(final String pName, final String pFileName, final String pContentType, final byte[] pBytes) { return part(pName, pFileName, pContentType, pBytes); }
	public byte[] bytes() { return this.bytes.toByteArray(); }
	private MultipartBody part(final String pName, final String pFileName, final String pType, final byte[] pValue)
	{
		try
		{
			this.bytes.write(utf8("--" + this.boundary + "\r\nContent-Disposition: form-data; name=\"" + quote(pName) + "\""));
			if (pFileName != null) this.bytes.write(utf8("; filename=\"" + quote(pFileName) + "\""));
			this.bytes.write(utf8("\r\nContent-Type: " + (pType == null ? "application/octet-stream" : pType) + "\r\n\r\n"));
			this.bytes.write(pValue == null ? new byte[0] : pValue);
			this.bytes.write(utf8("\r\n"));
			return this;
		}
		catch (final IOException impossible) { throw new IllegalStateException(impossible); }
	}
	public byte[] finish()
	{
		final ByteArrayOutputStream complete = new ByteArrayOutputStream();
		try
		{
			complete.write(this.bytes.toByteArray());
			complete.write(utf8("--" + this.boundary + "--\r\n"));
			return complete.toByteArray();
		}
		catch (final IOException impossible) { throw new IllegalStateException(impossible); }
	}
	private static String quote(final String pValue) { return (pValue == null ? "" : pValue).replace("\\", "\\\\").replace("\"", "\\\"").replace("\r", "").replace("\n", ""); }
	private static byte[] utf8(final String pValue)
	{
		try
		{
			return (pValue == null ? "" : pValue).getBytes("UTF-8");
		}
		catch (final java.io.UnsupportedEncodingException ex)
		{
			throw new IllegalStateException(ex);
		}
	}
}
