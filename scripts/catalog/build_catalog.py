#!/usr/bin/env python3
"""
Builds the offline catalog shipped with Atlante: app/src/main/assets/catalog/atlante-catalog.db

10,000 real works, chosen and described only from sources that may be redistributed:
- Wikidata (CC0): works (literary works with at least 5 Wikipedia articles, the most known first), Italian and
  original titles, authors, first publication year, genres, sagas with their numbers (P179 + P1545), sub-series
  (P179 of a saga), follows / followed by (P155 / P156), Italian and English editions with ISBN (P629).
- Open Library (CC0): cover ids of the works (by the Open Library id that Wikidata gives, P648).
Hardcover is not used here: its data stays online, through the user's own authorization.

The tables are created with the exact SQL Room exported (app/schemas/...CatalogDatabase/1.json), so Room opens
the file as it is; the FTS4 index is filled and optimized here, never on the phone.

Every answer is kept in scripts/catalog/cache/ (not committed): a second run needs no network.
Usage: python3 scripts/catalog/build_catalog.py [--limit 10000]
"""
import argparse, csv, hashlib, io, json, os, re, sqlite3, sys, time, unicodedata, urllib.parse, urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
SCHEMA = os.path.join(ROOT, "app/schemas/app.atlante.library.catalog.CatalogDatabase/1.json")
OUT = os.path.join(ROOT, "app/src/main/assets/catalog/atlante-catalog.db")
CACHE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "cache")
AGENT = "AtlanteLibraryCatalogBuilder/1.0 (https://github.com/Mancio24x/atlante-android)"
WDQS = "https://query.wikidata.org/sparql"

# ---- network, with a cache on disk ---------------------------------------------------------------------------

_last = {}
def fetch(url, data=None, accept="application/json", gap=1.0):
    key = hashlib.sha256((url + (data or "")).encode()).hexdigest()
    path = os.path.join(CACHE, key)
    if os.path.exists(path):
        with open(path, "rb") as f: return f.read()
    host = urllib.parse.urlparse(url).netloc
    wait = _last.get(host, 0) + gap - time.time()
    if wait > 0: time.sleep(wait)
    for attempt in range(5):
        try:
            req = urllib.request.Request(url, data=data.encode() if data else None, headers={"User-Agent": AGENT, "Accept": accept})
            if data: req.add_header("Content-Type", "application/x-www-form-urlencoded")
            with urllib.request.urlopen(req, timeout=90) as r: body = r.read()
            _last[host] = time.time()
            os.makedirs(CACHE, exist_ok=True)
            with open(path, "wb") as f: f.write(body)
            return body
        except Exception as e:
            _last[host] = time.time()
            print(f"  retry {attempt + 1} ({e})", file=sys.stderr)
            time.sleep(5 * (attempt + 1))
    raise RuntimeError("failed: " + url[:200])

def sparql(query):
    body = fetch(WDQS, "query=" + urllib.parse.quote(query), "application/sparql-results+json", gap=1.0)
    return json.loads(body)["results"]["bindings"]

def val(row, name):
    v = row.get(name)
    return v["value"] if v else None

def qid(uri):
    return int(uri.rsplit("/Q", 1)[1]) if uri and "/Q" in uri else None

def batches(items, n):
    for i in range(0, len(items), n): yield items[i:i + n]

def values(ids):
    return " ".join(f"wd:Q{i}" for i in ids)

# ---- genres: the same rules as the app (saga/Genres.kt), first match by priority ----------------------------

