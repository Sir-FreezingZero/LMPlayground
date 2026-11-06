# Roadmap

Planned changes that aren't built yet.

## Copy and Share with the in-between text

Replies now show the text the model writes between tool calls, but Copy and Share on a reply still only take the final answer (`message.content`). Decide whether they should include each round's text too.

## Cut the translations to about 12 languages

**Why.** The app ships 28 languages. Every new or changed piece of text has to be written 28 times (the Appearance page was 13 lines of text and 364 lines of translations). About 12 well-chosen languages reach roughly the same share of internet users, since the top 10 internet languages alone cover about 77% (Internet World Stats, 2020), and people whose language is missing get English. App size barely changes (well under 1 MB installed); the gain is upkeep.

**Keep (12):** English, Chinese (Simplified), Spanish, Arabic, Portuguese (Brazil), Indonesian, French, Japanese, **Russian (new)**, German, Hindi, Bengali.

**Drop (17):** Czech, Danish, Finnish, Filipino, Italian, Hebrew, Korean, Malay, Norwegian, Dutch, Polish, Romanian, Swedish, Thai, Turkish, Ukrainian, Vietnamese. Most are small online, or their speakers commonly read English.

**Steps**
1. Add `values-ru/strings.xml` with every string translated, plus a Russian Play Store listing in `fastlane/metadata/android/ru-RU`.
2. Delete the 17 `values-*` folders and their `fastlane/metadata/android/` listings.
3. Update the language list in `res/xml/locales_config.xml` and anything in `LanguageDialog.kt` / the Language screen that lists languages.
4. Update the README and FAQ if they mention the number of languages.
5. Check a user who had picked a dropped language: the app should fall back to the system language or English without crashing.

**Trade-off.** Users of the dropped languages lose a translated app. Korean, Turkish, Vietnamese and Italian are the closest calls (each roughly 1–2% of internet users); any could be kept if the list should lean toward coverage over upkeep.

## How tool calling works now (for reference)

- **The model decides when to call tools.** Tool choice is "auto" (`LlamaGenerationSession::renderTemplate`), so a reply can be any mix of messages, tool calls and thinking, in the order the model chooses.
- **Each step is shown in order**: that round's thinking, what the model said, then its tool call (`MessageChatBubble`).
- **Within one step the order is fixed** by the model's chat format: thinking, then text, then the tool call. Models can think again after each tool result.
- **Ten tool rounds at most** per reply (`ToolCallLoop.DEFAULT_MAX_TOOL_ROUNDS`).
- **Thinking after tool results follows the thinking switch**, except for Gemma and gpt-oss, which give blank replies after a tool call without it, so it's forced on for them (`GenerationCoordinator`, `forceThinkingAfterTools`).
- **The system prompt asks the model to say what it's about to do** before each tool call, whenever any tool is on (`system_prompt_tool_hint`).
- **Probably one tool per round.** The app doesn't turn on llama.cpp's parallel tool calls, which are off by default. Not confirmed against the llama.cpp source.
