#!/usr/bin/env python3
"""Generates missing SVG fallback tiles and the logos.txt list read by the installer.

Existing files are intentionally preserved: a downloaded vendor logo with the same name must never
be overwritten by a later catalogue regeneration.
"""
import os, sys, html
OUT = sys.argv[1] if len(sys.argv) > 1 else os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "ebx-connectors", "src", "main", "webapp", "www", "common", "images", "connect", "logos")
LIST = sys.argv[2] if len(sys.argv) > 2 else None
# id: (lines, colour, glyph)
L = {
 "Salesforce": (["sales", "force"], "#00A1E0", "cloud"), "Snowflake": (["snow", "flake"], "#29B5E8", "snow"), "Databricks": (["data", "bricks"], "#FF3621", "bricks"),
 "BigQuery": (["Big", "Query"], "#4285F4", "db"), "JDBC": (["JDBC"], "#5C6B73", "db"), "SAP": (["SAP"], "#0FAAFF", "grid"), "JDEdwards": (["JD", "Edwards"], "#C74634", "grid"),
 "OracleFusion": (["Oracle", "Fusion"], "#C74634", "grid"), "Dynamics365": (["Dynamics", "365"], "#002050", "grid"), "HubSpot": (["Hub", "Spot"], "#FF7A59", "hub"),
 "ServiceNow": (["Service", "Now"], "#62D84E", "grid"), "Workday": (["work", "day"], "#F38B00", "sun"), "Kafka": (["Kafka"], "#231F20", "stream"),
 "SFTP": (["SFTP"], "#2E7D32", "file"), "S3": (["S3"], "#FF9900", "bucket"), "AzureBlob": (["Azure", "Blob"], "#0078D4", "cloud"), "GCS": (["Cloud", "Storage"], "#4285F4", "bucket"),
 "Webhook": (["web", "hook"], "#6366F1", "plug"), "ChatWebhook": (["Slack", "Teams"], "#4A154B", "chat"), "Slack": (["Slack"], "#4A154B", "chat"),
 "GLEIF": (["GLEIF"], "#1F5FA8", "building"), "Sirene": (["INSEE", "Sirene"], "#0053B3", "flagfr"), "VIES": (["VIES"], "#003399", "eu"), "EORI": (["EORI"], "#003399", "eu"),
 "CompaniesHouse": (["Companies", "House"], "#005EA5", "building"), "Handelsregister": (["Handels", "register"], "#000000", "building"),
 "OpenCorporates": (["Open", "Corporates"], "#F58220", "globe"), "Orbis": (["Orbis"], "#E5232A", "globe"), "Creditsafe": (["Credit", "safe"], "#ED1C24", "shield"),
 "DnB": (["Dun &", "Bradstreet"], "#005EB8", "shield"), "OpenSanctions": (["Open", "Sanctions"], "#2C3E50", "shield"), "INPI": (["INPI"], "#0055A4", "flagfr"),
 "RechercheEntreprises": (["Recherche", "entreprises"], "#000091", "flagfr"), "Wikidata": (["Wikidata"], "#990000", "bars"),
 "Melissa": (["Melissa"], "#0B5FA5", "pin"), "Loqate": (["Loqate"], "#E4002B", "pin"), "BAN": (["BAN"], "#000091", "pin"), "GoogleGeocoding": (["Google", "Geocoding"], "#34A853", "pin"),
 "Nominatim": (["Nominatim", "OSM"], "#7EBC6F", "pin"), "Countries": (["Countries", "GraphQL"], "#E10098", "globe"),
 "Claude": (["Claude"], "#D97757", "spark"), "OpenAI": (["OpenAI"], "#10A37F", "spark"), "Gemini": (["Gemini"], "#4285F4", "spark"),
 "AzureOpenAI": (["Azure", "OpenAI"], "#0078D4", "spark"), "Mistral": (["Mistral", "AI"], "#FA520F", "spark"), "Ollama": (["Ollama"], "#1F1F1F", "spark"),
 "EBX": (["ON EBX"], "#1A56A8", "grid"), "OpenFDA": (["open", "FDA"], "#005EA2", "cross"),
 "default": (["?"], "#8A94A6", "plug"),
}
def glyph(kind, c="#fff"):
    o = 'fill="none" stroke="%s" stroke-width="7" stroke-linecap="round" stroke-linejoin="round"' % c
    return {
     "cloud": '<path %s d="M50 82h62a20 20 0 0 0 2-40 30 30 0 0 0-56-8 22 22 0 0 0-8 48z"/>' % o,
     "db": '<ellipse cx="80" cy="44" rx="34" ry="12" %s/><path %s d="M46 44v40c0 7 15 12 34 12s34-5 34-12V44M46 64c0 7 15 12 34 12s34-5 34-12"/>' % (o, o),
     "grid": '<rect x="42" y="30" width="30" height="30" rx="6" %s/><rect x="88" y="30" width="30" height="30" rx="6" %s/><rect x="42" y="66" width="30" height="30" rx="6" %s/><rect x="88" y="66" width="30" height="30" rx="6" %s/>' % (o, o, o, o),
     "hub": '<circle cx="80" cy="62" r="14" %s/><circle cx="46" cy="36" r="7" fill="%s"/><circle cx="116" cy="36" r="7" fill="%s"/><circle cx="46" cy="90" r="7" fill="%s"/><circle cx="116" cy="90" r="7" fill="%s"/><path %s d="M69 53L52 40M91 53l17-13M69 71L52 84M91 71l17 13"/>' % (o, c, c, c, c, o),
     "sun": '<circle cx="80" cy="62" r="18" %s/><path %s d="M80 26v10M80 88v10M44 62h10M106 62h10M55 37l7 7M98 80l7 7M105 37l-7 7M62 80l-7 7"/>' % (o, o),
     "stream": '<path %s d="M36 42h50M36 62h88M36 82h60"/><circle cx="100" cy="42" r="7" fill="%s"/><circle cx="110" cy="82" r="7" fill="%s"/>' % (o, c, c),
     "file": '<path %s d="M52 28h40l20 20v56H52z"/><path %s d="M92 28v20h20M64 70h32M64 86h32"/>' % (o, o),
     "bucket": '<path %s d="M42 40h76l-8 58H50z"/><ellipse cx="80" cy="40" rx="38" ry="10" %s/>' % (o, o),
     "plug": '<path %s d="M62 30v22M98 30v22M50 52h60v14a30 30 0 0 1-60 0zM80 96v14"/>' % o,
     "chat": '<path %s d="M36 40a12 12 0 0 1 12-12h64a12 12 0 0 1 12 12v40a12 12 0 0 1-12 12H72L48 110V92H48a12 12 0 0 1-12-12z"/><path %s d="M56 50h48M56 68h30"/>' % (o, o),
     "building": '<path %s d="M46 106V34h44v72M90 62h24v44M36 106h88M60 48h14M60 64h14M60 80h14M100 76h6M100 92h6"/>' % o,
     "flagfr": '<rect x="38" y="36" width="28" height="56" fill="#0055A4"/><rect x="66" y="36" width="28" height="56" fill="#fff"/><rect x="94" y="36" width="28" height="56" fill="#EF4135"/>',
     "eu": ''.join('<circle cx="%.1f" cy="%.1f" r="4" fill="#FFCC00"/>' % (80 + 30 * __import__("math").cos(i * 0.5236), 62 + 30 * __import__("math").sin(i * 0.5236)) for i in range(12)),
     "globe": '<circle cx="80" cy="62" r="34" %s/><path %s d="M46 62h68M80 28c14 12 14 56 0 68M80 28c-14 12-14 56 0 68"/>' % (o, o),
     "shield": '<path %s d="M80 26l38 14v26c0 22-16 38-38 46-22-8-38-24-38-46V40z"/><path %s d="M64 62l12 12 22-24"/>' % (o, o),
     "bars": '<path %s d="M44 36v52M60 36v52M76 36v52M96 36v52M112 36v52"/>' % o.replace('stroke-width="7"', 'stroke-width="9"'),
     "pin": '<path %s d="M80 108s-32-30-32-52a32 32 0 0 1 64 0c0 22-32 52-32 52z"/><circle cx="80" cy="56" r="11" %s/>' % (o, o),
     "spark": '<path fill="%s" d="M80 24l8 26 26 8-26 8-8 26-8-26-26-8 26-8zM118 84l4 12 12 4-12 4-4 12-4-12-12-4 12-4z"/>' % c,
     "cross": '<path fill="%s" d="M68 34h24v18h18v24H92v18H68V76H50V52h18z"/>' % c,
     "snow": '<path %s d="M80 26v72M49 44l62 36M49 80l62-36M80 26l-9 10M80 26l9 10M80 98l-9-10M80 98l9-10"/>' % o,
     "bricks": '<rect x="44" y="34" width="34" height="22" fill="%s"/><rect x="82" y="34" width="34" height="22" fill="%s"/><rect x="62" y="60" width="34" height="22" fill="%s"/><rect x="44" y="86" width="34" height="22" fill="%s"/><rect x="82" y="86" width="34" height="22" fill="%s"/>' % (c, c, c, c, c),
    }[kind]
