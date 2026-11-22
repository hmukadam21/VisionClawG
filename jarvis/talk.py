# talk.py - conversation with a local Ollama model (Jane or Jarvis) on the phone.
#   talk jane              chat page at http://127.0.0.1:5001 (opens in the browser)
#   talk jarvis            the same page for Jarvis
#   talk jane --terminal   the older chat inside Termux
# Page: type or tap the microphone; the reply appears in a chat bubble and is spoken in the
# voice from ~/Jarvis2.0/voice.txt (Emma by default). Hands-free mode listens again after each
# reply. The conversation is kept in ~/Jarvis2.0/chat_<name>.json and shown when the page opens.
# The page is reachable from this phone only (127.0.0.1). Ctrl+C in Termux stops it.
# Terminal mode: you speak (Termux:API speech recognition, if installed) or type; the answer is
# spoken through whatever plays the phone's sound (the glasses when they are connected).
# Jane's texting shorthand ("m ok wen r u") is spoken as full words; emojis are not spoken.
# Say or type "bye" to finish. Needs Ollama running, edge-tts and mpv; internet for the voice.
import datetime, json, os, re, shutil, subprocess, sys, tempfile, threading, urllib.parse, urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

HOME = os.path.expanduser("~/Jarvis2.0")
NAMES = [a for a in sys.argv[1:] if not a.startswith("--")]
MODEL = NAMES[0] if NAMES else "jane"
PORT = 5001
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


# The voice gets full words: Jane's own model rewrites her texting shorthand (it knows her
# style, so it also handles new abbreviations); the word list above is the backup.
REWRITE = ("You rewrite casual text messages into full, plain spoken English so a voice can read "
           "them aloud. Expand every abbreviation, slang spelling and texting shorthand into full "
           "words. Keep the same meaning, the same person speaking and the same tone. Remove emojis. "
           "Output only the rewritten message, nothing else.")
REWRITE_EXAMPLES = [
    ("m ok wen r u coming home? 🥺", "i'm okay, when are you coming home?"),
    ("lol gud nyt babe, txt me tmrw 😘", "haha, good night babe, text me tomorrow."),
    ("wbu? hw ws ur day", "what about you? how was your day?"),
]


def spoken_words(text):
    """What the voice should say: full words, no emojis."""
    clean = re.sub(r"\s+", " ", EMOJI.sub(" ", text)).strip()
    if not MODEL.lower().startswith("jane") or not re.search(r"\w", clean):
        return clean
    messages = [{"role": "system", "content": REWRITE}]
    for short, full in REWRITE_EXAMPLES:
        messages += [{"role": "user", "content": short}, {"role": "assistant", "content": full}]
    messages.append({"role": "user", "content": clean})
    try:
        body = json.dumps({"model": MODEL, "messages": messages, "stream": False,
                           "options": {"temperature": 0, "num_predict": 120}}).encode()
        req = urllib.request.Request(OLLAMA_URL, data=body, headers={"Content-Type": "application/json"})
        with urllib.request.urlopen(req, timeout=90) as resp:
            out = json.load(resp)["message"]["content"]
        lines = [l.strip().strip('"').strip() for l in out.splitlines() if re.search(r"\w", l)]
        full = EMOJI.sub(" ", lines[0]).strip() if lines else ""
        if full and len(full) <= 3 * len(clean) + 40:
            return to_speech(full)  # the word list catches anything left over
    except Exception:
        pass
    return to_speech(clean)


def speak(text, voice):
    said = spoken_words(text)
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


def terminal_main():
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


# ---------- chat page (talk jane) ----------

HISTORY_FILE = os.path.join(HOME, "chat_" + re.sub(r"\W", "_", MODEL) + ".json")
LOCK = threading.Lock()


def load_history():
    try:
        return json.load(open(HISTORY_FILE))
    except (OSError, ValueError):
        return []


def save_history(history):
    tmp = HISTORY_FILE + ".tmp"
    with open(tmp, "w") as f:
        json.dump(history[-500:], f)
    os.replace(tmp, HISTORY_FILE)


def tts_mp3(text, voice):
    """MP3 bytes of the text in the voice, or None."""
    said = spoken_words(text)
    if not re.search(r"\w", said):
        return None
    out = os.path.join(tempfile.gettempdir(), "talk_web_%d.mp3" % threading.get_ident())
    try:
        made = subprocess.run(["edge-tts", "--voice", voice, "--text", said, "--write-media", out],
                              capture_output=True, text=True, timeout=60)
        if made.returncode != 0:
            return None
        return open(out, "rb").read()
    except (OSError, subprocess.TimeoutExpired):
        return None