MAIN = [
    ("Fantascienza", ["fantascienz", "science fiction", "sci-fi", "scifi", "cyberpunk", "space opera", "distopi", "dystopi"]),
    ("Fantasy", ["fantasy", "fantastic", "litrpg", "sword and sorcery", "magia", "magic"]),
    ("Horror", ["horror", "orrore", "gotico", "gothic"]),
    ("Giallo", ["giall", "mystery", "detective", "poliziesc", "noir", "crime"]),
    ("Thriller", ["thriller", "suspense", "spionaggio", "spy"]),
    ("Romanzo storico", ["storic", "historical fiction"]),
    ("Rosa", ["rosa", "romance", "romantic", "sentimental"]),
    ("Classici", ["classic", "classici"]),
    ("Fumetti", ["fumett", "comic", "graphic novel", "manga"]),
    ("Ragazzi", ["ragazz", "young adult", "juvenile", "children", "bambini"]),
    ("Poesia", ["poesi", "poetry", "poems"]),
    ("Avventura", ["avventur", "adventure", "western", "pirat"]),
    ("Saggistica", ["saggi", "nonfiction", "non-fiction", "storia", "history", "filosofi", "philosophy", "scienz", "science", "biografi", "biography"]),
    ("Narrativa", ["narrativa", "fiction", "romanz", "novel", "letteratura"]),
]

def fold(s):
    return "".join(c for c in unicodedata.normalize("NFD", s.lower()) if unicodedata.category(c) != "Mn")

def canonical(label):
    t = fold(label)
    for i, (name, words) in enumerate(MAIN):
        if any(w in t for w in words): return i, name
    return None

def searchable(text):
    """The same folding as SearchIndex.normalize in the app."""
    return re.sub(r"[^\w]+|_", " ", fold(text)).strip()

def main_genre(labels):
    found = [c for c in (canonical(l) for l in labels) if c]
    return min(found)[1] if found else None

# ---- steps ---------------------------------------------------------------------------------------------------

def candidates(min_links):
    rows = csv.DictReader(io.StringIO(fetch(WDQS, "query=" + urllib.parse.quote(
        f"SELECT ?w ?s WHERE {{ ?w wdt:P31 wd:Q7725634; wikibase:sitelinks ?s. FILTER(?s >= {min_links}) }}"), "text/csv").decode()))
    return {qid(r["w"]): int(r["s"]) for r in rows if qid(r["w"])}

def series_of(ids):
    """work → [(series, ordinal)], series → popularity of its members."""
    out = {}
    for b in batches(ids, 400):
        for r in sparql(f"""SELECT ?w ?s ?ord WHERE {{ VALUES ?w {{ {values(b)} }} ?w p:P179 ?st. ?st ps:P179 ?s. OPTIONAL {{ ?st pq:P1545 ?ord }} }}"""):
            out.setdefault(qid(val(r, "w")), []).append((qid(val(r, "s")), val(r, "ord")))
    return out

def members(series_ids):
    """All works of these sagas, with their number and how known they are (to complete the sagas)."""
    out = {}
    for b in batches(series_ids, 150):
        for r in sparql(f"""SELECT ?s ?w ?ord ?links WHERE {{ VALUES ?s {{ {values(b)} }} ?w p:P179 ?st. ?st ps:P179 ?s.
                             OPTIONAL {{ ?st pq:P1545 ?ord }} OPTIONAL {{ ?w wikibase:sitelinks ?links }} }}"""):
            out.setdefault(qid(val(r, "s")), []).append((qid(val(r, "w")), val(r, "ord"), int(val(r, "links") or 0)))
    return out

