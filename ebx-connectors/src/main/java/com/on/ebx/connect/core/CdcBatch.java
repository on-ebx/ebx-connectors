package com.on.ebx.connect.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** A bounded batch and its candidate checkpoint. Persist the checkpoint only after apply succeeds. */
public final class CdcBatch
{
	private final List<CdcEvent> events;
	private final String candidateCheckpoint;

	CdcBatch(final List<CdcEvent> pEvents, final String pCandidateCheckpoint)
	{
		this.events = Collections.unmodifiableList(new ArrayList<CdcEvent>(pEvents));
		this.candidateCheckpoint = pCandidateCheckpoint;
	}
	public List<CdcEvent> getEvents() { return this.events; }
	public String getCandidateCheckpoint() { return this.candidateCheckpoint; }
}
