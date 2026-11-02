# Erzeugt app/src/main/assets/dict/{de,en,nl}.txt aus den AOSP-Wortlisten
# (LineageOS/android_packages_inputmethods_LatinIME, *_wordlist.combined.gz) und den
# FrequencyWords-Listen (hermitdave, *_50k.txt).
# Aufruf: python3 tools/build_dicts.py <ordner-mit-quellen> app/src/main/assets/dict
import gzip, re, sys, math
src, out = sys.argv[1], sys.argv[2]
ok = re.compile(r"^[^\W\d_]+(?:['-][^\W\d_]+)*$")
EXTRA = {
 "de": """vllt vlt bzw bzgl zb usw evtl ggf lg vg mfg kp ka hdl hdgdl sry thx np omg lol btw ok okay idk gn
          wg iwie iwo iwann jz grad mom xd haha hahaha hehe nö jop jup joa nee ne nen nem ner nix bissl dh ua
          uvm inkl zzgl ca nr tel std min max sorry danke bitte gerne gern alles klar""",
 "en": """lol btw idk imo imho asap omg thx ty np brb gtg tbh ok okay yeah yep nope gonna wanna gotta haha xd
          sry pls plz u ur lmao rn ngl""",
 "nl": """wss idd mss ff ok oke oké haha lol btw wrm iig gwn nvm srry sws hoezo""",
}
# Nicht aus den Untertiteln übernehmen
ENGLISH = set("""the you and to of is it in that me my what this for on your be have do not are was with we
  he she they his her him them there here yes no oh hey hi so but all just know get go got like can will
  okay don didn doesn isn wasn wouldn couldn aren shouldn weren hasn haven ain gonna yeah sir mr mrs""".split())
FRAGMENTS = set("don didn doesn isn wasn wouldn couldn aren shouldn weren hasn haven hadn ain em er goin doin".split())
SWEAR = set("""fuck fucking fucked fucker shit shitty ass asshole bastard damn bitch dick cunt piss motherfucker
  scheiße scheisse arsch arschloch fick ficken verdammt wichser hure kut klootzak godverdomme shit lul
  goddamn fuckin bullshit whore slut""".split())
EN_WORDS = set()
for line in gzip.open(f"{src}/en.gz","rt",encoding="utf-8"):
    if line.startswith(" word="):
        EN_WORDS.add(line.split(",",1)[0][6:].lower())
for l in ("de","en","nl"):
    words = {}
    for line in gzip.open(f"{src}/{l}.gz","rt",encoding="utf-8"):
        if not line.startswith(" word="): continue
        parts = dict(p.split("=",1) for p in line.strip().split(",") if "=" in p)
        w, f, fl = parts.get("word",""), int(parts.get("f","0")), parts.get("flags","")
        if "offensive" in fl: continue
        if not ok.match(w) or f < 1: continue
        words[w] = max(words.get(w,0), f)
    bylower = {}
    for w in words: bylower.setdefault(w.lower(), []).append(w)
    sub = []
    for line in open(f"{src}/sub_{l}.txt",encoding="utf-8"):
        p = line.split()
        if len(p)==2 and p[1].isdigit(): sub.append((p[0], int(p[1])))
    cmax, cmin = math.log(sub[0][1]), math.log(sub[-1][1])
    added = []
    for rank,(w,c) in enumerate(sub):
        s = int(60 + 170*(math.log(c)-cmin)/(cmax-cmin))
        if w in bylower:
            for cased in bylower[w]:
                if s > words[cased]: words[cased] = s
            continue
        if l == "en": continue  # englisches Wörterbuch ist vollständig genug
        if w in EN_WORDS: continue  # englische Wörter aus Untertiteln
        if rank >= 20000 or not ok.match(w) or len(w) < 3: continue
        # Untertitel-OCR verwechselt I und l: "lhr" = "Ihr", "lk" = "Ik"
        if w[0] == "l" and ("i" + w[1:]) in bylower: continue
        if re.search(r"(.)\1\1", w): continue          # chffffff, mmm
        if w in SWEAR or w in FRAGMENTS or w in ("qoq", "ncis", "dvdrip", "seufzt"): continue
        if "ß" in w and w.replace("ß", "ss") in bylower: continue  # alte Schreibung: daß, muß
        if l != "en" and w in ENGLISH: continue
        if "-" in w and min(len(x) for x in w.split("-")) < 2: continue   # i-i
        words[w] = s
        added.append(w)
    for w in EXTRA[l].split():
        words[w] = max(words.get(w, 0), 150)
    items = sorted(words.items(), key=lambda x:(-x[1], x[0]))[:150000]
    with open(f"{out}/{l}.txt","w",encoding="utf-8") as fh:
        for w,f in items: fh.write(f"{w}\t{f}\n")
    print(l, "ergänzt:", len(added), added[:30])