def details(ids):
    """Four light queries per group of works (one heavy query with every property times out on Wikidata)."""
    works = {w: dict(it=None, en=None, title=None, title_lang=None, year=None, ol=None, lang=None, links=0, authors=[], genres=[]) for w in ids}
    genre_ids = {}
    for n, b in enumerate(batches(ids, 150)):
        print(f"  dettagli {n * 150 + len(b)} / {len(ids)}", file=sys.stderr)
        for r in sparql(f"""SELECT ?w ?it ?en ?links WHERE {{ VALUES ?w {{ {values(b)} }}
              OPTIONAL {{ ?w rdfs:label ?it FILTER(LANG(?it) = "it") }} OPTIONAL {{ ?w rdfs:label ?en FILTER(LANG(?en) = "en") }}
              OPTIONAL {{ ?w wikibase:sitelinks ?links }} }}"""):
            d = works[qid(val(r, "w"))]; d["it"] = d["it"] or val(r, "it"); d["en"] = d["en"] or val(r, "en"); d["links"] = int(val(r, "links") or 0)
        for r in sparql(f"""SELECT ?w ?t ?d ?ol WHERE {{ VALUES ?w {{ {values(b)} }}
              OPTIONAL {{ ?w wdt:P1476 ?t }} OPTIONAL {{ ?w wdt:P577 ?d }} OPTIONAL {{ ?w wdt:P648 ?ol }} }}"""):
            d = works[qid(val(r, "w"))]
            t = r.get("t")
            if t and not d["title"]: d["title"] = t["value"]; d["title_lang"] = t.get("xml:lang")
            m = re.match(r"^([+-]?)(\d{1,4})-", val(r, "d") or "")
            y = (("-" if m.group(1) == "-" else "") + m.group(2)) if m else None
            if y and (d["year"] is None or int(y) < int(d["year"])): d["year"] = y
            if val(r, "ol") and not d["ol"]: d["ol"] = val(r, "ol")
        for r in sparql(f"""SELECT ?w ?p ?o WHERE {{ VALUES ?w {{ {values(b)} }} VALUES ?p {{ wdt:P50 wdt:P136 wdt:P407 }} ?w ?p ?o }}"""):
            d = works[qid(val(r, "w"))]; o = qid(val(r, "o")); prop = val(r, "p")
            if o is None: continue
            if prop.endswith("P50") and o not in d["authors"]: d["authors"].append(o)
            elif prop.endswith("P136"): genre_ids.setdefault(o, set()).add(qid(val(r, "w")))
            elif prop.endswith("P407") and not d["lang"]: d["lang"] = o
    # Genre names (English, as the app's rules expect) and language codes.
    for b in batches(sorted(genre_ids), 300):
        for r in sparql(f"""SELECT ?g ?l WHERE {{ VALUES ?g {{ {values(b)} }} ?g rdfs:label ?l FILTER(LANG(?l) = "en") }}"""):
            for w in genre_ids.get(qid(val(r, "g")), ()): works[w]["genres"].append(val(r, "l"))
    langs = sorted({d["lang"] for d in works.values() if isinstance(d["lang"], int)})
    codes = {}
    for b in batches(langs, 300):
        for r in sparql(f"""SELECT ?l ?c WHERE {{ VALUES ?l {{ {values(b)} }} ?l wdt:P218 ?c }}"""): codes[qid(val(r, "l"))] = val(r, "c")
    for d in works.values():
        if isinstance(d["lang"], int): d["lang"] = codes.get(d["lang"])
        d["title_lang"] = d["title_lang"] or None
    return works

def relations(ids):
    out = []
    for b in batches(ids, 400):
        for r in sparql(f"""SELECT ?w ?p ?o WHERE {{ VALUES ?w {{ {values(b)} }} VALUES ?p {{ wdt:P155 wdt:P156 }} ?w ?p ?o }}"""):
            out.append((qid(val(r, "w")), qid(val(r, "o")), "follows" if val(r, "p").endswith("P155") else "followed_by"))
    return out

def labels(ids):
    """Italian / English names of authors and sagas, Open Library author ids, parent saga."""
    out = {x: dict(it=None, en=None, any=None, ol=None, parent=None) for x in ids}
    for b in batches(ids, 200):
        for r in sparql(f"""SELECT ?x ?l WHERE {{ VALUES ?x {{ {values(b)} }} ?x rdfs:label ?l FILTER(LANG(?l) IN ("it", "en", "mul")) }}"""):
            d = out[qid(val(r, "x"))]; lang = r["l"].get("xml:lang")
            if lang == "it": d["it"] = r["l"]["value"]
            elif lang == "en": d["en"] = r["l"]["value"]
            else: d["any"] = r["l"]["value"]
        for r in sparql(f"""SELECT ?x ?ol ?p WHERE {{ VALUES ?x {{ {values(b)} }} OPTIONAL {{ ?x wdt:P648 ?ol }} OPTIONAL {{ ?x wdt:P179 ?p }} }}"""):
            d = out[qid(val(r, "x"))]
            d["ol"] = d["ol"] or val(r, "ol"); d["parent"] = d["parent"] or qid(val(r, "p"))
    return out

