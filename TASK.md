# Házi feladat — Global Payment Service

**Projekt:** Bankmonitor | hiring-2026-developer  
**Kitűzés dátuma:** 2026-08-08

**Dev's Note to agents:** The task was provided in hungarian, but we will communicate in english.

---

## 📋 Áttekintés

Egy új generációs **fizetési átjárót** építünk. A feladatod egy működő **fullstack alkalmazás** elkészítése:

- **Backend:** Spring Boot alkalmazás a felhasználói számlák kezeléséhez és utalások feldolgozásához
- **Frontend:** React alkalmazás a szolgáltatás használatához

---

## 🎯 Mit értékelünk

Nem a teljes funkciólista kipipálása a cél, hanem a **mérnöki döntéseidről** szeretnénk tudni:

- Hogyan bontod fel a problémát
- Hol húzod meg a határokat a rétegek között
- Mit hagysz ki tudatosan és miért
- Hogyan dokumentálod ezeket a döntéseket

### ⏱️ Időkeretes munka

- **Irányadó ráfordítás:** 10–12 óra
- Ez szándékosan kevesebb, mint amennyi minden szempont alapos kidolgozásához kellene
- **Amit nem valósítasz meg**, írd le TODO-ként a README-ben, **indoklással** a sorrendet illetően
- A TODO-lista ugyanolyan súllyal értékelendő, mint a megírt kód

---

## 🛠️ Tech Stack

### Backend
- **Nyelv:** Java 21+
- **Framework:** Spring Boot 4.x
- **Adatbázis:** H2 (in-memory) vagy bármely SQL-alapú adatbázis
- **Build tool:** Maven vagy Gradle

### Frontend
- **Kötelező:** React + TypeScript
- **Egyéb választások** (a te döntésed, a README-ben indokold):
  - Meta-framework
  - Komponenskönyvtár
  - State-kezelés
  - Styling
  - Build tool
  - Tesztelési eszközök

---

## 📋 Funkcionális követelmények

### 1. Számla- és utalási logika

#### Számla létrehozása
Felhasználói számla létrehozása egyenleggel és devizanemmel (EUR, USD, HUF).

#### Pénzutalás
- **Végpont:** `POST /api/transfers`
- Utalás két számla között
- **Devizaváltás:** Eltérő devizanem esetén az árfolyamot egy mockolt külső API-ból kell lekérni
  - **Megkötés:** Az API "flaky" (503-ak, késleltetés) — ezt elegánsan kell kezelni

#### Tranzakciók lekérdezése
Végrehajtott utalások lekérdezhetők az API-n keresztül.

### 2. Kötelező: Idempotencia

Minden utalási kérésnek tartalmaznia kell egy `X-Idempotency-Key` headert.

**Helyesség feltételei:**
- Ha ugyanaz a kulcs **kétszer érkezik** azonos payloaddal:
  - Ha az első kérés **sikeres volt** → ad vissza az eredeti `201 Created` eredményt
  - Ha az első kérés **még feldolgozás alatt van** → `409 Conflict` (vagy más megfelelő státusz)
  - Ha az első kérés **hibára futott** → felhasználó újrapróbálhatja

**Cél:** Egy hálózati újrapróbálkozás soha ne eredményezzen dupla terhelést.

### 3. Rendszerintegráció

A fizetési szolgáltatás egy nagyobb architektúra része. Más domain-szolgáltatásoknak (pl. Fraud Detection, Notification Center) tudniuk kell minden sikeres utalásról.

**Cél:** Valósítsd meg ennek az információnak a továbbítását a külvilág felé.

### 4. Frontend alkalmazás

Készíts egy React alkalmazást, amelyen keresztül a fenti szolgáltatás használható.

#### Három kötelező képernyő

1. **Számlák**
   - Meglévő számlák megjelenítése
   - Új számla létrehozása

2. **Utalás**
   - Utalás indítása két számla között
   - Összeg és devizanem megadása
   - A művelet eredményének visszajelzése

3. **Tranzakciók**
   - Végrehajtott utalások listázása