os.makedirs(OUT, exist_ok=True)
names = []
for cid, (lines, colour, kind) in L.items():
    destination = os.path.join(OUT, cid + ".svg")
    if os.path.exists(destination):
        if cid != "default":
            names.append(cid)
        continue
    text_y = 132 if len(lines) == 1 else 122
    size = 26 if max(len(x) for x in lines) <= 7 else (20 if max(len(x) for x in lines) <= 10 else 16)
    texts = "".join('<text x="80" y="%d" text-anchor="middle" font-family="Helvetica Neue, Arial, sans-serif" font-weight="700" font-size="%d" fill="#fff">%s</text>' % (text_y + i * (size + 2), size, html.escape(t)) for i, t in enumerate(lines))
    svg = ('<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 160 %d" width="160" height="%d">' % (160 if len(lines) == 1 else 176, 160 if len(lines) == 1 else 176)
           + '<rect width="160" height="%d" rx="24" fill="%s"/>' % (160 if len(lines) == 1 else 176, colour) + '<g transform="translate(24,6) scale(0.7)">' + glyph(kind) + '</g>' + texts + '</svg>')
    open(destination, "w", encoding="utf-8").write(svg)
    if cid != "default":
        names.append(cid)
if LIST:
    # Include vendor assets that do not have a generated fallback definition in L.
    names = [os.path.splitext(name)[0] for name in os.listdir(OUT)
             if name.lower().endswith(".svg") and name != "default.svg"]
    open(LIST, "w").write("\n".join(sorted(names)) + "\n")
print("%d logos in %s" % (len(L), OUT))