def editions(ids):
    found = {}
    for n, b in enumerate(batches(ids, 200)):
        if n % 10 == 0: print(f"  edizioni {n * 200} / {len(ids)}", file=sys.stderr)
        for r in sparql(f"""SELECT ?e ?w ?l ?t ?isbn ?d WHERE {{ VALUES ?w {{ {values(b)} }} ?e wdt:P629 ?w; wdt:P407 ?l. VALUES ?l {{ wd:Q652 wd:Q1860 }}
              OPTIONAL {{ ?e wdt:P1476 ?t }} OPTIONAL {{ ?e wdt:P212 ?isbn }} OPTIONAL {{ ?e wdt:P577 ?d }} }}"""):
            e = qid(val(r, "e")); d = found.setdefault(e, dict(id=e, work=qid(val(r, "w")), title=None, lang="it" if val(r, "l").endswith("Q652") else "en",
                                                                isbn=None, publisher=None, year=None))
            d["title"] = d["title"] or val(r, "t")
            d["isbn"] = d["isbn"] or (re.sub(r"[^0-9X]", "", val(r, "isbn") or "") or None)
            m = re.match(r"^\+?(\d{4})-", val(r, "d") or "")
            if m and not d["year"]: d["year"] = m.group(1)
    eds = list(found.values())
    # Publishers' names, in one go.
    pub = {}
    for b in batches(sorted(found), 300):
        for r in sparql(f"""SELECT ?e ?pl WHERE {{ VALUES ?e {{ {values(b)} }} ?e wdt:P123 ?p. ?p rdfs:label ?pl FILTER(LANG(?pl) IN ("it", "en")) }}"""):
            pub.setdefault(qid(val(r, "e")), val(r, "pl"))
    for d in eds: d["publisher"] = pub.get(d["id"])
    return eds

def covers(ol_ids):
    out = {}
    for n, b in enumerate(batches(sorted(ol_ids), 100)):
        if n % 10 == 0: print(f"  copertine {n * 100} / {len(ol_ids)}", file=sys.stderr)
        q = "key:(" + " OR ".join("/works/" + i for i in b) + ")"
        body = fetch("https://openlibrary.org/search.json?" + urllib.parse.urlencode({"q": q, "fields": "key,cover_i", "limit": 100}), gap=1.1)
        for d in json.loads(body).get("docs", []):
            if d.get("cover_i"): out[d["key"].rsplit("/", 1)[1]] = d["cover_i"]
    return out

def number(ordinal):
    try: return float((ordinal or "").replace(",", "."))
    except ValueError: return None

# ---- build ---------------------------------------------------------------------------------------------------

