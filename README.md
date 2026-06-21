# myIO Home KT

Modern, önálló Android-kliens [myIO](https://smarthomeninja.hu/) okosotthon-vezérlőkhöz.
Kotlin + Jetpack Compose + Material 3. Közvetlenül a myIO eszközökkel kommunikál a helyi
hálózaton, a **80-as HTTP REST porton**.

> `applicationId`: `org.vaczi.myiohome`.

## Funkciók

- **Felderítés-alapú indulás** — nincs beégetett szerver; a hálózaton automatikusan megtalálja
  a myIO eszközöket, vagy kézzel is felvehetők (IP / port / belépés).
- **Több szerver** egy appban, gyors váltással; virtuális „Otthon (összes)" nézet az összes
  vezérlő összevont kezelésére.
- **Kimenetek vezérlése** — relék, PCA (PWM) kimenetek, fényerő, redőnyök (fel/stop/le,
  automatikus pár-felismeréssel).
- **Termosztát** — szenzor-vezérelt kimenetek küszöb-szerkesztéssel.
- **Zónák** — szerverek közt megosztott zónák, kimenetek tetszőleges csoportosítása az
  „Otthon" dashboardon.
- **Per-felhasználó jogosultság** — az eszköz olvasási/írási jogait tiszteletben tartja
  (nem-vezérelhető kimenet lakatot kap).
- **Szakértő mód**, témaválasztó (klasszikus AMOLED-zöld vagy Material You), valamint
  exportálható/megosztható beállítás-csomag.

## Build

A `java` nincs PATH-on — állítsd be a `JAVA_HOME`-ot (Android Studio JBR, JDK 21):

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat :app:bundleRelease --console=plain
```

- **`bundleRelease`** → Play Store AAB: `app/build/outputs/bundle/release/app-release.aab`
- **`assembleRelease`** → telepíthető APK: `app/build/outputs/apk/release/myIO-Home-KT-release.apk`
- A `release` build R8 minify + resource shrink (~2 MB), a gépi debug-kulccsal aláírva.

## Technológia

| | |
|---|---|
| Nyelv / UI | Kotlin 2.0.21, Jetpack Compose, Material 3 |
| Toolchain | Gradle 8.10.2, AGP 8.7.2, Compose BOM 2024.10.01 |
| SDK | compileSdk 35 / targetSdk 35 / minSdk 24 |
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
állapot-poll, a `GET /` index a ritka metaadat (nevek + jogosultságok + induló szintek).
Minden hálózati hívás soros — a myIO szerver nem bír párhuzamos TCP-kapcsolatot.

## Licenc

Privát projekt.
