# AutoTAK-plugin

An ATAK plugin that passes the ATAK picture to the **AutoTAK** app (a TAK client for Android Auto)
on the same phone. With it AutoTAK can work in "linked" mode: it does not connect to a TAK server
itself and only shows what ATAK has.

*Polski opis jest niżej.*

## What the plugin passes to AutoTAK

- map items from ATAK (contacts, markers, shapes, tactical graphics, mission content) and their changes,
- own position, callsign, team and role,
- the state of ATAK's server connection (numbers only: configured and connected),
- configuration: user icon sets, online map definitions and the name of the selected map,
- the item selected in ATAK (from version 1.4).

From AutoTAK to ATAK goes only the selection of an item. Data never leaves the phone: the plugin
binds to a service of the AutoTAK app through Binder (the interface is in `app/src/main/aidl`).
The protocol is described in `docs/PROTOCOL.pl.md` (in Polish).

## In ATAK

- **Panel** (the AutoTAK button in the tools menu): link state, version of the connected AutoTAK
  app, number of items passed, a summary of the settings, an event log that can be shared as a
  file, change history, and the buttons Settings and Force synchronization.
- **Settings** (Settings → Tool Preferences → Specific Tool Preferences → AutoTAK): what ATAK does
  when an item is selected in AutoTAK, all items or contacts only, and switches for own data,
  icon sets, map definitions and the selected map name.

The plugin texts are in English and Polish.

## Requirements and compatibility

- ATAK 5.8 (CIV).
- The AutoTAK app 2.0.0 or newer, in linked mode. Newer plugins work with older app versions and
  the other way round; the two sides agree on the protocol version when they connect.

## Building

Requires the ATAK SDK 5.8.0 (CIV). Copy `template.local.properties` to `local.properties` and set
`sdk.path` and `takdev.plugin` (the `atak-gradle-takdev.jar` file from the SDK).

```
./gradlew assembleCivDebug     # for tests on the SDK (developer) build of ATAK
./gradlew assembleCivRelease   # for signing in the TAK pipeline
```

A debug build loads only in the SDK build of ATAK. ATAK from Google Play requires a build signed
by the TAK Product Center. The version is set in two places that must change together:
`PLUGIN_VERSION` in `app/build.gradle` and `takStaticVersion` in `gradle.properties`.

## About this repository

This is a read-only public copy of the plugin sources, published with every plugin release as
required by its licence. Development happens elsewhere, so pull requests are not merged here.

## Licence

GNU General Public License v3 — see `LICENSE`.

---

# AutoTAK-plugin (po polsku)

Wtyczka do ATAK, która przekazuje obraz sytuacji z ATAK do aplikacji **AutoTAK** (klient TAK dla
Android Auto) na tym samym telefonie. Dzięki niej AutoTAK może pracować w trybie „Powiązany”: sam
nie łączy się z serwerem TAK, tylko pokazuje to, co ma ATAK.

Wtyczka przekazuje: obiekty z mapy ATAK i ich zmiany, własną pozycję, znak wywoławczy, zespół
i rolę, stan połączenia ATAK z serwerem, zestawy ikon, definicje map online, nazwę wybranej mapy
oraz obiekt wybrany w ATAK. Z AutoTAK do ATAK trafia tylko zaznaczenie obiektu. Dane nie wychodzą
poza telefon. Opis protokołu: `docs/PROTOCOL.pl.md`.

W ATAK wtyczka ma panel (przycisk AutoTAK w menu narzędzi: stan łącza, wersja aplikacji, dziennik
zdarzeń, historia zmian, „Ustawienia”, „Wymuś synchronizację”) i stronę ustawień (Ustawienia →
Preferencje Narzędzi → Preferencje poszczególnych narzędzi → AutoTAK).

Wymagania: ATAK 5.8 (CIV) i aplikacja AutoTAK 2.0.0 lub nowsza w trybie powiązanym. Budowanie
i licencja — jak w części angielskiej powyżej. To repozytorium jest publiczną kopią źródeł tylko
do odczytu, publikowaną przy każdym wydaniu wtyczki.
