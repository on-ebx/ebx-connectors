package com.on.ebx.connect.spi;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Immutable result of validating an instance against its type descriptor. */
public final class ConfigurationValidationResult
{
	private final List<ConfigurationValidationIssue> issues;
	private final List<ConfigurationValidationIssue> warnings;

	public ConfigurationValidationResult(final List<ConfigurationValidationIssue> pIssues)
	{
		this(pIssues, Collections.<ConfigurationValidationIssue>emptyList());
	}

	public ConfigurationValidationResult(final List<ConfigurationValidationIssue> pIssues,
		final List<ConfigurationValidationIssue> pWarnings)
	{
		this.issues = Collections.unmodifiableList(new ArrayList<ConfigurationValidationIssue>(pIssues));
		this.warnings = Collections.unmodifiableList(new ArrayList<ConfigurationValidationIssue>(pWarnings));
	}

	/** True when nothing blocks the configuration; warnings do not count. */
	public boolean isValid() { return this.issues.isEmpty(); }
	public List<ConfigurationValidationIssue> getIssues() { return this.issues; }
	/** Non-blocking findings, such as a deprecated value that still works. */
	public List<ConfigurationValidationIssue> getWarnings() { return this.warnings; }
}
