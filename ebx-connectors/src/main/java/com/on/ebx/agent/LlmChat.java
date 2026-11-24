package com.on.ebx.agent;

import java.util.List;

/**
 * One model turn with tools: what {@link AgentLoop} needs from a language model, and nothing more. The provider APIs are
 * stateless, so the whole conversation is passed on every call; the implementation answers the model's next turn and
 * never runs a tool itself (the loop does, through its {@link ToolSet}).
 * <p>
 * Two implementations ship with this library: {@link LlmClient}, which calls the provider's HTTP API itself with headers
 * (the key among them) supplied by the caller, and {@link ContractLlmChat}, which asks ebx-connectors to make the call
 * through its in-JVM contract ({@code llm.chat}, contract 1.1.0), so that the key never leaves ebx-connectors.
 */
public interface LlmChat
{
	/**
	 * Sends the conversation and the tools offered, answers the model's turn.
	 *
	 * @param pSystem the system prompt (may be null)
	 * @param pTools the tools the model may ask for (may be empty)
	 * @param pConversation the whole conversation so far, in order: built with {@link LlmClient.Message#user},
	 * {@link LlmClient.Message#assistant} and {@link LlmClient.Message#toolResults}
	 * @throws LlmException when the model could not be asked or did not answer
	 */
	LlmClient.Turn send(String pSystem, List<LlmClient.ToolSpec> pTools, List<LlmClient.Message> pConversation) throws LlmException;
}