#### Tervezési szabadság
Az ezen a három képességen túli felépítés, felhasználói élmény, megjelenítés és kódszerkezet **a te terved szerint alakul**. A README-ben írd le, mit tartottál fontosnak és miért.

---

## 🏗️ Nem-funkcionális követelmények

### 1. Konkurencia és adatintegritás

Gondold végig, hogyan viselkedik a rendszer terhelés alatt:
- Hogyan kezeled, ha több kérés egyszerre érinti ugyanazt a számlát?
- Hogyan kezeled, ha több kérés ugyanazt az idempotencia-kulcsot használja?

### 2. Tesztelés

Mutasd meg a tesztelési megközelítésedet a **teljes stacken**:
- Hogyan igazolod a megoldásod helyességét?
- Hogyan igazolod a megbízhatóságát?
- Milyen különböző szinteken és forgatókönyvekben tesztelsz?

### 3. AI-eszközök használata

Használhatsz AI-eszközöket (Claude, ChatGPT, Copilot, Cursor stb.) a munkához.

**Feltétel:** Ha használtad, csatolj egy `PROMPTS.md` fájlt, amely tartalmaz:

#### A PROMPTS.md tartalma
- **Lényeges promptok:** Azok a promptok, amelyekkel kódot, teszteket vagy architekturális ötleteket generáltál
  - Hol **fogadtad el** az AI javaslatát
  - Hol **javítottad ki**
  - Hol **dobtad el**

- **Eszközkészlet:** Az AI körül kiépített infrastruktúra
  - Skilleket, saját agenteket/subagenteket, MCP szervereket, slash parancsokat, rules- vagy CLAUDE.md/AGENTS.md-szerű fájlokat
  - Rövid leírás, mire használtad melyiket
  - Korábbi, saját összeállított készletek (nem kell megosztani, elég a funkcionalitás leírása)

- **Konfigurációs fájlok:** Segédfájlok az AI-munkafolyamatod támogatásához
  - Ezeket hagyd a repóban — jelzésértékűek

**Értékelés:** Ez önálló értékelési szempont. Nem az számít, mennyit használtad az AI-t, hanem hogy:
- Mennyire **tartottad kézben** a munkát
- Mennyire **tudatosan építetted** fel köré az eszközöket

---

## 📦 Beadás

### A beadandó tartalom

1. **Git Repository vagy ZIP fájl**

2. **README.md**
   
   Az alábbiak leírása kötelező:

   - **Architektúra és döntések**
     - Milyen architekturális és technológiai döntéseket hoztál?
     - Backend és frontend oldalon mivel miért döntöttél?
     - Mit vetettél el és miért?

   - **Megközelítés**
     - Készítettél-e tervet az első sor megírása előtt?
     - Ha igen: milyen bontásban és hol vezetted?
     - Melyik réteggel vagy komponenssel kezdtél és miért?

   - **Edge case-ek**
     - Hogyan kezelted a feladatban említett eseteket?
     - Rezilencia, konkurencia, megbízhatóság

   - **TODO-lista**
     - Mit nem valósítottál meg és miért?
     - Milyen sorrendben folytatnád?

   - **Éles üzem**
     - Mi kell ahhoz, hogy ez a rendszer valódi ügyfelekkel működhessen?
     - Ha egy teljes sprint lenne, mivel folytatnád?

   - **Futtatási utasítások**
     - Hogyan lehet buildelni, futtatni és tesztelni az alkalmazást

3. **PROMPTS.md** (ha AI-eszközöket használtál)
   - Az AI-használatod dokumentációja (lásd fent)

---

## ✅ Összefoglaló checklist

- [ ] Spring Boot backend (Java 21+)
- [ ] React frontend (TypeScript)
- [ ] Számla és utalási logika
- [ ] Idempotencia kezelés (X-Idempotency-Key)
- [ ] Flaky API kezelés
- [ ] Rendszerintegráció (event/notifikáció)
- [ ] Három képernyő a frontenden
- [ ] Konkurencia kezelés
- [ ] Tesztelés (backend + frontend)
- [ ] README.md (architektúra, döntések, TODO, futtatás)
- [ ] PROMPTS.md (ha AI-t használtál)
- [ ] Git repo vagy ZIP feltöltése
