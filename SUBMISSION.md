# Döntések

1. A legfontosabb döntés az agentic vs tradicionál fejelesztési forma közötti döntés. Az időtartam alatt már nem lesz alakalom váltásra. 

A döntésem az agentic fejlesztés volt. Érvelés: a Java technológiát nem ismerem, és a határidőn belül ezzel a megoldással lehet bármi működőt alkotni.


2. AI toolok.

Agentic fejlesztéshez Calude modelleket, és [`Matt Pocock`](https://github.com/mattpocock/skills) módszerét használtam. A módszer miatt a kért PROMPTS file-t nem tudom érdemlegesen kitölteni. Nem vibe-kódoltam, nincsenek magic promptjaim. A task alapján egy hosszabb (kb 3 óra a 10-12-ből) interjú stílusú session alatt (Matt terminológiájával grilling) megvizsgáltuk a domaint, és meghoztuk a főbb döntéseket, melyeket ezután ticketekre konvertáltunk, ezeken dolgoztak az Agent-ek.

A session logokat csatoltam. a session-logs/ mappában.

MCP-t nem használtam. Saját skillből jelentőset nem használtam, egy /land_worktree-re volt szükség, miután egyértelművé vált hogy a ticketek sorban fejlesztése túl hosszú lesz, és 4 párhuzamos ügynököt engedtem a feladatra, akik worktree-ken dolgoztak.

3. Mi lett kész és mi nem? Döntés: aktvív döntés itt nem született. Mivel a feladat beveallott célja volt hogy ne lehessen befejezni, az időtartamra belőni a feature-set-et nem volt értelme, ledizájnoltam a tejles domaint, ticketekre bontottam, és addig juttotam implementációban, ameddig sikerült. A ticketek a .scratch/ mappában találhatóak.

4. Fontosabb architecturális döntések:
- A tranzakciókat egy ledger-es, abszolute async megközelítéssel terveztem, ahol akár hány külső service-nek jóvá kell hagynia mielőtt sikeresre vált. Server Side Eventek publikálásával terveztem mind a frotendet mind egyéb fogyasztókat a státuszról tájékoztatni. Leginkább a rendszer rugalmassága miatt döntöttem-e mellett, és mert tudtommal bankoknál ez a standard pattern. Az ár a komplexitás.
- A pénzre egy saját osztályt terveztem, tehát nem szám. A kerekítési problémák elekerülése miatt döntöttem e mellett, mert a Java egyetlen beépített típusával se voltam elégedett. Megjegyzés: a fejlesztés során megbántam ezt a döntést. Egyrész overengineered, másrészt egy szoftverfejlesztési problémát priorizáltam a való világ domainje felett, azaz fel se tettem a kérdést hogy kezeli a bank a pénzt és a kerekítést.

Kevésbé fontos döntések a grilling session logokban olvashatóak. Ad-hoc, implementáció során felmerült döntések miatt az implementációs session logokat is csatoltam.