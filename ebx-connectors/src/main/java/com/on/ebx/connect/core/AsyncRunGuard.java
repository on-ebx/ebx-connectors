package com.on.ebx.connect.core;

/**
 * Guarantees that a run started on a background thread reaches a terminal state.
 * <p>
 * The persisted {@code ExecutionRun} row says {@code RUNNING} from the moment the run is handed to
 * the executor. Nothing else will ever correct it while the JVM lives, so whatever the worker
 * throws - an exception, but also a {@link LinkageError} from an EBX internal that changed with a
 * product upgrade, or an {@link AssertionError} - must end in one last write of that row. Catching
 * exceptions alone is not enough: an {@code Error} would kill the thread and leave the run
 * {@code RUNNING} until the next restart.
 */
final class AsyncRunGuard
{
	/** Terminal status of a run whose worker failed. */
	static final String FAILED = "ERROR";
	/** Terminal status of a run whose worker thread was interrupted (module or server stopping). */
	static final String INTERRUPTED = "INTERRUPTED";

	/** The whole work of the worker, session creation included. */
	interface Body
	{
		void run() throws Exception;
	}

	/** Writes the terminal state of the run; called at most once, and only when the body failed. */
	interface TerminalState
	{
		void write(String pStatus, String pMessage);
	}

	private AsyncRunGuard()
	{
	}

	/**
	 * Runs the body; when it throws anything at all, writes the terminal state and returns the
	 * redacted reason (null when the body completed). Never throws, except for a
	 * {@link VirtualMachineError}, rethrown once the terminal state has been attempted: the JVM is in
	 * no state to pretend otherwise.
	 */
	static String run(final Body pBody, final TerminalState pTerminal)
	{
		try
		{
			pBody.run();
			return null;
		}
		catch (final Throwable failure)
		{
			// An interrupt left pending would make the terminal write itself fail on the first blocking
			// call; it is restored below for whoever owns the thread.
			final boolean interrupted = Thread.interrupted() || failure instanceof InterruptedException;
			final String status = interrupted ? AsyncRunGuard.INTERRUPTED : AsyncRunGuard.FAILED;
			String reason = AsyncRunGuard.describe(failure, interrupted);
			try
			{
				pTerminal.write(status, reason);
			}
			catch (final Throwable unwritten)
			{
				reason = reason + " (the terminal state could not be written either: " + AsyncRunGuard.text(unwritten)
					+ "; the run is marked INTERRUPTED at the next start)";
			}
			if (interrupted)
			{
				Thread.currentThread().interrupt();
			}
			if (failure instanceof VirtualMachineError)
			{
				throw (VirtualMachineError) failure;
			}
			return reason;
		}
	}

	/** What is kept on the run and written to the log: the kind of failure and its redacted message, never a stack trace. */
	static String describe(final Throwable pFailure, final boolean pInterrupted)
	{
		final String prefix = pInterrupted ? "Interrupted before completion (the module or the server was stopping): "
			: pFailure instanceof Error ? "Unexpected failure of the background worker (" + pFailure.getClass().getSimpleName()
				+ ", possibly an EBX internal that changed with this build): "
			: "Unexpected failure of the background worker: ";
		return ConnectorRepository.cut(prefix + AsyncRunGuard.text(pFailure), 2000);
	}

	private static String text(final Throwable pFailure)
	{
		return Redaction.redact(pFailure.getMessage() == null ? pFailure.toString() : pFailure.getMessage());
	}
}
