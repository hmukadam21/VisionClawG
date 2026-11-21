# jarvis_chat.py - chat with Jarvis (local Ollama model) plus live facts.
# Questions about prices, news, weather and other "now" topics are looked up first
# through Gemini with Google Search; Jarvis then answers in its own style from those facts.
# Start a message with "web" or "search" to force a lookup. Type bye to finish.
# "jarvis --check" tests the Gemini key and models and prints what Google says.
#
# v3 (21 Nov): Google's own error text is shown when a lookup fails; when the usual models
# are refused, other Gemini models on the key are tried and the first that works is
# remembered (gemini_model.txt); a failed lookup gets an honest fixed answer instead of
# Jarvis guessing (it had wrongly called xcite.com inactive).
import datetime, json, os, re, sys, urllib.error, urllib.request

HOME = os.path.expanduser("~/Jarvis2.0")
KEY_FILE = os.path.join(HOME, "secrets", "gemini_key.txt")
MODEL_FILE = os.path.join(HOME, "gemini_model.txt")   # the Gemini model that last worked
OLLAMA_URL = "http://localhost:11434/api/chat"
API = "https://generativelanguage.googleapis.com/v1beta/"
ARGS = [a for a in sys.argv[1:] if not a.startswith("--")]
MODEL = ARGS[0] if ARGS else "jarvis"
GEMINI_MODELS = ["gemini-flash-latest", "gemini-flash-lite-latest"]
SKIP_WORDS = ("live", "tts", "image", "embedding", "audio", "vision", "aqa", "robotics", "learnlm")
LIVE = re.compile(
    r"\b(price|prices|rate|rates|today|tonight|tomorrow|yesterday|now|current|currently|latest|"
    r"news|headlines?|weather|temperature|forecast|rain|score|scores|who won|results?|"
    r"stocks?|shares?|market|nifty|sensex|gold|silver|oil|bitcoin|crypto|dollar|rupee|dinar|"
    r"exchange|this week|election|president|prime minister|ceo)\b"
    r"|\b[a-z0-9-]+\.(com|net|org|io|ai|kw|in|co|me|app)\b", re.I)
# "web gold rate", "websearch ...", "search: ...", "google ..." force a lookup
FORCE = re.compile(r"^(web ?search|web|search|google)\b[:\s]*", re.I)


def load_key():
    """The Gemini key from the secrets file, whatever its format: a label (NAME=), quotes or
    spaces around it are ignored; the longest key-like run of characters is used."""
    try:
        text = open(KEY_FILE).read()
    except OSError:
        return None, "no key file at " + KEY_FILE
    runs = re.findall(r"[A-Za-z0-9_.+/\-]{30,}={0,2}", text)
    if runs:
        return max(runs, key=len), ""
    shape = text.strip()
    return None, "key file has %d characters starting %r, nothing key-like in it" % (len(shape), shape[:4])


def google_error(e):
    """Short reason from a Gemini HTTP error: code, status, Google's message and quota limit."""
    try:
        err = json.loads(e.read().decode("utf-8", "replace")).get("error", {})
    except Exception:
        return "HTTP " + str(e.code)
    message = " ".join(str(err.get("message", "")).split())
    limit = re.search(r"limit: ?(\d+)", message)
    quota = ""
    for detail in err.get("details", []) or []:
        for v in detail.get("violations", []) or []:
            quota = v.get("quotaId") or v.get("quotaMetric") or quota
    text = ("HTTP %s %s" % (e.code, err.get("status", ""))).strip()
    if limit or quota:
        # Quota refusals: the limit and the quota's name say it all
        extra = ["limit " + limit.group(1)] if limit else []
        extra += [quota.split("/")[-1]] if quota else []
        return text + " (" + ", ".join(extra) + ")"
    return text + ": " + message[:100]


def gemini_call(model, key, body, timeout=40):
    """(text, None) on success, (None, reason) on failure."""
    url = API + "models/" + model + ":generateContent"
    req = urllib.request.Request(url, data=body, headers={
        "Content-Type": "application/json", "x-goog-api-key": key})
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            data = json.load(resp)
        parts = data["candidates"][0]["content"]["parts"]
        text = " ".join(p.get("text", "") for p in parts).replace("*", "").strip()
        return (text, None) if text else (None, "empty answer")
    except urllib.error.HTTPError as e:
        return None, google_error(e)
    except Exception as e:
        return None, type(e).__name__


def other_models(key):
    """Text models on this key that can generate content (newest first), for a fallback."""
    req = urllib.request.Request(API + "models?pageSize=200", headers={"x-goog-api-key": key})
    try:
        with urllib.request.urlopen(req, timeout=20) as resp:
            models = json.load(resp).get("models", [])
    except Exception:
        return []
    names = []
    for m in models:
        name = m.get("name", "").replace("models/", "")
        if "generateContent" not in m.get("supportedGenerationMethods", []):
            continue
        if "gemini" not in name or any(w in name for w in SKIP_WORDS):
            continue
        names.append(name)
    # Flash before others, full before lite, stable before preview; newest version first in each
    names = sorted(set(names), reverse=True)
    return sorted(names, key=lambda n: ("flash" not in n, "lite" in n, "preview" in n or "exp" in n))


