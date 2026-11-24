package com.on.ebx.connect.ebx.ui;

import com.on.ebx.connect.core.ConnectorsPaths;
import com.onwbp.adaptation.Adaptation;
import com.orchestranetworks.schema.ValueFunction;
import com.orchestranetworks.schema.ValueFunctionContext;

/** {@code Connector/logoUrl}: the URL of the logo image (a plain string, usable as the image of a tile view). */
public final class LogoUrlFunction implements ValueFunction
{
	@Override
	public void setup(final ValueFunctionContext pContext)
	{
		// nothing to declare: the value is derived from the record's own logo field
	}

	@Override
	public Object getValue(final Adaptation pRecord)
	{
		final String uploaded = pRecord.getString(ConnectorsPaths.Connector.UPLOADED_LOGO);
		if (UploadedLogoWidgetFactory.isAcceptedDataUrl(uploaded)) { return uploaded; }
		final Object logo = pRecord.get(ConnectorsPaths.Connector.LOGO);
		final String value = logo == null ? null : pRecord.getSchemaNode().getNode(ConnectorsPaths.Connector.LOGO).formatToXsString(logo);
		final String url = Logos.url(value);
		return url == null ? Logos.defaultUrl() : url;
	}
}
