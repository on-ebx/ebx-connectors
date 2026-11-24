package com.on.ebx.connect.ebx.ui;

import java.util.Locale;

import com.on.ebx.connect.core.ConnectorRepository;
import com.on.ebx.connect.core.ConnectorsPaths;
import com.onwbp.adaptation.Adaptation;
import com.orchestranetworks.ui.UIButtonSpecNavigation;
import com.orchestranetworks.ui.UIButtonSpecSubmit;
import com.orchestranetworks.ui.selection.RecordEntitySelection;
import com.orchestranetworks.userservice.UserService;
import com.orchestranetworks.userservice.UserServiceDisplayConfigurator;
import com.orchestranetworks.userservice.UserServiceEvent;
import com.orchestranetworks.userservice.UserServiceEventContext;
import com.orchestranetworks.userservice.UserServiceEventOutcome;
import com.orchestranetworks.userservice.UserServiceObjectContextBuilder;
import com.orchestranetworks.userservice.UserServicePane;
import com.orchestranetworks.userservice.UserServicePaneContext;
import com.orchestranetworks.userservice.UserServicePaneWriter;
import com.orchestranetworks.userservice.UserServiceProcessEventOutcomeContext;
import com.orchestranetworks.userservice.UserServiceSetupDisplayContext;
import com.orchestranetworks.userservice.UserServiceSetupObjectContext;
import com.orchestranetworks.userservice.UserServiceValidateContext;
import com.orchestranetworks.service.Session;

/**
 * EBX-native action screen on the selected connector record: validate, test, connect, preview, disconnect. Nothing
 * writes business data; connect and disconnect record the connection state on the connector.
 * <p>
 * An action runs either from a button of this page, or on opening when the URL names it
 * ({@value DiagnosticsLaunch#ACTION_PARAMETER}) and proves it comes from a button of the connector form
 * ({@link DiagnosticsLaunch}). A URL without that proof runs nothing: a link alone must not be able to test,
 * connect or disconnect a connection with its stored credentials.
 */
public final class ConnectorDiagnosticsUserService implements UserService<RecordEntitySelection>
{
	private Adaptation connector;
	private ConnectorDiagnosticsAdapter.Result result;
	private boolean readable;
	/** The action and proof already run on opening: EBX sets the object context up again on every request of this page. */
	private String launched;
	/** The action a URL asked without proof, shown as an invitation to use the buttons until one of them is used. */
	private String unproven;

	static boolean isReadable(final Session pSession, final Adaptation pConnector)
	{
		return pSession != null && pConnector != null
			&& !pSession.getPermissions().getAdaptationAccessPermission(pConnector).isHidden();
	}

	@Override public void setupObjectContext(final UserServiceSetupObjectContext<RecordEntitySelection> pContext,
		final UserServiceObjectContextBuilder pBuilder)
	{
		this.connector = pContext.getEntitySelection().getRecord();
		this.readable = isReadable(pContext.getSession(), this.connector);
		final String requestedAction = pContext.getParameter(DiagnosticsLaunch.ACTION_PARAMETER);
		if (this.readable && DiagnosticsLaunch.isAction(requestedAction))
		{
			final String id = this.connector.getString(ConnectorsPaths.Connector.ID);
			final String token = pContext.getParameter(DiagnosticsLaunch.TOKEN_PARAMETER);
			if (!DiagnosticsLaunch.proven(token, pContext.getSession(), id, requestedAction))
			{
				// a link alone starts nothing: the user decides with the buttons of this page
				this.unproven = requestedAction;
				return;
			}
			final String launch = requestedAction + "|" + token;
			if (launch.equals(this.launched))
			{
				// already run when the page opened; a later request of the same page must not run it again
				return;
			}
			this.launched = launch;
			final ConnectorRepository repository = new ConnectorRepository(pContext.getRepository());
			if ("disconnect".equals(requestedAction))
			{
				this.result = ConnectorDiagnosticsAdapter.disconnect(pContext.getSession(), repository, id);
			}
			else if ("preview".equals(requestedAction))
			{
				this.result = ConnectorDiagnosticsAdapter.preview(pContext.getSession(), repository, id);
			}
			else if ("connect".equals(requestedAction))
			{
				this.result = ConnectorDiagnosticsAdapter.connectAndDiscover(pContext.getSession(), repository, id);
			}
			else { this.result = ConnectorDiagnosticsAdapter.testConnection(pContext.getSession(), repository, id); }
		}
	}

