package com.on.ebx.connect.core;

/**
 * What a person reads of a failure: the redacted message of the cause, never {@code toString()}, never a
 * stack trace; the stack is for the log, redacted as well.
 */
public final class FailureTextTest
{
	public static void main(final String[] pArgs) throws Exception
	{
		// a message is shown as it is, without the class name toString() prefixes
		final String plain = Redaction.failure(new IllegalStateException("Value must not exceed 10 character(s)"));
		check("Value must not exceed 10 character(s)".equals(plain), "the message alone: " + plain);

		// a wrapper without a message says nothing: its cause does, and it is the outermost message that wins
		final Exception wrapped = new RuntimeException(new java.io.IOException("Connection refused"));
		check("Connection refused".equals(Redaction.failure(wrapped)), "the cause speaks when the wrapper has no message: " + Redaction.failure(wrapped));
		final Exception contextual = new RuntimeException("Sync of Company failed", new java.io.IOException("Connection refused"));
		check("Sync of Company failed".equals(Redaction.failure(contextual)), "the outer message carries the context: " + Redaction.failure(contextual));

		// no message anywhere: the kind of failure, not a package name
		check("NullPointerException".equals(Redaction.failure(new NullPointerException())), "the simple name: " + Redaction.failure(new NullPointerException()));
		check("".equals(Redaction.failure(null)), "nothing is nothing");
		check("IllegalStateException".equals(Redaction.failure(new IllegalStateException("   "))), "a blank message is no message");

		// a cycle in the causes ends
		final Exception a = new RuntimeException();
		final Exception b = new RuntimeException(a);
		a.initCause(b);
		check("RuntimeException".equals(Redaction.failure(a)), "a cycle of causes ends: " + Redaction.failure(a));

		// secrets in the message are masked, in the text and in the stack
		final String url = "GET https://api.example.com/v1/items?api_key=SECRETVALUE123&page=2 failed";
		final Exception quoting = new IllegalStateException(url);
		check(!Redaction.failure(quoting).contains("SECRETVALUE123") && Redaction.failure(quoting).contains("api_key=***"), "the URL secret is masked: " + Redaction.failure(quoting));
		final String stack = Redaction.stack(quoting);
		check(!stack.contains("SECRETVALUE123") && stack.contains("FailureTextTest"), "the stack keeps its frames and loses the secret");

		// bounded
		final StringBuilder big = new StringBuilder();
		for (int i = 0; i < 5000; i++)
		{
			big.append('x');
		}
		check(Redaction.failure(new RuntimeException(big.toString())).length() <= 1003, "a long message is cut");
		check(Redaction.stack(new RuntimeException(big.toString())).length() <= 8003, "a long stack is cut");
		System.out.println("PASS: a failure is shown as the redacted message of its cause, the stack goes to the log redacted");
	}

	private static void check(final boolean pCondition, final String pMessage)
	{
		if (!pCondition)
		{
			throw new AssertionError(pMessage);
		}
	}
}
