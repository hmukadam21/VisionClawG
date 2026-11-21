# talk.py - spoken conversation with a local Ollama model in Termux.
#   talk jane     talk with Jane          talk jarvis    talk with Jarvis
# You speak (Android speech recognition through Termux:API, if installed) or type; the model
# answers on screen and out loud in the voice from ~/Jarvis2.0/voice.txt (Emma by default),
# through whatever plays the phone's sound (the glasses when they are connected).
# Jane's texting shorthand ("m ok wen r u") is spoken as full words; emojis are not spoken.
# Say or type "bye" to finish. Needs Ollama running, edge-tts and mpv; internet for the voice.
import json, os, re, shutil, subprocess, sys, tempfile, urllib.request

HOME = os.path.expanduser("~/Jarvis2.0")
MODEL = sys.argv[1] if len(sys.argv) > 1 else "jane"
OLLAMA_URL = "http://localhost:11434/api/chat"
DEFAULT_VOICE = "en-US-EmmaMultilingualNeural"

SPOKEN = {  # texting shorthand -> words to say
    "m": "i'm", "im": "i'm", "gud": "good", "gr8": "great", "u": "you", "ur": "your", "r": "are", "wen": "when", "txt": "text",
    "fr": "for real", "lol": "haha", "lmao": "haha", "cos": "because", "coz": "because",
    "nyt": "night", "ryt": "right", "lngg": "lang", "lng": "lang", "pls": "please", "plz": "please",
    "thx": "thanks", "ty": "thank you", "gd": "good", "gn": "good night", "gm": "good morning",
    "b4": "before", "2day": "today", "2nyt": "tonight", "tmrw": "tomorrow", "idk": "i don't know",
    "omg": "oh my god", "btw": "by the way", "k": "okay", "kk": "okay", "ok": "okay",
    "luv": "love", "wat": "what", "wut": "what", "wt": "what", "y": "why", "n": "and", "msg": "message",
    "bcz": "because", "abt": "about", "hw": "how", "nw": "now", "dnt": "don't", "cnt": "can't",
}
EMOJI = re.compile("[\U0001F000-\U0001FAFF☀-➿←-⇿⬀-⯿️‍]")


def voice_name():
    try:
        name = open(os.path.join(HOME, "voice.txt")).read().strip()
        return name or DEFAULT_VOICE
    except OSError:
        return DEFAULT_VOICE


def to_speech(text):
    """Text as it should be heard: emojis out; Jane's shorthand spelled out."""
    text = EMOJI.sub(" ", text)
    if not MODEL.lower().startswith("jane"):
        return re.sub(r"\s+", " ", text).strip()
    words = re.split(r"(\W+)", text)
    return "".join(SPOKEN.get(w.lower(), w) if re.fullmatch(r"\w+", w) else w for w in words).strip()


def speak(text, voice):
    said = to_speech(text)
    if not re.search(r"\w", said):
        return
    out = os.path.join(tempfile.gettempdir(), "talk_reply.mp3")
    try:
        made = subprocess.run(["edge-tts", "--voice", voice, "--text", said, "--write-media", out],
                              capture_output=True, text=True, timeout=60)
        if made.returncode != 0:
            print("  (voice failed: " + (made.stderr.strip().splitlines() or ["no internet?"])[-1][:100] + ")")
            return
        subprocess.run(["mpv", "--no-video", "--really-quiet", out], timeout=300)
    except (OSError, subprocess.TimeoutExpired) as e:
        print("  (voice failed: " + type(e).__name__ + ")")


def hear_once():
    """One round of Android speech recognition: (text, problem)."""
    heard = subprocess.run(["termux-speech-to-text"], capture_output=True, text=True)
    out = heard.stdout.strip()
    if out.startswith("{"):  # Termux:API reports problems as JSON (e.g. microphone not allowed)
        try:
            return "", str(json.loads(out).get("error", out))[:100]
        except ValueError:
            return "", out[:100]
    # keep real words only (drop stray brackets and empty partial lines); the last line is final
    lines = [l.strip() for l in out.splitlines() if re.search(r"\w", l)]
    return (lines[-1] if lines else ""), ""


def listen(can_hear):
    """What the user said (speech recognition) or typed. None means finish."""
    if can_hear:
        for attempt in range(3):  # hands-free: try again by itself when nothing was caught
            print("  (listening... speak now)")
            text, problem = hear_once()
            if text:
                print("you: " + text)
                return text
            if problem:
                print("  (speech recognition: " + problem + ")")
                break
        try:
            return input("  (did not catch that; type it, or press Enter to listen again) you: ").strip()
        except (EOFError, KeyboardInterrupt):
            return None
    try:
        return input("you: ").strip()
    except (EOFError, KeyboardInterrupt):
        return None


def ask(messages):
    body = json.dumps({"model": MODEL, "messages": messages[-12:], "stream": False}).encode()
    req = urllib.request.Request(OLLAMA_URL, data=body, headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=300) as resp:
        return json.load(resp)["message"]["content"].strip()


def main():
    voice = voice_name()
    can_hear = shutil.which("termux-speech-to-text") is not None and "--type" not in sys.argv
    print("Talking with " + MODEL + ", voice " + voice + ".")
    print("You: " + ("speak after 'listening'" if can_hear else
                     "type (for speaking, install Termux:API; see the README)") + ". Say or type bye to finish.\n")
    history = []
    while True:
        said = listen(can_hear)
        if said is None or said.lower().strip(" .!") in ("bye", "goodbye", "exit", "quit", "stop"):
            break
        if not said:
            continue
        history.append({"role": "user", "content": said})
        try:
            reply = ask(history)
        except Exception as e:
            print("  (" + MODEL + " did not answer: " + type(e).__name__ + ". Is Ollama running?)")
            history.pop()
            continue
        history.append({"role": "assistant", "content": reply})
        print(MODEL + ": " + reply)
        speak(reply, voice)
        print("")


if __name__ == "__main__":
    main()