class Page(BaseHTTPRequestHandler):
    history = []
    voice = DEFAULT_VOICE

    def send(self, code, body, kind):
        self.send_response(code)
        self.send_header("Content-Type", kind)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        url = urllib.parse.urlparse(self.path)
        if url.path == "/":
            html = PAGE.replace("__NAME__", MODEL.capitalize())
            self.send(200, html.encode("utf-8"), "text/html; charset=utf-8")
        elif url.path == "/history":
            with LOCK:
                self.send(200, json.dumps(Page.history[-200:]).encode(), "application/json")
        elif url.path == "/speak":
            text = urllib.parse.parse_qs(url.query).get("text", [""])[0][:1500]
            mp3 = tts_mp3(text, Page.voice)
            if mp3:
                self.send(200, mp3, "audio/mpeg")
            else:
                self.send(503, b"voice unavailable (internet?)", "text/plain")
        else:
            self.send(404, b"not found", "text/plain")

    def do_POST(self):
        if urllib.parse.urlparse(self.path).path != "/chat":
            return self.send(404, b"not found", "text/plain")
        try:
            data = json.loads(self.rfile.read(int(self.headers.get("Content-Length", 0))) or b"{}")
        except ValueError:
            data = {}
        text = str(data.get("text", "")).strip()[:2000]
        if not text:
            return self.send(400, b'{"error": "empty message"}', "application/json")
        stamp = datetime.datetime.now().strftime("%d %b %H:%M")
        with LOCK:
            Page.history.append({"role": "user", "content": text, "time": stamp})
            context = [{"role": m["role"], "content": m["content"]} for m in Page.history[-12:]]
        try:
            reply = ask(context)
        except Exception as e:
            with LOCK:
                Page.history.pop()
            body = json.dumps({"error": MODEL + " did not answer (" + type(e).__name__ + "). Is Ollama running?"})
            return self.send(502, body.encode(), "application/json")
        with LOCK:
            Page.history.append({"role": "assistant", "content": reply,
                                 "time": datetime.datetime.now().strftime("%d %b %H:%M")})
            save_history(Page.history)
        self.send(200, json.dumps({"reply": reply}).encode(), "application/json")

    def log_message(self, *args):
        pass


