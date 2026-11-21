# Jarvis (Termux)

Files for the local Jarvis assistant in `~/Jarvis2.0` on the phone (Ollama + Llama 3.2 3B).

- `Modelfile`: Jarvis's personality, with the Llama 3 chat template (without it Ollama drops the
  personality text) and rule 5 for live facts.
- `jarvis_chat.py`: chat with Jarvis in Termux. Questions about prices, news, weather and other
  "now" topics are first looked up with Gemini + Google Search (key read from
  `~/Jarvis2.0/secrets/gemini_key.txt`, never stored here); Jarvis answers from those facts.
  Start a message with `web ` to force a lookup; `bye` ends. Python standard library only.