	@Override public void setupDisplay(final UserServiceSetupDisplayContext<RecordEntitySelection> pContext,
		final UserServiceDisplayConfigurator pConfigurator)
	{
		final Locale locale = pContext.getSession().getLocale();
		final String label = this.connector == null ? "Connector" : this.connector.getString(ConnectorsPaths.Connector.LABEL);
		pConfigurator.setTitle(Messages.get(ConnectorDiagnosticsUserService.class, locale, "diagnostics.title", label == null ? Messages.get(ConnectorDiagnosticsUserService.class, locale, "diagnostics.connector") : label));
		// This service is hosted by EBX's manager bridge when launched from the modern
		// workspace.  Do not redirect that bridge to an /ebx-ui URL on Close: EBX then
		// renders an entire modern workspace inside the bridge, producing a nested shell.
		// A plain native close returns to the already-open connector record and preserves
		// the outer workspace session.
		pConfigurator.setCloseCallback(new UserServiceEvent() {
			@Override public UserServiceEventOutcome processEvent(final UserServiceEventContext pEventContext)
			{
				return com.orchestranetworks.userservice.UserServiceNext.nextClose();
			}
		});
		final UIButtonSpecSubmit validate = button(pConfigurator, Messages.get(ConnectorDiagnosticsUserService.class, locale, "diagnostics.validate"), "ftcdValidate", new Action() {
			@Override public ConnectorDiagnosticsAdapter.Result run(final UserServiceEventContext pContext, final ConnectorRepository pRepository, final String pId)
			{
				return ConnectorDiagnosticsAdapter.validate(pContext.getSession().getLocale(), pRepository, pId);
			}
		});
		final UIButtonSpecSubmit test = button(pConfigurator, Messages.get(ConnectorDiagnosticsUserService.class, locale, "diagnostics.test"), "ftcdTest", new Action() {
			@Override public ConnectorDiagnosticsAdapter.Result run(final UserServiceEventContext pContext, final ConnectorRepository pRepository, final String pId)
			{
				return ConnectorDiagnosticsAdapter.testConnection(pContext.getSession(), pRepository, pId);
			}
		});
		final UIButtonSpecSubmit connect = button(pConfigurator, Messages.get(ConnectorDiagnosticsUserService.class, locale, "diagnostics.connect"), "ftcdConnect", new Action() {
			@Override public ConnectorDiagnosticsAdapter.Result run(final UserServiceEventContext pContext, final ConnectorRepository pRepository, final String pId)
			{
				return ConnectorDiagnosticsAdapter.connectAndDiscover(pContext.getSession(), pRepository, pId);
			}
		});
		final UIButtonSpecSubmit preview = button(pConfigurator, Messages.get(ConnectorDiagnosticsUserService.class, locale, "diagnostics.preview"), "ftcdPreview", new Action() {
			@Override public ConnectorDiagnosticsAdapter.Result run(final UserServiceEventContext pContext, final ConnectorRepository pRepository, final String pId)
			{
				return ConnectorDiagnosticsAdapter.preview(pContext.getSession(), pRepository, pId);
			}
		});
		final UIButtonSpecSubmit disconnect = button(pConfigurator, Messages.get(ConnectorDiagnosticsUserService.class, locale, "diagnostics.disconnect"), "ftcdDisconnect", new Action() {
			@Override public ConnectorDiagnosticsAdapter.Result run(final UserServiceEventContext pContext, final ConnectorRepository pRepository, final String pId)
			{
				return ConnectorDiagnosticsAdapter.disconnect(pContext.getSession(), pRepository, pId);
			}
		});
		final UIButtonSpecNavigation close = pConfigurator.newCloseButton();
		close.setAskBeforeLeavingModifiedForm(false);
		if (this.readable) { pConfigurator.setLeftButtons(validate, test, connect, preview, disconnect); }
		pConfigurator.setRightButtons(close);
		pConfigurator.setContent(new UserServicePane() {
			@Override public void writePane(final UserServicePaneContext pPaneContext, final UserServicePaneWriter pWriter)
			{
				ThemeTokens.write(pWriter, pPaneContext);
				ConnectorDiagnosticsUserService.this.write(pWriter, pPaneContext.getSession().getLocale());
			}
		});
	}

