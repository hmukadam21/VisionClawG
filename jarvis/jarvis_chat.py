# jarvis_chat.py v5 - Jarvis with a free Gemini brain and free live-data tools.
#
# Brain: Gemini on the free tier, the same kind of free use as VisionClaw (no Google search,
# which the free tier refuses). Live facts come from free sources Gemini calls as tools:
#   weather  Open-Meteo (no key)          news   Google News feed (no key)
#   rates    currency-api, daily (no key)  gold/silver  same currency-api, daily
# If Gemini is busy or unreachable, the local Ollama model "jarvis" answers instead.
# "jarvis --local" uses only the local model (private, offline).
# "jarvis --check" tests the Gemini brain and each live source.
import datetime, json, os, re, sys, urllib.error, urllib.parse, urllib.request
import xml.etree.ElementTree as ET

HOME = os.path.expanduser("~/Jarvis2.0")
KEY_FILE = os.path.join(HOME, "secrets", "gemini_key.txt")
OLLAMA_URL = "http://localhost:11434/api/chat"
API = "https://generativelanguage.googleapis.com/v1beta/"
BRAINS = ["gemini-flash-latest", "gemini-flash-lite-latest"]
LOCAL_MODEL = "jarvis"
HOME_CITY = ("Kuwait City, Kuwait", 29.3759, 47.9774)
UA = {"User-Agent": "Mozilla/5.0 (Linux; Android 14) JarvisTermux/5"}

SYSTEM = (
    "You are Jarvis, a personal assistant who talks like a calm, thoughtful expert friend: warm, "
    "direct and honest. The user lives in Kuwait. RULES: 1. Give the answer in your first sentence. "
    "No preamble and no filler. 2. Keep it short: 1 to 3 sentences unless asked for more; your "
    "words may be read aloud. 3. Use plain everyday words. 4. Be honest: never invent facts, "
    "numbers, names or sources; if you are not sure, say so. 5. For weather, news, currency rates "
    "and gold or silver prices ALWAYS use your tools, and say how fresh the data is. For other "
    "live facts (sports scores, stock prices, whether a website is up, and so on) say you cannot "
    "check that yet. 6. If the user may be wrong, say so kindly. 7. Warm, not gushing; light "
    "humour is fine. 8. Plain text only: no markdown, no bullet lists unless asked.")

TOOLS = [{"functionDeclarations": [
    {"name": "get_weather",
     "description": "Current weather and the forecast for a city (default: Kuwait City).",
     "parameters": {"type": "object", "properties": {
         "city": {"type": "string", "description": "City name in English; empty for Kuwait City."},
         "days": {"type": "integer", "description": "Forecast days, 1 to 3."}}}},
    {"name": "get_news",
     "description": "Latest news headlines from Google News, optionally about a topic.",
     "parameters": {"type": "object", "properties": {
         "topic": {"type": "string", "description": "Topic or name; empty for top headlines."}}}},
    {"name": "get_exchange_rates",
     "description": "Currency exchange rates (updated daily).",
     "parameters": {"type": "object", "properties": {
         "base": {"type": "string", "description": "Three-letter code, e.g. USD."},
         "targets": {"type": "string", "description": "Comma-separated codes, e.g. KWD,INR."}},
         "required": ["base", "targets"]}},
    {"name": "get_metal_price",
     "description": "Gold or silver spot price per ounce and per gram (updated daily).",
     "parameters": {"type": "object", "properties": {
         "metal": {"type": "string", "description": "gold or silver."},
         "currency": {"type": "string", "description": "Three-letter code; default KWD."}},
         "required": ["metal"]}},
]}]


# ---------- small helpers ----------

def load_key():
    """The Gemini key from the secrets file, any format (labels, quotes, spaces ignored)."""
    try:
        text = open(KEY_FILE).read()
    except OSError:
        return None
    runs = re.findall(r"[A-Za-z0-9_.+/\-]{30,}={0,2}", text)
    return max(runs, key=len) if runs else None


def fetch(url, timeout=15):
    req = urllib.request.Request(url, headers=UA)
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return resp.read()


def fetch_json(url, timeout=15):
    return json.loads(fetch(url, timeout).decode("utf-8"))


def google_error(e):
    try:
        err = json.loads(e.read().decode("utf-8", "replace")).get("error", {})
        return "HTTP %s %s: %s" % (e.code, err.get("status", ""), " ".join(str(err.get("message", "")).split())[:90])
    except Exception:
        return "HTTP " + str(e.code)


# ---------- live data tools (all free, no keys) ----------

WEATHER_WORDS = [(0, "clear"), (3, "partly cloudy"), (48, "foggy"), (57, "drizzle"), (67, "rain"),
                 (77, "snow"), (82, "rain showers"), (86, "snow showers"), (99, "thunderstorms")]


