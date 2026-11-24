package com.on.ebx.connect.ebx.ui;

import com.orchestranetworks.schema.Path;
import com.orchestranetworks.ui.ResourceType;
import com.orchestranetworks.ui.form.widget.UISimpleCustomWidget;
import com.orchestranetworks.ui.form.widget.UIWidgetFactory;
import com.orchestranetworks.ui.form.widget.WidgetDisplayContext;
import com.orchestranetworks.ui.form.widget.WidgetFactoryContext;
import com.orchestranetworks.ui.form.widget.WidgetFactorySetupContext;
import com.orchestranetworks.ui.form.widget.WidgetWriter;

/** {@code Connector/logo}: the image next to the resource field in forms, the image alone in table cells. */
public final class LogoWidgetFactory implements UIWidgetFactory<UISimpleCustomWidget>
{
	@Override
	public void setup(final WidgetFactorySetupContext pContext)
	{
		// nothing to configure
	}

	@Override
	public UISimpleCustomWidget newInstance(final WidgetFactoryContext pContext)
	{
		return new UISimpleCustomWidget(pContext)
		{
			@Override
			public void write(final WidgetWriter pWriter, final WidgetDisplayContext pContext)
			{
				final Object value = pContext.getValueContext().getValue();
				final String path = Logos.imagePath(value == null ? null : pContext.getNode().formatToXsString(value));
				final String url = path == null ? pWriter.getURLForResource(ResourceType.IMAGE, Logos.FOLDER + "default.svg")
						: pWriter.getURLForResource(ResourceType.IMAGE, path);
				final int height = pContext.isDisplayedInTable() ? 24 : 48;
				pWriter.add("<img src=\"" + url + "\" alt=\"\" style=\"height:" + height + "px;vertical-align:middle;margin-right:8px;border-radius:5px\"/>");
				if (!pContext.isDisplayedInTable())
				{
					// The native resource-field widget renders its value as unconstrained text --
					// long module-resource paths (ebx-connectors:ext-images:connect/logos/<file>)
					// overflow past the panel instead of wrapping (found live 2026-09-12). This
					// factory only draws the image; it doesn't otherwise control that widget's own
					// markup, so the fix is a wrapper this factory does own.
					pWriter.add("<span style=\"display:inline-block;max-width:calc(100% - 60px);"
						+ "overflow-wrap:break-word;word-break:break-all;vertical-align:middle\">");
					pWriter.addWidget(Path.SELF);
					pWriter.add("</span>");
				}
			}
		};
	}
}
