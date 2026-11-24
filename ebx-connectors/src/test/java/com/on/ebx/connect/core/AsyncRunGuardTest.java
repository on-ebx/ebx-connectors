package com.on.ebx.connect.core;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

import com.orchestranetworks.service.Session;

/**
 * An asynchronous run always reaches a terminal state, and a worker always has a session: the two
 * rules are exercised here without an EBX repository, by throwing from the body and from the session
 * minter what an EBX upgrade would throw ({@code NoSuchMethodError}, {@code NoClassDefFoundError}).
 * What this cannot show is the real minting through {@code SessionImpl.createSession}, nor the write
 * of the {@code ExecutionRun} row itself: both need a running repository.
 */
public final class AsyncRunGuardTest
{
	public static void main(final String[] pArgs) throws Exception
	{
		// a body that completes writes no terminal state: the import wrote its own
		final String[] written = new String[2];
		check(AsyncRunGuard.run(body(null), terminal(written)) == null, "a completed body reports no failure");
		check(written[0] == null, "a completed body never triggers the last-resort write");

		// an Error from an EBX internal is a terminal failure, not a dead thread
		final String linkage = AsyncRunGuard.run(body(new NoSuchMethodError("SessionImpl.createSession(UserReference, Repository)")), terminal(written));
		check("ERROR".equals(written[0]), "an Error ends the run in ERROR: " + written[0]);
		check(written[1] != null && written[1].contains("NoSuchMethodError") && written[1].contains("createSession"), "the reason names the failure: " + written[1]);
		check(linkage != null && linkage.equals(written[1]), "the same reason is returned for the log");

		// a checked or runtime exception escaping the import is handled the same way
		written[0] = null;
		AsyncRunGuard.run(body(new IllegalStateException("repository closed")), terminal(written));
		check("ERROR".equals(written[0]) && written[1].contains("repository closed"), "a runtime exception ends the run in ERROR: " + written[1]);

		// a secret in the message never reaches the run row or the log
		written[0] = null;
		AsyncRunGuard.run(body(new IllegalStateException("GET https://api.example.test/v1?api_key=SECRETVALUE123 failed")), terminal(written));
		check(!written[1].contains("SECRETVALUE123"), "the message is redacted: " + written[1]);

		// an interrupted worker (module stopping) ends INTERRUPTED, writes with the flag cleared, then restores it
		written[0] = null;
		final boolean[] flagDuringWrite = new boolean[1];
		AsyncRunGuard.run(new AsyncRunGuard.Body()
		{
			@Override
			public void run() throws Exception
			{
				Thread.currentThread().interrupt();
				throw new IllegalStateException("interrupted while reading");
			}
		}, new AsyncRunGuard.TerminalState()
		{
			@Override
			public void write(final String pStatus, final String pMessage)
			{
				flagDuringWrite[0] = Thread.currentThread().isInterrupted();
				written[0] = pStatus;
			}
		});
		check("INTERRUPTED".equals(written[0]), "an interrupted worker ends INTERRUPTED: " + written[0]);
		check(!flagDuringWrite[0], "the terminal write runs with the interrupt cleared");
		check(Thread.interrupted(), "the interrupt is restored for the owner of the thread");

		// a terminal write that fails too must not throw out of the worker
		final String unwritten = AsyncRunGuard.run(body(new NoClassDefFoundError("com/onwbp/adaptation/SessionImpl")), new AsyncRunGuard.TerminalState()
		{
			@Override
			public void write(final String pStatus, final String pMessage)
			{
				throw new IllegalStateException("dataset unavailable");
			}
		});
		check(unwritten != null && unwritten.contains("could not be written"), "a failed terminal write is said, not thrown: " + unwritten);

		// session minting: whatever the internal factory does, the worker gets a usable session
		final Session caller = session();
		final Session fresh = session();
		check(WorkerSessions.mintOrFallBack(caller, minter(fresh, null)) == fresh, "a minted session is used");
		check(WorkerSessions.mintOrFallBack(caller, minter(null, null)) == caller, "no minted session: the caller's own");
		check(WorkerSessions.mintOrFallBack(caller, minter(null, new NoSuchMethodError("createSession"))) == caller, "a changed internal signature: the caller's own");
		check(WorkerSessions.mintOrFallBack(caller, minter(null, new ClassNotFoundException("com.onwbp.adaptation.SessionImpl"))) == caller,
			"a removed internal class: the caller's own");
		check(WorkerSessions.mintOrFallBack(caller, minter(null, new IllegalStateException("directory refused"))) == caller, "a refusal: the caller's own");
		check(WorkerSessions.forWorker(null, null) == null && WorkerSessions.forWorker(caller, null) == caller, "nothing to mint from: the caller's own");

		// the EBX jar this suite compiles against still has the internal factory, with the expected shape
		final Method factory = WorkerSessions.internalFactory();
		check(java.lang.reflect.Modifier.isStatic(factory.getModifiers()) && java.lang.reflect.Modifier.isPublic(factory.getModifiers())
			&& Session.class.isAssignableFrom(factory.getReturnType()), "the internal session factory is public, static and returns a Session: " + factory);

		System.out.println("PASS: an asynchronous run always ends in a terminal state; a worker always has a session (real minting and the ExecutionRun write need a running EBX)");
	}

	private static AsyncRunGuard.Body body(final Throwable pFailure)
	{
		return new AsyncRunGuard.Body()
		{
			@Override
			public void run() throws Exception
			{
				if (pFailure instanceof Error) throw (Error) pFailure;
				if (pFailure instanceof Exception) throw (Exception) pFailure;
			}
		};
	}

	private static AsyncRunGuard.TerminalState terminal(final String[] pWritten)
	{
		return new AsyncRunGuard.TerminalState()
		{
			@Override
			public void write(final String pStatus, final String pMessage)
			{
				pWritten[0] = pStatus;
				pWritten[1] = pMessage;
			}
		};
	}

	private static WorkerSessions.Minter minter(final Session pMinted, final Throwable pFailure)
	{
		return new WorkerSessions.Minter()
		{
			@Override
			public Session mint(final Session pCaller) throws Exception
			{
				if (pFailure instanceof Error) throw (Error) pFailure;
				if (pFailure instanceof Exception) throw (Exception) pFailure;
				return pMinted;
			}
		};
	}

	/** A stand-in session: only its identity matters to the fallback rule. */
	private static Session session()
	{
		return (Session) Proxy.newProxyInstance(AsyncRunGuardTest.class.getClassLoader(), new Class<?>[] { Session.class }, new InvocationHandler()
		{
			@Override
			public Object invoke(final Object pProxy, final Method pMethod, final Object[] pMethodArgs)
			{
				if ("equals".equals(pMethod.getName())) return Boolean.valueOf(pProxy == pMethodArgs[0]);
				if ("hashCode".equals(pMethod.getName())) return Integer.valueOf(System.identityHashCode(pProxy));
				return null;
			}
		});
	}

	private static void check(final boolean pCondition, final String pMessage)
	{
		if (!pCondition)
		{
			throw new AssertionError(pMessage);
		}
	}
}