def weather_word(code):
    for top, word in WEATHER_WORDS:
        if code is not None and code <= top:
            return word if code != 0 else "clear sky"
    return "unknown"


def tool_weather(city="", days=1):
    place, lat, lon = HOME_CITY
    if city and city.strip().lower() not in ("kuwait", "kuwait city"):
        found = fetch_json("https://geocoding-api.open-meteo.com/v1/search?count=1&name="
                           + urllib.parse.quote(city.strip())).get("results") or []
        if not found:
            return {"error": "city not found: " + city}
        place = found[0]["name"] + ", " + found[0].get("country", "")
        lat, lon = found[0]["latitude"], found[0]["longitude"]
    days = max(1, min(3, int(days or 1)))
    d = fetch_json("https://api.open-meteo.com/v1/forecast?latitude=%s&longitude=%s"
                   "&current=temperature_2m,apparent_temperature,relative_humidity_2m,wind_speed_10m,weather_code"
                   "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max"
                   "&timezone=auto&forecast_days=%d" % (lat, lon, days))
    c, dl = d["current"], d["daily"]
    return {"place": place, "observed_at_local_time": c["time"],
            "now": {"temp_c": c["temperature_2m"], "feels_like_c": c["apparent_temperature"],
                    "humidity_pct": c["relative_humidity_2m"], "wind_kmh": c["wind_speed_10m"],
                    "sky": weather_word(c["weather_code"])},
            "days": [{"date": dl["time"][i], "max_c": dl["temperature_2m_max"][i],
                      "min_c": dl["temperature_2m_min"][i], "rain_chance_pct": dl["precipitation_probability_max"][i],
                      "sky": weather_word(dl["weather_code"][i])} for i in range(len(dl["time"]))],
            "source": "Open-Meteo"}


def tool_news(topic=""):
    def read(url):
        root = ET.fromstring(fetch(url))
        items = []
        for item in root.iter("item"):
            items.append({"title": item.findtext("title", ""), "published": item.findtext("pubDate", ""),
                          "source": item.findtext("source", "")})
            if len(items) == 6:
                break
        return items
    if topic:
        base = "https://news.google.com/rss/search?hl=en&gl=KW&ceid=KW:en&q="
        items = read(base + urllib.parse.quote(topic + " when:2d")) or read(base + urllib.parse.quote(topic))
    else:
        items = read("https://news.google.com/rss?hl=en&gl=KW&ceid=KW:en")
    return {"headlines": items, "source": "Google News"} if items else {"error": "no headlines found"}


def currency_table(code):
    code = code.strip().lower()
    for url in ("https://latest.currency-api.pages.dev/v1/currencies/%s.json" % code,
                "https://cdn.jsdelivr.net/npm/@fawazahmed0/currency-api@latest/v1/currencies/%s.json" % code):
        try:
            d = fetch_json(url)
            return d["date"], d[code]
        except Exception:
            continue
    raise RuntimeError("rates unavailable for " + code.upper())


def tool_rates(base, targets):
    date, table = currency_table(base)
    wanted = [t.strip().lower() for t in re.split(r"[,\s]+", targets or "") if t.strip()]
    return {"base": base.upper(), "rates": {t.upper(): table.get(t) for t in wanted},
            "rates_date": date, "source": "currency-api (daily rates)"}


def tool_metal(metal, currency="KWD"):
    code = {"gold": "xau", "silver": "xag"}.get((metal or "").strip().lower())
    if not code:
        return {"error": "only gold or silver"}
    cur = (currency or "KWD").strip().lower()
    date, table = currency_table(code)
    per_oz = table.get(cur)
    if per_oz is None:
        return {"error": "no price in " + cur.upper()}
    per_g = per_oz / 31.1035
    out = {"metal": metal, "currency": cur.upper(), "per_troy_ounce": round(per_oz, 3),
           "per_gram": round(per_g, 3), "price_date": date,
           "note": "spot price; shops add making charges", "source": "currency-api (daily)"}
    if code == "xau":
        out["per_gram_22k"] = round(per_g * 22 / 24, 3)
        out["per_gram_21k"] = round(per_g * 21 / 24, 3)
        out["per_gram_18k"] = round(per_g * 18 / 24, 3)
    return out


TOOL_RUNNERS = {
    "get_weather": (lambda a: tool_weather(a.get("city", ""), a.get("days", 1)), "the weather"),
    "get_news": (lambda a: tool_news(a.get("topic", "")), "the news"),
    "get_exchange_rates": (lambda a: tool_rates(a.get("base", "USD"), a.get("targets", "KWD")), "exchange rates"),
    "get_metal_price": (lambda a: tool_metal(a.get("metal", "gold"), a.get("currency", "KWD")), "the metal price"),
}


