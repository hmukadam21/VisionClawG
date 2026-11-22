# talk.py - conversation with a local Ollama model (Jane or Jarvis) on the phone.
#   talk jane              chat page at http://127.0.0.1:5001 (opens in the browser)
#   talk jarvis            the same page for Jarvis
#   talk jane --terminal   the older chat inside Termux
# Page: type or tap the microphone; the reply appears in a chat bubble and is spoken in the
# voice from ~/Jarvis2.0/voice.txt (Emma by default). Hands-free mode listens again after each
# reply. The menu button lists all chats (newest first), starts a new one or deletes one; each
# chat is a file in ~/Jarvis2.0/chats_<name>/ and keeps its own memory.
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
# Each conversation is one file in ~/Jarvis2.0/chats_<name>/ (newest first in the Chats list).
# Older single-chat files (chat_<name>.json and the dated ones set aside) are moved in once.

SAFE_NAME = re.sub(r"\W", "_", MODEL)
CHAT_DIR = os.path.join(HOME, "chats_" + SAFE_NAME)
LOCK = threading.Lock()


def stamp():
    return datetime.datetime.now().strftime("%d %b %H:%M")


def chat_path(cid):
    return os.path.join(CHAT_DIR, re.sub(r"[^\w-]", "", cid) + ".json")


def read_chat(cid):
    try:
        return json.load(open(chat_path(cid)))
    except (OSError, ValueError):
        return None


def write_chat(chat):
    os.makedirs(CHAT_DIR, exist_ok=True)
    tmp = chat_path(chat["id"]) + ".tmp"
    with open(tmp, "w") as f:
        json.dump(chat, f)
    os.replace(tmp, chat_path(chat["id"]))


def new_chat(created=None):
    base = datetime.datetime.now().strftime("%Y%m%d-%H%M%S")
    cid, n = base, 1
    while os.path.exists(chat_path(cid)):
        n += 1
        cid = "%s-%d" % (base, n)
    chat = {"id": cid, "created": created or stamp(), "messages": []}
    write_chat(chat)
    return chat


def list_chats():
    chats = []
    for name in os.listdir(CHAT_DIR) if os.path.isdir(CHAT_DIR) else []:
        if not name.endswith(".json"):
            continue
        chat = read_chat(name[:-5])
        if not chat:
            continue
        msgs = chat.get("messages", [])
        first = next((m["content"] for m in msgs if m.get("role") == "user"), "")
        chats.append({"id": chat["id"], "title": (first[:40] + ("..." if len(first) > 40 else "")) or "New chat",
                      "updated": msgs[-1].get("time", "") if msgs else chat.get("created", ""),
                      "count": len(msgs)})
    return sorted(chats, key=lambda c: c["id"], reverse=True)


