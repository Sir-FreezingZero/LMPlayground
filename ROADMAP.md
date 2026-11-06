# Roadmap

Planned changes that aren't built yet.

## Copy and Share with the in-between text

Replies now show the text the model writes between tool calls, but Copy and Share on a reply still only take the final answer (`message.content`). Decide whether they should include each round's text too.

## How tool calling works now (for reference)

- **The model decides when to call tools.** Tool choice is "auto" (`LlamaGenerationSession::renderTemplate`), so a reply can be any mix of messages, tool calls and thinking, in the order the model chooses.
- **Each step is shown in order**: that round's thinking, what the model said, then its tool call (`MessageChatBubble`).
- **Within one step the order is fixed** by the model's chat format: thinking, then text, then the tool call. Models can think again after each tool result.
- **Ten tool rounds at most** per reply (`ToolCallLoop.DEFAULT_MAX_TOOL_ROUNDS`).
- **Thinking after tool results follows the thinking switch**, except for Gemma and gpt-oss, which give blank replies after a tool call without it, so it's forced on for them (`GenerationCoordinator`, `forceThinkingAfterTools`).
- **The system prompt asks the model to say what it's about to do** before each tool call, whenever any tool is on (`system_prompt_tool_hint`).
- **Probably one tool per round.** The app doesn't turn on llama.cpp's parallel tool calls, which are off by default. Not confirmed against the llama.cpp source.