def run_tool(name, args):
    runner = TOOL_RUNNERS.get(name)
    if not runner:
        return {"error": "unknown tool " + name}
    print("  (checking " + runner[1] + "...)")
    try:
        return runner[0](args or {})
    except Exception as e:
        return {"error": "source unavailable (" + type(e).__name__ + ")"}


# ---------- the brains ----------

def gemini_answer(history, question, key):
    """(answer, model) from free Gemini with the live-data tools, or (None, reasons)."""
    now = datetime.datetime.now().strftime("%A %d %B %Y, %H:%M")
    system = {"parts": [{"text": SYSTEM + " It is now " + now + " in Kuwait."}]}
    reasons = []
    for model in BRAINS:
        contents = history + [{"role": "user", "parts": [{"text": question}]}]
        try:
            for _ in range(4):  # up to 3 rounds of tool use, then the answer
                body = json.dumps({"systemInstruction": system, "contents": contents, "tools": TOOLS,
                                   "generationConfig": {"temperature": 0.6}}).encode()
                req = urllib.request.Request(API + "models/" + model + ":generateContent", data=body,
                                             headers={"Content-Type": "application/json", "x-goog-api-key": key})
                with urllib.request.urlopen(req, timeout=60) as resp:
                    data = json.load(resp)
                content = data["candidates"][0].get("content", {})
                parts = content.get("parts", [])
                calls = [p["functionCall"] for p in parts if "functionCall" in p]
                if not calls:
                    text = " ".join(p.get("text", "") for p in parts if not p.get("thought")).replace("*", "").strip()
                    if text:
                        if model != BRAINS[0]:  # the first brain was busy: start with this one next time
                            BRAINS.remove(model)
                            BRAINS.insert(0, model)
                        return text, model
                    reasons.append(model + ": empty answer")
                    break
                contents.append(content)  # kept as sent (Gemini 3 needs its thought signatures back)
                answers = []
                for call in calls:
                    reply = {"name": call["name"], "response": {"result": run_tool(call["name"], call.get("args"))}}
                    if call.get("id"):
                        reply["id"] = call["id"]
                    answers.append({"functionResponse": reply})
                contents.append({"role": "user", "parts": answers})
            else:
                reasons.append(model + ": too many tool rounds")
        except urllib.error.HTTPError as e:
            reasons.append(model + ": " + google_error(e))
        except Exception as e:
            reasons.append(model + ": " + type(e).__name__)
    return None, reasons


def local_answer(history, question):
    """Answer from the local Ollama model (offline, no live data)."""
    messages = [{"role": "assistant" if m["role"] == "model" else "user",
                 "content": " ".join(p.get("text", "") for p in m["parts"])} for m in history]
    messages.append({"role": "user", "content": question})
    body = json.dumps({"model": LOCAL_MODEL, "messages": messages[-12:], "stream": False}).encode()
    req = urllib.request.Request(OLLAMA_URL, data=body, headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=300) as resp:
        return json.load(resp)["message"]["content"].strip()


# ---------- check and chat ----------

def check(key):
    print("Key: " + ("found, %d characters" % len(key) if key else "NOT found"))
    if key:
        text, why = gemini_answer([], "Say OK.", key)
        print("Gemini brain: " + ("OK via " + why if text else "FAILED: " + "; ".join(why)))
    tests = [("weather (Kuwait)", lambda: tool_weather()),
             ("news (top)", lambda: tool_news()),
             ("rates (USD to KWD, INR)", lambda: tool_rates("USD", "KWD,INR")),
             ("gold (KWD)", lambda: tool_metal("gold", "KWD"))]
    for label, test in tests:
        try:
            r = test()
            short = json.dumps(r)[:110] if "error" not in r else "ERROR " + r["error"]
            print(label + ": " + short)
        except Exception as e:
            print(label + ": FAILED " + type(e).__name__ + " " + str(e)[:80])
    try:
        print("local model: " + local_answer([], "Say OK.")[:60])
    except Exception as e:
        print("local model: FAILED " + type(e).__name__)


def main():
    key = load_key()
    if "--check" in sys.argv:
        check(key)
        return
    local_only = "--local" in sys.argv or not key
    print("Jarvis ready. Brain: " + ("local model only (offline, private)" if local_only
                                     else "Gemini (free) with live weather, news, rates, gold; local model as backup"))
    print("Type your message. Type bye to finish.")
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
        answer = None
        if not local_only:
            answer, info = gemini_answer(history, question, key)
            if answer is None:
                print("  (Gemini unavailable: " + " | ".join(info)[:160] + "; answering offline)")
        if answer is None:
            try:
                answer = local_answer(history, question)
            except Exception as e:
                print("  (local model did not answer: " + type(e).__name__ + ". Is Ollama running?)")
                continue
        history += [{"role": "user", "parts": [{"text": question}]},
                    {"role": "model", "parts": [{"text": answer}]}]
        history = history[-20:]
        print("jarvis: " + answer + "\n")


if __name__ == "__main__":
    main()