def build(limit):
    print("1. Opere candidate su Wikidata…", file=sys.stderr)
    pool = candidates(5)
    print(f"   {len(pool)} opere letterarie con almeno 5 voci Wikipedia", file=sys.stderr)
    ranked = sorted(pool, key=lambda w: (-pool[w], w))
    # Some candidates have no author or title on Wikidata (anonymous epics…) and cannot be recognized:
    # 15% more are looked at, then exactly [limit] complete works are kept.
    first = ranked[:int(limit * 1.15)]
    print("2. Saghe delle opere scelte…", file=sys.stderr)
    work_series = series_of(first)
    series_ids = sorted({s for v in work_series.values() for s, _ in v})
    # A "work" that is itself the saga of other chosen works (The Lord of the Rings) is a saga, not a volume.
    first = [w for w in first if w not in set(series_ids)]
    saga_members = members(series_ids)
    chosen = set(first)
    # Complete the sagas: every volume of a saga with at least two chosen works, then trim the least known
    # standalone works to stay at the limit.
    extra = set()
    for s, ms in saga_members.items():
        if sum(1 for w, _, _ in ms if w in chosen) >= 2: extra.update(w for w, _, _ in ms if w not in chosen and w not in set(series_ids))
    in_saga = {w for w, v in work_series.items() if v} | extra
    standalone = [w for w in first if w not in in_saga]
    keep = sorted(chosen | extra, key=lambda w: (-(pool.get(w, 0)), w))
    over = len(keep) - int(limit * 1.15)
    if over > 0:
        drop = set(sorted(standalone, key=lambda w: (pool[w], -w))[:over])
        keep = [w for w in keep if w not in drop]
    ids = sorted(keep)
    print(f"   {len(ids)} opere ({len(extra)} aggiunte per completare le saghe)", file=sys.stderr)

    print("3. Titoli, autori, anni, generi…", file=sys.stderr)
    works = details(ids)
    complete = [w for w, d in works.items() if (d["title"] or d["en"] or d["it"]) and d["authors"]]
    # Exactly [limit]: every volume of the sagas first (a saga is kept whole), then the best known works.
    sagas = [w for w in complete if w in in_saga]
    rest = sorted((w for w in complete if w not in in_saga), key=lambda w: (-works[w]["links"], w))
    chosen_ids = set(sorted(sagas, key=lambda w: (-works[w]["links"], w))[:limit]) | set(rest[:max(0, limit - len(sagas))])
    works = {w: d for w, d in works.items() if w in chosen_ids}
    ids = sorted(works)
    print(f"   {len(ids)} opere complete", file=sys.stderr)
    work_series = series_of(ids)
    rels = [r for r in relations(ids) if r[0] in works and r[1] in works]
    print("4. Edizioni italiane e inglesi…", file=sys.stderr)
    eds = [e for e in editions(ids) if e["work"] in works]
    print("5. Nomi di autori e saghe…", file=sys.stderr)
    author_ids = sorted({a for w in works.values() for a in w["authors"]})
    all_series = sorted({s for v in work_series.values() for s, _ in v})
    names = labels(author_ids + all_series)
    # Parents of sagas (sub-series) that are not known yet.
    parents = sorted({names[s]["parent"] for s in all_series if s in names and names[s]["parent"]} - set(all_series))
    names.update(labels(parents))
    print("6. Copertine da Open Library…", file=sys.stderr)
    cover_of = covers({w["ol"] for w in works.values() if w["ol"] and re.fullmatch(r"OL\d+W", w["ol"])})

    print("7. Database…", file=sys.stderr)
    schema = json.load(open(SCHEMA))["database"]
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    tmp = OUT + ".tmp"
    if os.path.exists(tmp): os.remove(tmp)
    db = sqlite3.connect(tmp)
    for e in schema["entities"]:
        db.execute(e["createSql"].replace("${TABLE_NAME}", e["tableName"]))
        for i in e.get("indices", []): db.execute(i["createSql"].replace("${TABLE_NAME}", e["tableName"]))
    for q in schema["setupQueries"]: db.execute(q)
    db.execute(f"PRAGMA user_version = {schema['version']}")

    italian_editions = {}
    for e in eds:
        if e["lang"] == "it" and e["title"]: italian_editions.setdefault(e["work"], []).append(e["title"])
    def name_of(x):
        n = names.get(x) or {}
        return n.get("it") or n.get("en") or n.get("any")

    written = 0
    for w, d in works.items():
        original = d["title"] or d["en"] or d["it"]
        if not original or not d["authors"]: continue          # without a title or an author it cannot be recognized
        italian = d["it"] if d["it"] and not re.fullmatch(r"Q\d+", d["it"]) else (italian_editions.get(w) or [None])[0]
        authors = [name_of(a) for a in d["authors"] if name_of(a)]
        cover = cover_of.get(d["ol"]) if d["ol"] else None
        db.execute("INSERT INTO catalog_works VALUES (?,?,?,?,?,?,?,?,?,?,?,?)", (
            w, italian or original, original, italian, d["lang"] or d["title_lang"], int(d["year"]) if d["year"] else None,
            " & ".join(authors), main_genre(d["genres"]), "|".join(d["genres"]), d["ol"],
            f"https://covers.openlibrary.org/b/id/{cover}-M.jpg" if cover else None, d["links"]))
        for a in d["authors"]: db.execute("INSERT OR IGNORE INTO catalog_work_authors VALUES (?,?)", (w, a))
        sagas = []
        for s, ordinal in work_series.get(w, []):
            db.execute("INSERT OR IGNORE INTO catalog_entries VALUES (?,?,?,?)", (s, w, ordinal, number(ordinal)))
            sagas += [x for x in ((names.get(s) or {}).get("it"), (names.get(s) or {}).get("en")) if x]
        titles = {t for t in [original, italian, d["en"], d["it"]] + italian_editions.get(w, []) if t}
        # Folded text (no accents, no case, no punctuation), as the app folds what you type: FTS4's tokenizer does not.
        db.execute("INSERT INTO catalog_fts(rowid, titles, authors, series) VALUES (?,?,?,?)",
                   (w, " | ".join(sorted({searchable(t) for t in titles})), " | ".join(searchable(a) for a in authors), " | ".join(sorted({searchable(x) for x in sagas}))))
        written += 1
    for a in author_ids:
        n = names.get(a) or {}
        if n.get("it") or n.get("en") or n.get("any"):
            db.execute("INSERT OR IGNORE INTO catalog_authors VALUES (?,?,?,?)", (a, n.get("en") or n.get("it") or n.get("any"), n.get("it"), n.get("ol")))
    for s in set(all_series) | set(parents):
        n = names.get(s) or {}
        name = n.get("en") or n.get("it") or n.get("any")
        if name: db.execute("INSERT OR IGNORE INTO catalog_series VALUES (?,?,?,?)", (s, name, n.get("it"), n.get("parent")))
    for f, t, kind in rels: db.execute("INSERT OR IGNORE INTO catalog_relations VALUES (?,?,?)", (f, t, kind))
    for e in eds:
        if e["id"] and e["title"]:
            db.execute("INSERT OR IGNORE INTO catalog_editions VALUES (?,?,?,?,?,?,?)",
                       (e["id"], e["work"], e["title"], e["lang"], e["isbn"] if e["isbn"] and len(e["isbn"]) == 13 else None, e["publisher"], int(e["year"]) if e["year"] else None))
    # Entries of works left out (no title or author) go too: a saga only lists what the catalog describes.
    db.execute("DELETE FROM catalog_entries WHERE workId NOT IN (SELECT id FROM catalog_works)")
    db.execute("DELETE FROM catalog_work_authors WHERE workId NOT IN (SELECT id FROM catalog_works)")
    db.execute("DELETE FROM catalog_editions WHERE workId NOT IN (SELECT id FROM catalog_works)")
    db.execute("DELETE FROM catalog_relations WHERE fromId NOT IN (SELECT id FROM catalog_works) OR toId NOT IN (SELECT id FROM catalog_works)")
    db.execute("INSERT INTO catalog_fts(catalog_fts) VALUES('optimize')")
    db.commit(); db.execute("VACUUM"); db.close()
    os.replace(tmp, OUT)
    report(OUT)

