# Jarvis (Termux)

Files for the local Jarvis assistant in `~/Jarvis2.0` on the phone (Ollama + Llama 3.2 3B).

- `Modelfile`: Jarvis's personality, with the Llama 3 chat template (without it Ollama drops the
  personality text) and rule 5 for live facts.
- `jarvis_chat.py`: chat with Jarvis in Termux. Questions about prices, news, weather and other
  "now" topics are first looked up with Gemini + Google Search (key read from
  `~/Jarvis2.0/secrets/gemini_key.txt`, never stored here); Jarvis answers from those facts.
  If the usual models are refused, other models on the key are tried and the one that works is
  remembered in `gemini_model.txt`. A failed lookup gets an honest "couldn't check" answer.
  `jarvis --check` prints what Google says about the key and each model.
  Start a message with `web` or `search` to force a lookup; `bye` ends. The key file may hold the key in any format (label, quotes, new-style keys with dots). Python standard library only.