PAGE = r"""<!DOCTYPE html>
<html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
<title>__NAME__</title>
<link rel="icon" href="data:,">
<style>
  * { box-sizing: border-box; }
  html, body { margin: 0; height: 100%; }
  body { font: 16px/1.4 system-ui, -apple-system, "Segoe UI", Roboto, sans-serif; background: #eef1f4;
         color: #1d232a; display: flex; flex-direction: column; height: 100dvh; }
  header { background: #1f2933; color: #fff; padding: 10px 14px; display: flex; align-items: center; gap: 10px; }
  .avatar { width: 38px; height: 38px; border-radius: 50%; background: #c2577a; display: grid;
            place-items: center; font-weight: 600; font-size: 18px; }
  .who { flex: 1; } .who b { display: block; font-size: 17px; } .who small { opacity: .7; font-size: 12px; }
  header button { background: #2f3b47; color: #fff; border: 0; border-radius: 18px; padding: 7px 10px;
                  font-size: 13px; }
  header button.on { background: #3a7d5c; }
  #log { flex: 1; overflow-y: auto; padding: 12px 10px 4px; }
  .row { display: flex; margin: 4px 0; } .row.me { justify-content: flex-end; }
  .row.note { justify-content: center; }
  .bubble { max-width: 80%; padding: 7px 10px 5px; border-radius: 12px; white-space: pre-wrap;
            word-wrap: break-word; box-shadow: 0 1px 1px rgba(0,0,0,.08); }
  .me .bubble { background: #d7f0de; border-top-right-radius: 3px; }
  .them .bubble { background: #fff; border-top-left-radius: 3px; }
  .note .bubble { background: #fff4d6; font-size: 13px; color: #6b5a2a; }
  .time { display: block; text-align: right; font-size: 11px; color: #7b8794; margin-top: 2px; }
  .typing { color: #7b8794; letter-spacing: 3px; }
  footer { display: flex; gap: 8px; padding: 8px; background: #e3e7eb;
           padding-bottom: calc(8px + env(safe-area-inset-bottom)); }
  footer input { flex: 1; border: 0; border-radius: 22px; padding: 11px 16px; font-size: 16px; outline: none; }
  footer button { width: 46px; height: 46px; border-radius: 50%; border: 0; font-size: 20px;
                  background: #1f2933; color: #fff; }
  footer button.on { background: #c0392b; animation: pulse 1s infinite; }
  @keyframes pulse { 50% { opacity: .6; } }
</style></head>
<body>
<header>
  <div class="avatar">__NAME__</div>
  <div class="who"><b>__NAME__</b><small id="status">on this phone</small></div>
  <button id="voiceBtn" title="Voice on or off">voice</button>
  <button id="freeBtn" title="Listen again after each reply">hands-free</button>
</header>
<div id="log"></div>
<footer>
  <button id="mic" title="Speak">&#127908;</button>
  <input id="text" placeholder="Message" autocomplete="off" enterkeyhint="send">
  <button id="sendBtn" title="Send">&#10148;</button>
</footer>
<script>
const log = document.getElementById("log"), input = document.getElementById("text");
const mic = document.getElementById("mic"), statusEl = document.getElementById("status");
const voiceBtn = document.getElementById("voiceBtn"), freeBtn = document.getElementById("freeBtn");
const audio = new Audio();
document.querySelector(".avatar").textContent = "__NAME__".charAt(0);
function load(k, d) { try { const v = localStorage.getItem(k); return v === null ? d : v === "1"; } catch (e) { return d; } }
function store(k, v) { try { localStorage.setItem(k, v ? "1" : "0"); } catch (e) {} }
let voiceOn = load("voiceOn", true), handsFree = load("handsFree", false), busy = false, misses = 0;
function paint() { voiceBtn.classList.toggle("on", voiceOn); freeBtn.classList.toggle("on", handsFree); }
paint();
function now() {
  const d = new Date(), p = n => String(n).padStart(2, "0");
  return p(d.getDate()) + " " + d.toLocaleString("en", {month: "short"}) + " " + p(d.getHours()) + ":" + p(d.getMinutes());
}
function bubble(kind, text, time) {
  const row = document.createElement("div");
  row.className = "row " + (kind === "user" ? "me" : kind === "note" ? "note" : "them");
  const b = document.createElement("div"); b.className = "bubble"; b.textContent = text;
  if (time) { const t = document.createElement("span"); t.className = "time"; t.textContent = time; b.appendChild(t); }
  row.appendChild(b); log.appendChild(row); log.scrollTop = log.scrollHeight; return row;
}
async function loadHistory() {
  try { (await (await fetch("/history")).json()).forEach(m => bubble(m.role, m.content, m.time)); }
  catch (e) { bubble("note", "Could not load earlier messages."); }
}
async function send(text) {
  text = (text || "").trim(); if (!text || busy) return;
  busy = true; input.value = ""; bubble("user", text, now());
  const typing = bubble("them", "..."); typing.firstChild.classList.add("typing"); statusEl.textContent = "typing...";
  try {
    const r = await fetch("/chat", {method: "POST", headers: {"Content-Type": "application/json"}, body: JSON.stringify({text})});
    const d = await r.json(); typing.remove();
    if (d.error) { bubble("note", d.error); }
    else { bubble("them", d.reply, now()); if (voiceOn) say(d.reply); else if (handsFree) listen(); }
  } catch (e) { typing.remove(); bubble("note", "Lost the connection to Termux. Is talk still running?"); }
  statusEl.textContent = "on this phone"; busy = false;
}
function say(text) {
  statusEl.textContent = "speaking...";
  audio.src = "/speak?text=" + encodeURIComponent(text);
  audio.play().catch(() => { statusEl.textContent = "on this phone"; if (handsFree) listen(); });
}
audio.onended = () => { statusEl.textContent = "on this phone"; if (handsFree) listen(); };
audio.onerror = () => { statusEl.textContent = "on this phone"; bubble("note", "Voice unavailable (internet?)."); if (handsFree) listen(); };
const SR = window.SpeechRecognition || window.webkitSpeechRecognition; let rec = null;
function listen() {
  if (!SR) { bubble("note", "Speaking needs the Chrome browser. Typing works here."); return; }
  if (rec || busy) return;
  rec = new SR(); rec.lang = "en-US"; rec.interimResults = false; rec.maxAlternatives = 1;
  mic.classList.add("on"); statusEl.textContent = "listening...";
  rec.onresult = e => {
    misses = 0; const t = e.results[0][0].transcript;
    if (/^\s*(bye|goodbye|stop)\s*$/i.test(t)) { handsFree = false; store("handsFree", false); paint(); bubble("user", t, now()); return; }
    send(t);
  };
  rec.onerror = e => {
    if (e.error === "no-speech" && handsFree && ++misses < 3) { setTimeout(listen, 300); return; }
    if (e.error === "not-allowed") bubble("note", "Allow the microphone for this page to speak.");
    misses = 0;
  };
  rec.onend = () => { mic.classList.remove("on"); rec = null; if (!busy) statusEl.textContent = "on this phone"; };
  rec.start();
}
mic.onclick = () => { if (rec) { rec.stop(); return; } audio.pause(); listen(); };
document.getElementById("sendBtn").onclick = () => send(input.value);
input.addEventListener("keydown", e => { if (e.key === "Enter") send(input.value); });
voiceBtn.onclick = () => { voiceOn = !voiceOn; store("voiceOn", voiceOn); if (!voiceOn) audio.pause(); paint(); };
freeBtn.onclick = () => { handsFree = !handsFree; store("handsFree", handsFree); paint(); if (handsFree && !rec && !busy) listen(); };
loadHistory();
</script>
</body></html>
"""


def web_main():
    Page.history = load_history()
    Page.voice = voice_name()
    try:
        server = ThreadingHTTPServer(("127.0.0.1", PORT), Page)
    except OSError as e:
        print("Port %d is busy (%s). Is talk already running in another Termux session?" % (PORT, e.strerror))
        return
    url = "http://127.0.0.1:%d" % PORT
    print("Chat with " + MODEL + " is open at " + url + " (voice " + Page.voice + ").")
    print("Keep this Termux session running. Ctrl+C stops the page.")
    if shutil.which("termux-open-url"):
        subprocess.Popen(["termux-open-url", url], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\nStopped.")


def main():
    if "--terminal" in sys.argv:
        terminal_main()
    else:
        web_main()


if __name__ == "__main__":
    main()
