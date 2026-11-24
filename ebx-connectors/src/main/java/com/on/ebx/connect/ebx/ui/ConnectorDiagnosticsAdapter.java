package com.on.ebx.connect.ebx.ui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.on.ebx.connect.core.BoundedInspectionRuntime;
import com.on.ebx.connect.core.ConnectorRegistry;
import com.on.ebx.connect.core.ConnectorRepository;
import com.on.ebx.connect.core.ConnectorStatusEvaluation;
import com.on.ebx.connect.core.ConnectorStatusEvaluator;
import com.on.ebx.connect.core.ConnectorConnectionStateService;
import com.on.ebx.connect.core.Redaction;
import com.on.ebx.connect.core.InspectionPreview;
import com.on.ebx.connect.spi.ConfigurationValidationIssue;
import com.on.ebx.connect.spi.ConfigurationValidationResult;
import com.on.ebx.connect.spi.ConnectionTestResult;
import com.on.ebx.connect.spi.Connector;
import com.on.ebx.connect.spi.ConnectorConfig;
import com.on.ebx.connect.spi.ConnectorContext;
import com.on.ebx.connect.spi.ConnectorDescriptor;
import com.on.ebx.connect.spi.ConnectorException;
import com.on.ebx.connect.spi.ConnectorOperationDescriptor;
import com.on.ebx.connect.spi.DiscoveredObject;
import com.on.ebx.connect.spi.ObjectDiscoveryRequest;
import com.on.ebx.connect.spi.ObjectDiscoveryResult;
import com.orchestranetworks.service.Session;
import com.on.ebx.connect.ebx.EbxConnectorConnectionStateStore;

/** Read-only bridge between the EBX diagnostics screen and the connector runtime. */
final class ConnectorDiagnosticsAdapter
{
	private static final BoundedInspectionRuntime INSPECTION = new BoundedInspectionRuntime();
	static final class Result
	{
		final boolean success;
		final String headline;
		final List<String> details;

		Result(final boolean pSuccess, final String pHeadline, final List<String> pDetails)
		{
			this.success = pSuccess;
			this.headline = safeText(pHeadline);
			final List<String> sanitized = new ArrayList<String>();
			if (pDetails != null)
			{
				for (final String detail : pDetails) { sanitized.add(safeText(detail)); }
			}
			this.details = Collections.unmodifiableList(sanitized);
		}
	}

	private ConnectorDiagnosticsAdapter() { }

	/** A diagnostics text in the language of the session (audit C-37); what a connection or the engine itself says stays as it is. */
	private static String text(final java.util.Locale pLocale, final String pKey, final Object... pArgs)
	{
		return Messages.get(ConnectorDiagnosticsAdapter.class, pLocale, pKey, pArgs);
	}

	static Result denied(final java.util.Locale pLocale)
	{
		return failure(text(pLocale, "diagnostics.denied"), text(pLocale, "diagnostics.denied.help"));
	}

	private static Result notFound(final java.util.Locale pLocale, final String pConnectorId)
	{
		return failure(text(pLocale, "diag.notFound"), text(pLocale, "diag.notFound.detail", pConnectorId));
	}

	static Result validate(final java.util.Locale pLocale, final ConnectorRepository pRepository, final String pConnectorId)
	{
		final ConnectorConfig config = pRepository.connector(pConnectorId);
		if (config == null) { return notFound(pLocale, pConnectorId); }
		final ConnectorStatusEvaluation evaluation = ConnectorStatusEvaluator.evaluate(config, ConnectorRegistry.get());
		final List<String> details = new ArrayList<String>();
		final ConfigurationValidationResult validation = evaluation.getValidation();
		if (validation != null)
		{
			for (final ConfigurationValidationIssue issue : validation.getIssues()) { details.add(issue.toString()); }
			for (final ConfigurationValidationIssue warning : validation.getWarnings()) { details.add(text(pLocale, "diag.warning", warning.toString())); }
		}
		if (details.isEmpty()) { details.add(text(pLocale, "diag.status", text(pLocale, "diag.status." + evaluation.getStatus().name()))); }
		final boolean valid = validation != null && validation.isValid();
		return new Result(valid, !valid ? text(pLocale, "diag.attention")
			: validation.getWarnings().isEmpty() ? text(pLocale, "diag.valid") : text(pLocale, "diag.validWithWarnings"), details);
	}

