# Roadmap

Planned changes that aren't built yet.

## Show what the model says before each tool call

**Problem.** A model can write visible text before calling a tool, for example "I'll search for that now." While it streams, the text shows in the chat. When the tool call happens, `ConversationUiState.addToolCallsToLastMessage` moves that round's text into the tool call's `precedingThinking`. `MessageChatBubble` then shows only its thinking part, in the collapsible "Thinking" section above the tool. The visible part is dropped from the screen.

The text isn't lost: it's saved with the tool call, and the native session keeps it in the history (`assistant_msg.content`), so the model remembers saying it. Only the display is missing it. Today you only see narration from the final step, after the last tool.

**Fix.** In `MessageChatBubble`, also render `splitThinking(toolCall.precedingThinking).responseContent` as normal reply text, between that round's thinking section and its tool call. A turn would then read in order: thinking → "I'll look this up" → tool → thinking → more text → tool → final answer.

- Display only. No changes to the engine, the saved data or the tool loop.
- Old chats show the missing text too, since it's already saved.
- Check that Copy and Share on a reply still behave as intended. They may need to include the in-between text too.

## Related tool-calling behavior (for reference, not planned changes)

- **The model decides when to call tools.** Tool choice is "auto" (`LlamaGenerationSession::renderTemplate`), so no call is forced at the start of a reply.
- **Five tool rounds at most** per turn (`ToolCallLoop.DEFAULT_MAX_TOOL_ROUNDS`).
- **Thinking is forced on after each tool result** for models with a thinking mode, even with the thinking switch off. This works around Gemma-style models giving blank replies after a tool call.
- **Probably one tool per round.** The app doesn't turn on llama.cpp's parallel tool calls, which are off by default. Not confirmed against the llama.cpp source.
