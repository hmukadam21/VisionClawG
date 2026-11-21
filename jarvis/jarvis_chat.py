# jarvis_chat.py - chat with Jarvis (local Ollama model) plus live facts.
# Questions about prices, news, weather and other "now" topics are looked up first
# through Gemini with Google Search; Jarvis then answers in its own style from those facts.
# Start a message with "web " to force a lookup. Type bye to finish.
import datetime, json, os, re, sys, urllib.error, urllib.request

HOME = os.path.expanduser("~/Jarvis2.0")
KEY_FILE = os.path.join(HOME, "secrets", "gemini_key.txt")
OLLAMA_URL = "http://localhost:11434/api/chat"
MODEL = sys.argv[1] if len(sys.argv) > 1 else "jarvis"
GEMINI_MODELS = ["gemini-flash-latest", "gemini-flash-lite-latest"]
LIVE = re.compile(
    r"\b(price|prices|rate|rates|today|tonight|tomorrow|yesterday|now|current|currently|latest|"
    r"news|headlines?|weather|temperature|forecast|rain|score|scores|who won|results?|"
    r"stocks?|shares?|market|nifty|sensex|gold|silver|oil|bitcoin|crypto|dollar|rupee|dinar|"
    r"exchange|this week|election|president|prime minister|ceo)\b", re.I)


def load_key():
    try:
        text = open(KEY_FILE).read()
    except OSError:
        return None
    found = re.search(r"AIza[0-9A-Za-z_\-]{30,}", text)
    return found.group(0) if found else None


def live_facts(question, key):
    """Short current facts from Gemini + Google Search, or (None, reason)."""
    now = datetime.datetime.now().strftime("%Y-%m-%d %H:%M")
    prompt = ("It is now " + now + " (Kuwait). Search Google and give only the current facts needed "
              "to answer the question below, in at most 3 short sentences, with numbers, units and "
              "the date or time they refer to. No advice, no links. Question: " + question)
    body = json.dumps({"contents": [{"role": "user", "parts": [{"text": prompt}]}],
                       "tools": [{"google_search": {}}]}).encode()
    reason = "no model answered"
    for model in GEMINI_MODELS:
        url = "https://generativelanguage.googleapis.com/v1beta/models/" + model + ":generateContent"
        req = urllib.request.Request(url, data=body, headers={
            "Content-Type": "application/json", "x-goog-api-key": key})
        try:
            with urllib.request.urlopen(req, timeout=40) as resp:
                data = json.load(resp)
            parts = data["candidates"][0]["content"]["parts"]
            text = " ".join(p.get("text", "") for p in parts).replace("*", "").strip()
            if text:
                return text[:700], model
            reason = model + ": empty answer"
        except urllib.error.HTTPError as e:
            reason = model + ": HTTP " + str(e.code)
        except Exception as e:
            reason = model + ": " + type(e).__name__
    return None, reason


def ask_jarvis(messages):
    body = json.dumps({"model": MODEL, "messages": messages, "stream": False}).encode()
    req = urllib.request.Request(OLLAMA_URL, data=body, headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=300) as resp:
        return json.load(resp)["message"]["content"].strip()


def main():
    key = load_key()
    print("Jarvis (" + MODEL + ") ready. Live facts: " + ("ON" if key else "OFF - no Gemini key found"))
    print("Type your message. Start with 'web ' to force a lookup. Type bye to finish.\n")
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
        forced = question.lower().startswith("web ")
        if forced:
            question = question[4:].strip()
        content = question
        if key and (forced or LIVE.search(question)):
            print("  (looking it up...)")
            facts, source = live_facts(question, key)
            if facts:
                stamp = datetime.datetime.now().strftime("%H:%M")
                print("  (live facts from Google via " + source + ")")
                content = question + "\n\n[LIVE FACTS fetched at " + stamp + " from Google Search: " + facts + "]"
            else:
                print("  (lookup failed: " + source + ")")
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