def remembered_model():
    try:
        return open(MODEL_FILE).read().strip() or None
    except OSError:
        return None


def live_facts(question, key):
    """Short current facts from Gemini + Google Search: (facts, model) or (None, reasons)."""
    now = datetime.datetime.now().strftime("%Y-%m-%d %H:%M")
    prompt = ("It is now " + now + " (Kuwait). Search Google and give only the current facts needed "
              "to answer the question below, in at most 3 short sentences, with numbers, units and "
              "the date or time they refer to. No advice, no links. Question: " + question)
    body = json.dumps({"contents": [{"role": "user", "parts": [{"text": prompt}]}],
                       "tools": [{"google_search": {}}]}).encode()
    first = [m for m in [remembered_model()] + GEMINI_MODELS if m]
    tried, reasons = [], []
    for model in first + ["*"]:
        if model == "*":
            # The usual models were refused: try the other models this key offers (at most 4)
            extra = [m for m in other_models(key) if m not in tried][:4]
            if extra:
                print("  (usual models refused, trying: " + ", ".join(extra) + ")")
            candidates = extra
        else:
            candidates = [model]
        for m in candidates:
            if m in tried:
                continue
            tried.append(m)
            text, reason = gemini_call(m, key, body)
            if text:
                if m != remembered_model():
                    try:
                        open(MODEL_FILE, "w").write(m)
                    except OSError:
                        pass
                return text[:700], m
            reasons.append(m + ": " + reason)
    return None, reasons


def ask_jarvis(messages):
    body = json.dumps({"model": MODEL, "messages": messages, "stream": False}).encode()
    req = urllib.request.Request(OLLAMA_URL, data=body, headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=300) as resp:
        return json.load(resp)["message"]["content"].strip()


def check(key):
    """jarvis --check: what Google says about this key and its models."""
    print("Key: " + ("found, %d characters, starts %s" % (len(key), key[:4]) if key else "NOT found"))
    if not key:
        return
    names = other_models(key)
    print("Text models on this key: %d" % len(names))
    for n in names[:12]:
        print("   " + n)
    body = json.dumps({"contents": [{"role": "user", "parts": [{"text": "What is today's date? One line."}]}],
                       "tools": [{"google_search": {}}]}).encode()
    plain = json.dumps({"contents": [{"role": "user", "parts": [{"text": "Say OK."}]}]}).encode()
    order = ([remembered_model()] if remembered_model() else []) + GEMINI_MODELS + names[:3]
    for m in [m for i, m in enumerate(order) if m not in order[:i]]:
        text, reason = gemini_call(m, key, body, timeout=30)
        print("\n" + m + " with Google Search: " + ("OK: " + text[:80] if text else reason))
        if not text:
            text2, reason2 = gemini_call(m, key, plain, timeout=30)
            print("   without search: " + ("OK" if text2 else reason2))


def main():
    key, problem = load_key()
    if "--check" in sys.argv:
        check(key)
        return
    print("Jarvis (" + MODEL + ") ready. Live facts: " + ("ON" if key else "OFF - " + problem))
    print("Type your message. Start with 'web' or 'search' to force a lookup. Type bye to finish.")
    print("")
    history = []
    while True:
        try:
            question = input("you: ").strip()
        except (EOFError, KeyboardInterrupt):
            print()
            break
        if not question:
            continue
        if question.lower() in ("bye", "/bye", "exit", "quit"):
            break
        force = FORCE.match(question)
        forced = force is not None
        if forced:
            question = question[force.end():].strip() or question
        content = question
        if key and (forced or LIVE.search(question)):
            print("  (looking it up...)")
            facts, source = live_facts(question, key)
            if not facts:
                # Never let Jarvis guess when the web was needed but could not be reached
                for reason in source:
                    print("  (lookup failed: " + reason + ")")
                answer = "I couldn't check the web just now, so I won't guess. Try again in a minute."
                print("jarvis: " + answer + "\n")
                history += [{"role": "user", "content": question}, {"role": "assistant", "content": answer}]
                continue
            stamp = datetime.datetime.now().strftime("%H:%M")
            print("  (live facts from Google via " + source + ")")
            content = question + "\n\n[LIVE FACTS fetched at " + stamp + " from Google Search: " + facts + "]"
        history.append({"role": "user", "content": content})
        try:
            answer = ask_jarvis(history[-12:])
        except Exception as e:
            print("  (Jarvis did not answer: " + type(e).__name__ + ". Is Ollama running?)")
            history.pop()
            continue
        history.append({"role": "assistant", "content": answer})
        print("jarvis: " + answer + "\n")


if __name__ == "__main__":
    main()
