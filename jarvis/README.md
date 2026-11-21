# Jarvis (Termux)

Files for the Jarvis assistant in `~/Jarvis2.0` on the phone. Start it with the `jarvis` command.

- `jarvis_chat.py` (v5): the brain is free Gemini (no Google search, which the free tier
  refuses), with free live-data tools Gemini calls by itself: weather (Open-Meteo), news
  (Google News feed), exchange rates and gold/silver (currency-api, daily). No keys besides
  the Gemini key in `~/Jarvis2.0/secrets/gemini_key.txt` (never stored here). If Gemini is busy
  or offline, the local Ollama model `jarvis` answers. `jarvis --local` = local model only
  (private); `jarvis --check` tests the brain and every source. Python standard library only.
- `Modelfile`: the local model's personality, with the Llama 3 chat template (without it
  Ollama drops the personality text).