def move_old_chats():
    """Bring chat_<name>.json and the dated copies set aside into the chats folder (once)."""
    for name in sorted(os.listdir(HOME)):
        m = re.fullmatch(r"chat_" + re.escape(SAFE_NAME) + r"(?:_(\d{8})_(\d{4}))?\.json", name)
        if not m:
            continue
        path = os.path.join(HOME, name)
        try:
            msgs = json.load(open(path))
        except (OSError, ValueError):
            continue
        if isinstance(msgs, list) and msgs:
            when = datetime.datetime.fromtimestamp(os.path.getmtime(path))
            chat = new_chat(msgs[0].get("time") or when.strftime("%d %b %H:%M"))
            if m.group(1):  # keep the original order: the set-aside date becomes the id
                os.remove(chat_path(chat["id"]))
                chat["id"] = m.group(1) + "-" + m.group(2) + "00"
            chat["messages"] = msgs
            write_chat(chat)
        os.replace(path, path + ".moved")


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
    voice = DEFAULT_VOICE

    def send(self, code, body, kind="application/json"):
        if not isinstance(body, bytes):
            body = json.dumps(body).encode()
        self.send_response(code)
        self.send_header("Content-Type", kind)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def body(self):
        try:
            return json.loads(self.rfile.read(int(self.headers.get("Content-Length", 0))) or b"{}")
        except ValueError:
            return {}

    def do_GET(self):
        url = urllib.parse.urlparse(self.path)
        parts = url.path.strip("/").split("/")
        if url.path == "/":
            self.send(200, PAGE.replace("__NAME__", MODEL.capitalize()).encode("utf-8"), "text/html; charset=utf-8")
        elif url.path == "/chats":
            with LOCK:
                self.send(200, list_chats())
        elif len(parts) == 2 and parts[0] == "chats":
            with LOCK:
                chat = read_chat(parts[1])
            self.send(200, chat) if chat else self.send(404, {"error": "chat not found"})
        elif url.path == "/speak":
            text = urllib.parse.parse_qs(url.query).get("text", [""])[0][:1500]
            mp3 = tts_mp3(text, Page.voice)
            self.send(200, mp3, "audio/mpeg") if mp3 else self.send(503, b"voice unavailable", "text/plain")
        else:
            self.send(404, {"error": "not found"})

    def do_POST(self):
        parts = urllib.parse.urlparse(self.path).path.strip("/").split("/")
        if parts == ["chats", "new"]:
            with LOCK:
                return self.send(200, {"id": new_chat()["id"]})
        if len(parts) == 3 and parts[0] == "chats" and parts[2] == "delete":
            with LOCK:
                try:
                    os.remove(chat_path(parts[1]))
                except OSError:
                    pass
            return self.send(200, {"ok": True})
        if parts != ["chat"]:
            return self.send(404, {"error": "not found"})
        data = self.body()
        text = str(data.get("text", "")).strip()[:2000]
        cid = str(data.get("id", ""))
        if not text:
            return self.send(400, {"error": "empty message"})
        with LOCK:
            chat = read_chat(cid) or new_chat()
            chat["messages"].append({"role": "user", "content": text, "time": stamp()})
            write_chat(chat)
            context = [{"role": m["role"], "content": m["content"]} for m in chat["messages"][-12:]]
        try:
            reply = ask(context)
        except Exception as e:
            with LOCK:
                chat = read_chat(chat["id"]) or chat
                if chat["messages"] and chat["messages"][-1]["role"] == "user":
                    chat["messages"].pop()
                write_chat(chat)
            return self.send(502, {"error": MODEL + " did not answer (" + type(e).__name__ + "). Is Ollama running?"})
        with LOCK:
            chat = read_chat(chat["id"]) or chat
            chat["messages"].append({"role": "assistant", "content": reply, "time": stamp()})
            write_chat(chat)
        self.send(200, {"reply": reply, "id": chat["id"]})

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
  header { background: #1f2933; color: #fff; padding: 10px 12px; display: flex; align-items: center; gap: 9px; }
  .avatar { width: 36px; height: 36px; border-radius: 50%; background: #c2577a; display: grid;
            place-items: center; font-weight: 600; font-size: 17px; flex: none; }
  .who { flex: 1; min-width: 0; } .who b { display: block; font-size: 17px; }
  .who small { opacity: .7; font-size: 12px; }
  header button { background: #2f3b47; color: #fff; border: 0; border-radius: 18px; padding: 7px 10px;
                  font-size: 13px; flex: none; }
  header button.on { background: #3a7d5c; }
  #menuBtn { font-size: 18px; padding: 4px 10px; background: transparent; }
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
  footer input { flex: 1; min-width: 0; border: 0; border-radius: 22px; padding: 11px 16px; font-size: 16px; outline: none; }
  footer button { width: 46px; height: 46px; border-radius: 50%; border: 0; font-size: 20px;
                  background: #1f2933; color: #fff; flex: none; }
  footer button.on { background: #c0392b; animation: pulse 1s infinite; }
  @keyframes pulse { 50% { opacity: .6; } }
  #shade { position: fixed; inset: 0; background: rgba(0,0,0,.35); display: none; }
  #panel { position: fixed; top: 0; bottom: 0; left: 0; width: min(84vw, 340px); background: #fff;
           transform: translateX(-105%); transition: transform .2s; display: flex; flex-direction: column;
           box-shadow: 2px 0 12px rgba(0,0,0,.2); }
  body.open #panel { transform: none; } body.open #shade { display: block; }
  #panel h2 { margin: 0; padding: 16px; font-size: 17px; background: #1f2933; color: #fff; }
  #newBtn { margin: 12px; padding: 12px; border: 0; border-radius: 10px; background: #3a7d5c;
            color: #fff; font-size: 15px; }
  #list { flex: 1; overflow-y: auto; }
  .item { display: flex; align-items: center; border-bottom: 1px solid #edf0f2; }
  .item.cur { background: #eef6f1; }
  .item .open { flex: 1; min-width: 0; padding: 11px 14px; background: none; border: 0; text-align: left; font: inherit; color: inherit; }
  .item .open b { display: block; font-weight: 500; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  .item .open small { color: #7b8794; font-size: 12px; }
  .item .del { background: none; border: 0; color: #a0a8b0; font-size: 18px; padding: 12px 14px; }
</style></head>
<body>
<header>
  <button id="menuBtn" title="Chats">&#9776;</button>
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
<div id="shade"></div>
<nav id="panel"><h2>Chats with __NAME__</h2><button id="newBtn">+ New chat</button><div id="list"></div></nav>
<script>
const $ = id => document.getElementById(id);
const log = $("log"), input = $("text"), mic = $("mic"), statusEl = $("status");
const audio = new Audio();
document.querySelector(".avatar").textContent = "__NAME__".charAt(0);
function load(k, d) { try { const v = localStorage.getItem(k); return v === null ? d : v; } catch (e) { return d; } }
function store(k, v) { try { localStorage.setItem(k, v); } catch (e) {} }
let voiceOn = load("voiceOn", "1") === "1", handsFree = load("handsFree", "0") === "1";
let busy = false, misses = 0, current = null;
function paint() { $("voiceBtn").classList.toggle("on", voiceOn); $("freeBtn").classList.toggle("on", handsFree); }
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
async function api(path, body) {
  const r = await fetch(path, body === undefined ? {} : {method: "POST", headers: {"Content-Type": "application/json"}, body: JSON.stringify(body)});
  return r.json();
}
async function openChat(id) {
  const chat = await api("/chats/" + id);
  if (chat.error) return false;
  current = chat.id; store("chat", current); log.innerHTML = "";
  chat.messages.forEach(m => bubble(m.role, m.content, m.time));
  if (!chat.messages.length) bubble("note", "New chat. Say hello.");
  return true;
}
async function newChat() { const r = await api("/chats/new", {}); await openChat(r.id); closePanel(); }
async function start() {
  try {
    const chats = await api("/chats"), saved = load("chat", "");
    const pick = chats.find(c => c.id === saved) || chats[0];
    if (!(pick && await openChat(pick.id))) await newChat();
  } catch (e) { bubble("note", "Could not reach Termux. Is talk still running?"); }
}
async function showList() {
  const chats = await api("/chats"), list = $("list"); list.innerHTML = "";
  chats.forEach(c => {
    const item = document.createElement("div"); item.className = "item" + (c.id === current ? " cur" : "");
    const open = document.createElement("button"); open.className = "open";
    const t = document.createElement("b"); t.textContent = c.title;
    const s = document.createElement("small"); s.textContent = c.updated + " · " + c.count + " messages";
    open.append(t, s); open.onclick = async () => { await openChat(c.id); closePanel(); };
    const del = document.createElement("button"); del.className = "del"; del.textContent = "✕"; del.title = "Delete";
    del.onclick = async () => {
      if (!confirm("Delete this chat for good?\n" + c.title)) return;
      await api("/chats/" + c.id + "/delete", {});
      if (c.id === current) { current = null; log.innerHTML = ""; }
      showList(); if (!current) start();
    };
    item.append(open, del); list.appendChild(item);
  });
}
function openPanel() { document.body.classList.add("open"); showList(); }
function closePanel() { document.body.classList.remove("open"); }
async function send(text) {
  text = (text || "").trim(); if (!text || busy) return;
  busy = true; input.value = ""; bubble("user", text, now());
  const typing = bubble("them", "..."); typing.firstChild.classList.add("typing"); statusEl.textContent = "typing...";
  try {
    const d = await api("/chat", {id: current, text}); typing.remove();
    if (d.error) { bubble("note", d.error); }
    else { current = d.id; store("chat", current); bubble("them", d.reply, now()); if (voiceOn) say(d.reply); else if (handsFree) listen(); }
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
    if (/^\s*(bye|goodbye|stop)\s*$/i.test(t)) { handsFree = false; store("handsFree", "0"); paint(); bubble("user", t, now()); return; }
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
$("sendBtn").onclick = () => send(input.value);
input.addEventListener("keydown", e => { if (e.key === "Enter") send(input.value); });
$("voiceBtn").onclick = () => { voiceOn = !voiceOn; store("voiceOn", voiceOn ? "1" : "0"); if (!voiceOn) audio.pause(); paint(); };
$("freeBtn").onclick = () => { handsFree = !handsFree; store("handsFree", handsFree ? "1" : "0"); paint(); if (handsFree && !rec && !busy) listen(); };
$("menuBtn").onclick = openPanel; $("shade").onclick = closePanel; $("newBtn").onclick = newChat;
start();
</script>
</body></html>
"""


def web_main():
    with LOCK:
        move_old_chats()
    Page.voice = voice_name()
    try:
        server = ThreadingHTTPServer(("127.0.0.1", PORT), Page)
    except OSError as e:
        print("Port %d is busy (%s). Is talk already running in another Termux session?" % (PORT, e.strerror))
        return
    url = "http://127.0.0.1:%d" % PORT
    print("Chat with " + MODEL + " is open at " + url + " (voice " + Page.voice + ").")
    print("Chats are kept in " + CHAT_DIR + ". Keep this Termux session running; Ctrl+C stops the page.")
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