def report(path):
    db = sqlite3.connect(path)
    q = lambda s: db.execute(s).fetchone()[0]
    print(f"\n{path}\n  {os.path.getsize(path) / 1e6:.1f} MB")
    for t in ["catalog_works", "catalog_authors", "catalog_series", "catalog_entries", "catalog_editions", "catalog_relations"]:
        print(f"  {t}: {q('SELECT COUNT(*) FROM ' + t)}")
    print(f"  con titolo italiano: {q('SELECT COUNT(*) FROM catalog_works WHERE italianTitle IS NOT NULL')}")
    print(f"  con copertina: {q('SELECT COUNT(*) FROM catalog_works WHERE coverUrl IS NOT NULL')}")
    print(f"  con genere: {q('SELECT COUNT(*) FROM catalog_works WHERE genre IS NOT NULL')}")
    print(f"  in una saga: {q('SELECT COUNT(DISTINCT workId) FROM catalog_entries')}, con numero: {q('SELECT COUNT(*) FROM catalog_entries WHERE sortKey IS NOT NULL')}")
    db.close()

if __name__ == "__main__":
    p = argparse.ArgumentParser(); p.add_argument("--limit", type=int, default=10000); p.add_argument("--report", action="store_true")
    a = p.parse_args()
    report(OUT) if a.report else build(a.limit)
