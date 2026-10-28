# myIO Home KT

Modern, önálló Android-kliens [myIO](https://smarthomeninja.hu/) okosotthon-vezérlőkhöz.
Kotlin + Jetpack Compose + Material 3. Közvetlenül a myIO eszközökkel kommunikál a helyi
hálózaton, a **80-as HTTP REST porton**.

> `applicationId`: `org.vaczi.myiohome`.

## Funkciók

- **Felderítés-alapú indulás** — nincs beégetett szerver; a hálózaton automatikusan megtalálja
  a myIO eszközöket, vagy kézzel is felvehetők (IP / port / belépés).
- **Több szerver** egy appban, gyors váltással; virtuális „Otthon (összes)" nézet az összes
  vezérlő összevont kezelésére. Szerverenként opcionális második cím, automatikus átállással.
- **Kimenetek vezérlése** — relék, PCA (PWM) kimenetek, fényerő, redőnyök (fel/stop/le,
  automatikus pár-felismeréssel).
- **Termosztát** — szenzor-vezérelt kimenetek küszöb-szerkesztéssel, fűtés/hűtés mód jelzéssel.
- **Zónák** — szerverek közt megosztott zónák ikonnal, kimenetek tetszőleges csoportosítása és
  sorrendje az „Otthon" dashboardon; egész zóna egy mozdulattal kapcsolható.
- **Jelenetek** — az aktuális bekapcsolt állapot rögzítése jelenetként, majd egy koppintással
  futtatás akár több szerveren át.
- **Keresés** a kimenetek között, „csak a bekapcsoltak" szűrővel.
- **Per-felhasználó jogosultság** — az eszköz olvasási/írási jogait tiszteletben tartja
  (nem-vezérelhető kimenet lakatot kap).
- **Védelem** — opcionális alkalmazászár és kimenetenkénti védelem a rendszer-hitelesítéssel
  (ujjlenyomat / PIN).
- **Szakértő mód**, témaválasztó (klasszikus AMOLED-zöld vagy Material You), fekvő nézet
  oldalsávval.
- **Mentés és megosztás** — megosztható beállítás-csomag, valamint teljes biztonsági mentés.

## Build

```powershell
.\build.ps1        # unit tesztek + bundleRelease (Play Store AAB)
.\build.ps1 -Apk   # unit tesztek + assembleRelease (telepíthető APK)
```

A szkript állítja be a `JAVA_HOME`-ot (Android Studio JBR), mert a `java` nincs PATH-on.

- AAB: `app/build/outputs/bundle/release/app-release.aab`
- APK: `app/build/outputs/apk/release/myIO-Home-KT-release.apk`
- A `release` build R8 minify + resource shrink (~2 MB), a gépi debug-kulccsal aláírva.

## Technológia

| | |
|---|---|
| Nyelv / UI | Kotlin 2.4.21, Jetpack Compose, Material 3 |
| Toolchain | Gradle 9.8.2, AGP 9.4.0, Compose BOM 2026.09.00 |
| SDK | compileSdk 37 / targetSdk 37 / minSdk 24 |
| Hálózat | OkHttp + kotlinx.serialization |
| Tárolás | DataStore |

## Architektúra

Rétegezett felépítés a `org.vaczi.myiohome` package alatt:

- **`data/model`** — domain modellek (relé/PCA/PWM kimenetek, szenzorok, redőny, zónák).
- **`data/net`** — myIO HTTP kliens, JSON-parserek, parancs-builder, LAN-felderítés.
- **`data/repo`** — repository: poll-loop + metaadat-frissítés + lemez-cache.
- **`data/local`** — DataStore-ok (szerverek, zónák, redőny-override-ok, beállítások, cache).
- **`ui`** — Compose képernyők és ViewModel (StateFlow-alapú reaktív állapot).

A myIO eszköz két végpontját kadencia szerint használja: `sens_out.json` a gyakori
állapot-poll, a `GET /` index a ritka metaadat (nevek + jogosultságok + induló szintek); a
parancsok kötegelve, `POST /info.json` hívással mennek. Egy eszköz felé minden hálózati hívás
soros — a myIO szerver (3.12 alatti firmware) nem bír párhuzamos TCP-kapcsolatot; különböző
szerverek felé párhuzamos. Android 17-től az app a helyi hálózati engedélyt
(`ACCESS_LOCAL_NETWORK`) futásidőben kéri.

## Licenc

MIT — lásd [LICENSE](LICENSE).