	/**
	 * Loads and configures the connector through the production registry. This exercises descriptor
	 * validation, secret resolution and the outbound allow-list without executing a business operation.
	 */
	static Result testConnection(final Session pSession, final ConnectorRepository pRepository, final String pConnectorId)
	{
		try
		{
			final Connector connector = ConnectorRegistry.get().connector(pConnectorId, pRepository);
			final ConnectionTestResult test = connector.testConnection(new ConnectorContext(pSession,
				pRepository.getRepository(), null, pRepository.dataset()));
			final List<String> details = new ArrayList<String>();
			final java.util.Locale locale = pSession.getLocale();
			details.add(text(locale, "diag.implementation", connector.getLabel()));
			details.add(test.getMessage()); // said by the connection itself: stays as it is
			addWarnings(details, locale, pRepository.connector(pConnectorId));
			if (test.isSupported()) { details.add(text(locale, "diag.elapsed", String.valueOf(test.getElapsedMillis()))); }
			else { details.add(text(locale, "diag.noProbe")); }
			if (test.isSupported() && !test.isSuccessful())
			{
				ConnectorDiagnosticsAdapter.addNextStep(details, pSession.getLocale(), test.getMessage());
			}
			return new Result(test.isSupported() && test.isSuccessful(), test.isSupported()
				? (test.isSuccessful() ? text(locale, "diag.test.passed") : text(locale, "diag.test.failed"))
				: text(locale, "diag.test.unsupported"), details);
		}
		catch (final ConnectorException ex) { return failure(text(pSession.getLocale(), "diag.test.failed"), safe(ex), pSession.getLocale(), ex); }
		catch (final RuntimeException ex) { return failure(text(pSession.getLocale(), "diag.test.failed"), safe(ex), pSession.getLocale(), ex); }
	}

	/** The non-blocking findings of the configuration (a deprecated flow that still works), one line each. */
	private static void addWarnings(final List<String> pDetails, final java.util.Locale pLocale, final ConnectorConfig pConfig)
	{
		if (pConfig == null) return;
		final ConfigurationValidationResult validation = ConnectorStatusEvaluator.evaluate(pConfig, ConnectorRegistry.get()).getValidation();
		if (validation == null) return;
		for (final ConfigurationValidationIssue warning : validation.getWarnings()) { pDetails.add(text(pLocale, "diag.warning", warning.toString())); }
	}

	static Result preview(final Session pSession, final ConnectorRepository pRepository, final String pConnectorId)
	{
		final java.util.Locale locale = pSession.getLocale();
		final ConnectorConfig config = pRepository.connector(pConnectorId);
		if (config == null) { return notFound(locale, pConnectorId); }
		try
		{
			final Connector connector = ConnectorRegistry.get().connector(pConnectorId, pRepository);
			final ConnectorDescriptor descriptor = connector.getDescriptor();
			final List<String> details = new ArrayList<String>();
			details.add(text(locale, "diag.category", String.valueOf(descriptor.getCategory())));
			details.add(text(locale, "diag.auth", String.valueOf(descriptor.getAuthenticationMethods())));
			final ConnectorContext context = new ConnectorContext(pSession,
				pRepository.getRepository(), null, pRepository.dataset());
			final ObjectDiscoveryResult discovery = INSPECTION.discover(connector, context, config,
				ObjectDiscoveryRequest.firstPage("", 25));
			if (discovery.isSupported())
			{
				for (final DiscoveredObject object : discovery.getObjects())
				{
					details.add(object.getLabel() + " (" + object.getId() + ") — " + object.getType());
				}
				final DiscoveredObject readable = firstReadable(discovery);
				if (readable != null)
				{
					final com.on.ebx.connect.spi.ObjectDescription description = INSPECTION.describe(
						connector, context, config, readable.getId());
					if (description.isSupported()) details.add(text(locale, "diag.schema", String.valueOf(description.getFields().size())));
					final InspectionPreview sample = INSPECTION.preview(connector, context, config,
						new com.on.ebx.connect.spi.PreviewRequest(readable.getId(), readable.getId(), null, 5));
					if (sample.isSupported())
					{
						details.add(text(locale, "diag.sample", String.valueOf(sample.getRecords().size()), String.valueOf(sample.getPayloadBytes()))
							+ (sample.isTruncated() ? text(locale, "diag.sample.cut") : ""));
						for (final com.fasterxml.jackson.databind.JsonNode record : sample.getRecords())
							details.add(com.on.ebx.connect.core.Json.write(record));
					}
					else details.add(sample.getMessage());
				}
			}
			else
			{
				for (final ConnectorOperationDescriptor operation : descriptor.getOperations())
				{
					details.add(operation.getName() + " — " + operation.getDescription());
				}
				details.add(discovery.getMessage());
			}
			return new Result(true, text(locale, "diag.discoverable",
				String.valueOf(discovery.isSupported() ? discovery.getObjects().size() : descriptor.getOperations().size())), details);
		}
		catch (final ConnectorException ex) { return failure(text(locale, "diag.preview.unavailable"), safe(ex), locale, ex); }
	}

