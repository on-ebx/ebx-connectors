package com.on.ebx.connect.core;

import com.orchestranetworks.instance.Repository;
import com.orchestranetworks.service.Session;
import com.orchestranetworks.service.UserReference;

/**
 * The one place of the module that asks EBX for a session it was not handed: a background worker
 * needs a {@link Session} for the same user as its caller, whose own session may be tied to the
 * HTTP request that started the work.
 * <p>
 * EBX has no public API for "a session for this already authenticated user": every public factory
 * of {@link Repository} wants credentials the module never holds. The only route is the internal
 * {@code com.onwbp.adaptation.SessionImpl.createSession(UserReference, Repository)}, found by
 * {@code javap} and therefore liable to move or disappear with any EBX build. It is called by
 * reflection so that its absence is an ordinary, catchable condition rather than a
 * {@code NoSuchMethodError} thrown in the middle of a worker, and every failure - missing class,
 * changed signature, refusal by the directory, linkage error - ends in the same documented
 * fallback: <b>the caller's own session, unchanged</b>. A worker therefore always has a session;
 * the worst case is the one that existed before a fresh session was minted at all.
 */
final class WorkerSessions
{
	/** Produces a session for the user of the caller's session; may throw anything, including linkage errors. */
	interface Minter
	{
		Session mint(Session pCaller) throws Exception;
	}

	private static final String INTERNAL_FACTORY_CLASS = "com.onwbp.adaptation.SessionImpl";
	private static final String INTERNAL_FACTORY_METHOD = "createSession";

	private WorkerSessions()
	{
	}

	/**
	 * A fresh session for the user of {@code pCaller}, or {@code pCaller} itself when one cannot be
	 * minted for any reason. Never throws.
	 */
	static Session forWorker(final Session pCaller, final Repository pRepository)
	{
		if (pCaller == null || pRepository == null)
		{
			return pCaller;
		}
		return WorkerSessions.mintOrFallBack(pCaller, new Minter()
		{
			@Override
			public Session mint(final Session pSession) throws Exception
			{
				final UserReference user = pSession.getUserReference();
				if (user == null)
				{
					return null;
				}
				final Object minted = WorkerSessions.internalFactory().invoke(null, user, pRepository);
				return minted instanceof Session ? (Session) minted : null;
			}
		});
	}

	/**
	 * The internal factory of this EBX build, resolved without initialising its class; throws when the
	 * class or the signature is no longer there.
	 */
	static java.lang.reflect.Method internalFactory() throws ClassNotFoundException, NoSuchMethodException
	{
		return Class.forName(WorkerSessions.INTERNAL_FACTORY_CLASS, false, WorkerSessions.class.getClassLoader())
			.getMethod(WorkerSessions.INTERNAL_FACTORY_METHOD, UserReference.class, Repository.class);
	}

	/**
	 * The fallback rule on its own, separated from the EBX call so that it can be exercised without a
	 * repository: whatever the minter throws or returns, the answer is a usable session.
	 */
	static Session mintOrFallBack(final Session pCaller, final Minter pMinter)
	{
		try
		{
			final Session minted = pMinter.mint(pCaller);
			return minted != null ? minted : pCaller;
		}
		catch (final Exception ex)
		{
			WorkerSessions.warn(ex);
			return pCaller;
		}
		catch (final LinkageError ex)
		{
			// the internal factory moved or changed shape in this EBX build
			WorkerSessions.warn(ex);
			return pCaller;
		}
	}

	private static void warn(final Throwable pFailure)
	{
		final Throwable cause = pFailure instanceof java.lang.reflect.InvocationTargetException && pFailure.getCause() != null
			? pFailure.getCause() : pFailure;
		ConnectorService.log().warn("[connectors] could not mint a fresh session for a background worker; reusing the caller's own session - "
			+ Redaction.redact(cause.getMessage() == null ? cause.toString() : cause.getClass().getSimpleName() + ": " + cause.getMessage()));
	}
}
