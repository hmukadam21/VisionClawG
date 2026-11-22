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
- `talk.py`: `talk jane` (or `talk jarvis`) opens a chat page at http://127.0.0.1:5001 with
  chat bubbles, microphone, voice replies, hands-free mode and saved history
  (one file per chat in `chats_<name>/`; the ☰ menu lists chats, starts a new one or deletes
  one; the camera button sends a photo, shrunk to 384 px in the browser, which the local photo
  reader huihui_ai/qwen3-vl-abliterated:2b-instruct describes for Jane or Jarvis to answer;
  `--threads=N` sets its processor cores, default 4); `talk jane --terminal` is the older chat. Terminal mode: you speak
  (Android speech recognition via Termux:API, if installed) or type (`--type`); the reply is
  shown and spoken in the voice from `voice.txt` (Emma by default) with edge-tts + mpv, through
  the phone's sound output (the glasses when connected). Jane's texting shorthand is spoken
  as full words and emojis are not spoken.