	private UIButtonSpecSubmit button(final UserServiceDisplayConfigurator pConfigurator, final String pLabel,
		final String pId, final Action pAction)
	{
		final UIButtonSpecSubmit button = pConfigurator.newSubmitButton(pLabel, new UserServiceEvent() {
			@Override public UserServiceEventOutcome processEvent(final UserServiceEventContext pContext)
			{
				ConnectorDiagnosticsUserService.this.unproven = null;
				if (!ConnectorDiagnosticsUserService.isReadable(pContext.getSession(), ConnectorDiagnosticsUserService.this.connector))
				{
					ConnectorDiagnosticsUserService.this.result = ConnectorDiagnosticsAdapter.denied(pContext.getSession().getLocale());
				}
				else
				{
					final String id = ConnectorDiagnosticsUserService.this.connector.getString(ConnectorsPaths.Connector.ID);
					ConnectorDiagnosticsUserService.this.result = pAction.run(pContext, new ConnectorRepository(pContext.getRepository()), id);
				}
				return null;
			}
		});
		button.setId(pId);
		return button;
	}

	private void write(final UserServicePaneWriter pWriter, final Locale pLocale)
	{
		pWriter.add("<style>.ftcd{max-width:900px;font-size:13px}.ftcd-card{border:1px solid #dedede;border-radius:8px;padding:18px;background:#fff}.ftcd h2{margin:0 0 5px}.ftcd p{color:#626262}.ftcd-result{margin-top:16px;padding:13px;border-radius:6px;background:#edf8f0;border:1px solid #b8dec3}.ftcd-result.bad{background:#fff4f2;border-color:#efc0b8}.ftcd ul{margin:8px 0 0 20px}</style>");
		pWriter.add("<div class=\"ftcd\"><div class=\"ftcd-card\"><h2>").add(MappingForm.escape(Messages.get(ConnectorDiagnosticsUserService.class, pLocale, "diagnostics.safe.title"))).add("</h2><p>").add(MappingForm.escape(Messages.get(ConnectorDiagnosticsUserService.class, pLocale, "diagnostics.safe.help"))).add("</p>");
		if (!this.readable)
		{
			pWriter.add("<div class=\"ftcd-result bad\"><strong>").add(MappingForm.escape(Messages.get(ConnectorDiagnosticsUserService.class, pLocale, "diagnostics.denied"))).add("</strong><ul><li>").add(MappingForm.escape(Messages.get(ConnectorDiagnosticsUserService.class, pLocale, "diagnostics.denied.help"))).add("</li></ul></div>");
		}
		if (this.readable && this.unproven != null && this.result == null)
		{
			pWriter.add("<div class=\"ftcd-result bad\"><strong>").add(MappingForm.escape(Messages.get(ConnectorDiagnosticsUserService.class, pLocale, "diagnostics.unproven",
				Messages.get(ConnectorDiagnosticsUserService.class, pLocale, "diagnostics." + this.unproven)))).add("</strong><ul><li>")
				.add(MappingForm.escape(Messages.get(ConnectorDiagnosticsUserService.class, pLocale, "diagnostics.unproven.help"))).add("</li></ul></div>");
		}
		if (this.result != null)
		{
			pWriter.add("<div class=\"ftcd-result").add(this.result.success ? "" : " bad").add("\"><strong>")
				.add(MappingForm.escape(this.result.headline)).add("</strong><ul>");
			for (final String detail : this.result.details) { pWriter.add("<li>").add(MappingForm.escape(detail)).add("</li>"); }
			pWriter.add("</ul></div>");
		}
		pWriter.add("</div></div>");
	}

	private interface Action { ConnectorDiagnosticsAdapter.Result run(UserServiceEventContext pContext, ConnectorRepository pRepository, String pId); }

	@Override public UserServiceEventOutcome processEventOutcome(final UserServiceProcessEventOutcomeContext<RecordEntitySelection> pContext,
		final UserServiceEventOutcome pOutcome) { return pOutcome; }

	@Override public void validate(final UserServiceValidateContext<RecordEntitySelection> pContext) { }
}