	/** Tests first and only performs bounded, read-only discovery after a successful connection. */
	static Result connectAndDiscover(final Session pSession, final ConnectorRepository pRepository, final String pConnectorId)
	{
		final java.util.Locale locale = pSession.getLocale();
		final ConnectorConfig config = pRepository.connector(pConnectorId);
		if (config == null) { return notFound(locale, pConnectorId); }
		final ConnectorConnectionStateService states = new ConnectorConnectionStateService(
			new EbxConnectorConnectionStateStore(pRepository, pSession));
		try
		{
			final Connector connector = ConnectorRegistry.get().connector(pConnectorId, pRepository);
			final ConnectorContext context = new ConnectorContext(pSession, pRepository.getRepository(), null, pRepository.dataset());
			final ConnectorConnectionStateService.State connected = states.verifyAndRecord(connector, context, config);
			if (connected.getStatus() != ConnectorConnectionStateService.Status.CONNECTED)
			{
				final Result refused = failure(text(locale, "diag.verifyFailed"), connected.getMessage());
				final List<String> refusedDetails = new ArrayList<String>(refused.details);
				addNextStep(refusedDetails, pSession.getLocale(), connected.getMessage());
				return new Result(false, refused.headline, refusedDetails);
			}
		}
		catch (final ConnectorException ex) { return failure(text(locale, "diag.verifyFailed"), safe(ex), locale, ex); }
		final Result discovery = preview(pSession, pRepository, pConnectorId);
		final List<String> details = new ArrayList<String>();
		details.add(text(locale, "diag.verified"));
		details.add(discovery.headline);
		details.addAll(discovery.details);
		return new Result(discovery.success, discovery.success
			? text(locale, "diag.connected") : text(locale, "diag.connectedAttention"), details);
	}

	static Result disconnect(final Session pSession, final ConnectorRepository pRepository, final String pConnectorId)
	{
		final java.util.Locale locale = pSession.getLocale();
		final ConnectorConfig config = pRepository.connector(pConnectorId);
		if (config == null) { return notFound(locale, pConnectorId); }
		new ConnectorConnectionStateService(new EbxConnectorConnectionStateStore(pRepository, pSession)).disconnect(config);
		return new Result(true, text(locale, "diag.disconnected"), Collections.singletonList(text(locale, "diag.disconnected.detail")));
	}

	private static DiscoveredObject firstReadable(final ObjectDiscoveryResult pDiscovery)
	{
		for (final DiscoveredObject object : pDiscovery.getObjects()) if (object.isReadable()) return object;
		return null;
	}

	private static Result failure(final String pHeadline, final String pDetail)
	{
		final List<String> details = new ArrayList<String>();
		details.add(pDetail);
		return new Result(false, pHeadline, details);
	}

	/** A failure with what to do about it when it is one of the frequent ones (audit C-38): the cause and the next action, from the catalogue. */
	private static Result failure(final String pHeadline, final String pDetail, final java.util.Locale pLocale, final Throwable pError)
	{
		final List<String> details = new ArrayList<String>();
		details.add(pDetail);
		final ErrorTexts texts = ErrorTexts.of(pLocale, pError, null);
		if (texts.isKnown())
		{
			details.add(texts.getCause());
			details.add(texts.getAction());
		}
		return new Result(false, pHeadline, details);
	}

	/** The cause and next action of a failure text the catalogue knows, appended to the details. */
	private static void addNextStep(final List<String> pDetails, final java.util.Locale pLocale, final String pMessage)
	{
		final ErrorTexts texts = ErrorTexts.of(pLocale, pMessage, null);
		if (texts.isKnown())
		{
			pDetails.add(texts.getCause());
			pDetails.add(texts.getAction());
		}
	}

	private static String safe(final Throwable pError)
	{
		final String message = pError.getMessage();
		return message == null || message.trim().isEmpty() ? pError.getClass().getSimpleName() : Redaction.redact(message);
	}

	private static String safeText(final String pText)
	{
		return Redaction.redact(pText == null ? "" : pText);
	}
}
