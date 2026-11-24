package com.on.ebx.connect.ebx.ui;

import java.util.Locale;

import com.on.ebx.connect.core.ModuleVersion;
import com.orchestranetworks.ui.UIButtonSpecNavigation;
import com.orchestranetworks.ui.selection.DatasetEntitySelection;
import com.orchestranetworks.userservice.UserService;
import com.orchestranetworks.userservice.UserServiceDisplayConfigurator;
import com.orchestranetworks.userservice.UserServiceEventOutcome;
import com.orchestranetworks.userservice.UserServiceObjectContextBuilder;
import com.orchestranetworks.userservice.UserServicePane;
import com.orchestranetworks.userservice.UserServicePaneContext;
import com.orchestranetworks.userservice.UserServicePaneWriter;
import com.orchestranetworks.userservice.UserServiceProcessEventOutcomeContext;
import com.orchestranetworks.userservice.UserServiceSetupDisplayContext;
import com.orchestranetworks.userservice.UserServiceSetupObjectContext;
import com.orchestranetworks.userservice.UserServiceValidateContext;

/** Read-only page: the running version and links to the documentation (all the markup is in {@link AboutPage}). */
public final class AboutUserService implements UserService<DatasetEntitySelection>
{
	private Locale locale = Locale.ENGLISH;

	@Override public void setupObjectContext(final UserServiceSetupObjectContext<DatasetEntitySelection> pContext,
		final UserServiceObjectContextBuilder pBuilder) { }

	@Override public void setupDisplay(final UserServiceSetupDisplayContext<DatasetEntitySelection> pContext,
		final UserServiceDisplayConfigurator pConfigurator)
	{
		this.locale = pContext.getSession().getLocale();
		pConfigurator.setTitle(Messages.getInfo(AboutUserService.class, "about.label"));
		final UIButtonSpecNavigation close = pConfigurator.newCloseButton();
		close.setAskBeforeLeavingModifiedForm(false);
		pConfigurator.setRightButtons(close);
		pConfigurator.setContent(new UserServicePane()
		{
			@Override public void writePane(final UserServicePaneContext pPaneContext, final UserServicePaneWriter pWriter)
			{
				ThemeTokens.write(pWriter, pPaneContext);
				pWriter.add(AboutPage.render(AboutUserService.this.locale, ModuleVersion.version(), ModuleVersion.commit(), ModuleVersion.builtAt(),
					AboutPage.ebxVersion()));
				pWriter.addJS_cr(AboutPage.COPY_SCRIPT);
			}
		});
	}

	@Override public UserServiceEventOutcome processEventOutcome(final UserServiceProcessEventOutcomeContext<DatasetEntitySelection> pContext,
		final UserServiceEventOutcome pOutcome) { return pOutcome; }
	@Override public void validate(final UserServiceValidateContext<DatasetEntitySelection> pContext) { }
}
