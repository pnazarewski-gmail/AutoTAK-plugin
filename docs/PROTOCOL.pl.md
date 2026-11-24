# Tryb powiązany (companion) — AutoTAK i wtyczka AutoTAK-plugin

Od wersji 2.0.0 AutoTAK ma dwa tryby pracy (Ustawienia → Tryb pracy):

- **Samodzielny** (domyślny) — aplikacja sama łączy się z serwerem TAK.
- **Powiązany** — aplikacja pokazuje to, co ma ATAK na tym samym telefonie. Dane przekazuje
  wtyczka `AutoTAK-plugin` (katalog `AutoTAK-plugin/`, licencja GPL-3) działająca w ATAK.
  AutoTAK nie łączy się wtedy z serwerem i nie wysyła pozycji.

## Łącze

- Aplikacja wystawia usługę `dev.autotak.app.companion.CompanionService` (Binder, AIDL).
  Komponent jest włączony tylko w trybie powiązanym, więc poza nim ATAK nie budzi aplikacji.
- Wtyczka podłącza się z procesu ATAK (`bindService`), kolejno do pakietu wydania i kompilacji
  testowej; ponawia próbę co 15 s. Dane nie wychodzą poza telefon.
- **Kto może się podłączyć.** Przy włączeniu trybu aplikacja zapisuje zainstalowane aplikacje ATAK
  (pakiet i skrót SHA-256 certyfikatu podpisu) jako zaufane — przełączenie trybu jest zgodą
  użytkownika. Każde wywołanie jest sprawdzane: UID wołającego musi należeć do zaufanego pakietu
  o tym samym podpisie. ATAK zainstalowany później dodaje się przyciskiem „Zaufaj zainstalowanemu ATAK”.
- Interfejs ma jedno źródło: `AutoTAK-plugin/app/src/main/aidl/dev/autotak/companion/`
  (aplikacja dołącza ten katalog do swojej kompilacji).

## Protokół (wersja 1)

Wtyczka → aplikacja (`ICompanionHost`):

| Wywołanie | Treść |
|---|---|
| `hello(protocol, pluginVersion, atakVersion, plugin)` | Powitanie; zwraca wersję protokołu albo 0 (odmowa: tryb wyłączony albo niezaufany nadawca). Powtarzane co 15 s — odmowa kończy łącze. |
| `events(cot, flags)` | Paczka obiektów jako CoT XML (do ok. 100 tys. znaków na paczkę). Flagi: 1 — początek pełnego obrazu, 2 — koniec. Po końcu aplikacja usuwa obiekty, których w obrazie nie było. |
| `removed(uids)` | Obiekty usunięte z mapy ATAK. |
| `state(json)` | `self` (uid, callsign, team, role, lat, lon, hae, speed, course), `servers` (configured, connected — same liczby), `activeMap` (nazwa mapy wybranej w ATAK). Wysyłane przy zmianie, najwyżej co sekundę. |
| `config(json)` | `iconsets` (uid, name, version, count) i `maps` (file, xml — definicje map online). Wysyłane przy zmianie. |

Aplikacja → wtyczka (`ICompanionPlugin`):

| Wywołanie | Treść |
|---|---|
| `requestSnapshot()` | Prośba o ponowny pełny obraz. |
| `select(uid)` | Zaznaczenie obiektu; `null` zdejmuje zaznaczenie. Reakcję ATAK wybiera użytkownik w ustawieniach wtyczki: nic, przejście mapy na obiekt (`FOCUS`) albo przejście i zaznaczenie obiektu (`SHOW_MENU`, `SHOW_DETAILS`). |
| `openIconSet(uid)` | Zestaw ikon jako zip (`iconset.xml` i pliki) przez potok. |

## Ustawienia wtyczki (od wersji 1.1)

Strona w ATAK: Ustawienia → Preferencje Narzędzi → Preferencje poszczególnych narzędzi → AutoTAK.
Zmiana działa od razu (wtyczka wysyła od nowa konfigurację, stan i pełny obraz).

| Ustawienie | Wartości | Domyślnie |
|---|---|---|
| Po wybraniu obiektu w AutoTAK | Nic / Przesuń mapę na obiekt / Przesuń mapę i zaznacz obiekt (menu i okno informacji) | Przesuń mapę na obiekt |
| Obiekty mapy | Wszystkie / Tylko kontakty | Wszystkie |
| Własna pozycja i dane własne | tak / nie | tak |
| Zestawy ikon | tak / nie | tak |
| Definicje map online | tak / nie | tak |
| Nazwa wybranej mapy | tak / nie | tak |

- „Tylko kontakty” — obiekt z danymi zespołu albo typu `a-f-G-U-C`, czyli ta sama reguła, którą
  aplikacja rozpoznaje kontakt.
- Stan połączenia ATAK z serwerem i identyfikator własnego urządzenia są przekazywane zawsze.
- Po wyłączeniu danych własnych znak wywoławczy wraca do profilu AutoTAK od razu. Pozycja znika
  z mapy od aplikacji 2.1.0 (stan bez pozycji czyści ją); w 2.0.x ostatnia pozycja zostawała do
  ponownego uruchomienia aplikacji.
- Ustawienia muszą leżeć na najwyższym poziomie strony (`res/xml/preferences.xml`): ATAK przenosi
  na swoją stronę tylko takie, a zagnieżdżone w grupie próbowałby zapisać w danych wtyczki, do
  których nie ma dostępu, i gubił. Wartości czyta się przez `AtakPreferences`.

## Panel wtyczki (od wersji 1.2)

Przycisk AutoTAK w menu narzędzi ATAK otwiera boczny panel: stan łącza (połączono / czekam na
aplikację / AutoTAK nie jest w trybie powiązanym / połączenie przerwane), aplikacja po drugiej
stronie (wydanie albo wersja testowa), wersja protokołu, liczba przekazanych obiektów, wersja
wtyczki i bieżące ustawienia w skrócie. Stan odświeża się co sekundę. Przyciski: „Ustawienia”
(strona ustawień wtyczki) i „Wymuś synchronizację” (konfiguracja, stan i pełny obraz od nowa;
bez łącza — natychmiastowa próba połączenia).

Reakcja „Przesuń mapę i zaznacz obiekt” od 1.2 odpowiada dotknięciu obiektu w ATAK: `FOCUS`,
`SHOW_MENU` i `SHOW_DETAILS` (okno informacji w prawym górnym rogu); zdjęcie wyboru wysyła
`HIDE_MENU`, `HIDE_DETAILS` i `UNFOCUS`. W 1.1 nie było okna informacji.

## Zgodność wsteczna

Użytkownicy mogą mieć starszą aplikację i nowszą wtyczkę albo odwrotnie, więc:

- **Nowa wtyczka obsługuje wszystkie wydane wersje AutoTAK.** `hello` zwraca wersję protokołu
  aplikacji; wtyczka nie używa wywołań ani pól nowszych niż ta wersja.
- **Nowa aplikacja obsługuje starsze wtyczki** — brakujących danych nie pokazuje.
- **Zmiany tylko przez dodawanie:** nowe pola JSON i nowe wywołania na końcu interfejsu; istniejące
  nie zmieniają znaczenia ani kolejności. Nieznane pola obie strony pomijają.
- Zmiana łamiąca zgodność wymaga nowego numeru protokołu i obsługi obu numerów jednocześnie.
- Przed wydaniem: test nowej wtyczki z poprzednią wydaną aplikacją i nowej aplikacji z poprzednią wtyczką.

| Wtyczka | Protokół | Działa z aplikacją |
|---|---|---|
| 1.0 | 1 | 2.0.0, 2.0.1 |
| 1.1 | 1 | 2.0.0, 2.0.1 |
| 1.2 | 1 | 2.0.0, 2.0.1, 2.1.0, 2.2.0 |
| 1.3 | 2 (z aplikacją 2.0.0–2.1.0 rozmawia w wersji 1) | 2.0.0, 2.0.1, 2.1.0, 2.2.0, 2.3.0 |
| 1.4–1.4.4, 1.5.0 | 3 (ze starszą aplikacją w jej wersji) | 2.0.0–2.3.6 |

Protokół 3 (aplikacja 2.3.0, wtyczka 1.4) dodaje wywołanie `selected(uid)`: wtyczka zgłasza obiekt
wybrany w ATAK (rozgłoszenie `SHOW_DETAILS`) i zdjęcie wyboru (`HIDE_DETAILS`, z opóźnieniem 0,4 s,
bo ATAK przy zmianie wyboru najpierw ukrywa stare okno). Nie zgłasza obiektów, których aplikacja
nie dostała, ani własnej reakcji na wybór z AutoTAK (2 s po `select`). Aplikacja reaguje według
ustawienia `linkedSelectAction` (0 — nic, 1 — gdy podążanie wyłączone, 2 — zawsze) i nie odsyła
takiego wyboru do ATAK. Sprawdzone 2026-11-23: wtyczka 1.4 z aplikacją 2.2.0, aplikacja 2.3.0
z wtyczką 1.3.

Protokół 2 (aplikacja 2.2.0, wtyczka 1.3) dodaje jedno wywołanie: `hostInfo()` zwraca JSON
z wersją aplikacji (`app`), skrótem commita (`build`) i numerem protokołu. Obie strony zgłaszają
w `hello` swój najwyższy numer, a rozmawiają w niższym z dwóch; wtyczka woła `hostInfo` tylko
przy wersji co najmniej 2, a dla starszej aplikacji pokazuje „2.1.0 lub wcześniejsza”.
Sprawdzone 2026-11-22: wtyczka 1.3 z aplikacją 2.1.0 i aplikacja 2.2.0 z wtyczką 1.2.

## Dzienniki zdarzeń (aplikacja 2.2.0, wtyczka 1.3)

Obie strony prowadzą dziennik zdarzeń technicznych (`EventLog` w aplikacji, `PluginLog` we
wtyczce): start i wersje, połączenia, odmowy, rozłączenia, zmiany trybu i ustawień, liczby
obiektów, wyniki uzgodnienia konfiguracji, stan połączenia z serwerem. Wpisy są po angielsku
i nie zawierają treści obiektów, znaków wywoławczych, współrzędnych, adresu serwera ani loginu —
nowy wpis musi trzymać się tej zasady. Dziennik aplikacji: plik `files/events.log`, 500 wpisów,
strona „Dziennik zdarzeń”. Dziennik wtyczki: plik w danych ATAK, 300 wpisów, podgląd w panelu;
„Udostępnij dziennik” zapisuje kopię w `atak/tools/autotak/AutoTAK-plugin-events.txt`.

## Skąd wtyczka bierze dane

- **Obiekty:** wszystkie obiekty mapy ATAK, które da się zapisać jako CoT (`CotEventFactory`).
  ATAK nie wpisuje do takiego zapisu zespołu, roli, telefonu ani e-maila kontaktu — wtyczka
  dopisuje je z danych obiektu (`team`, `atakRoleType`, `phoneNumber`, `emailAddress`).
- **Zmiany:** zdarzenia mapy (dodanie, usunięcie, odświeżenie, zapis, przeciągnięcie) oraz nasłuch
  ruchu każdego obiektu punktowego — ATAK nie zgłasza zdarzeniem przesunięcia kontaktu po
  wiadomości z sieci. Dodatkowo co 15 s porównanie całej mapy z tym, co wysłano (skrót treści
  bez czasów zdarzenia).
- **Ikony:** baza zestawów ikon użytkownika ATAK. Aplikacja pobiera zestaw, którego nie ma albo
  który ma inną liczbę ikon.
- **Mapy:** pliki definicji map online z folderów `imagery/mobile/mapsources` i z bazy warstw
  ATAK. Aplikacja dodaje te, które obsługuje jej silnik (kafelki XYZ i WMS); z opcją „Mapa jak
  w ATAK” przełącza się na mapę o tej samej nazwie, co wybrana w ATAK.

## Po stronie aplikacji

- Obiekty z ATAK trafiają do wspólnego magazynu z flagą `linked` (ważność i kolejność czasu ich
  nie odrzucają — o życiu obiektu decyduje ATAK). Po rozłączeniu zostają 60 s, potem znikają.
- Własna pozycja: `SelfLocation.linked` wyłącza własny odbiornik; pozycja, prędkość i kierunek
  pochodzą z ATAK. Znak wywoławczy, zespół i rola w ramce własnej — także z ATAK.
- Dane własne z ATAK są tylko pokazywane (`AppSettings.shownCallsign` itd.); własny profil
  aplikacji zostaje bez zmian (od 2.0.1 — w 2.0.0 strona profilu mogła go nadpisać).
- Kropka stanu: zielona, gdy wtyczka jest podłączona i ATAK ma połączenie z serwerem.
- Bez łącza ramka własna pokazuje „Brak połączenia z ATAK”; przy podłączeniu samochodu na telefon
  trafia powiadomienie „AutoTAK czeka na ATAK”, które uruchamia ATAK.
- W ustawieniach działy „Połączenie”, „Profil” i „DataSync” są ukryte.

## Stan sprawdzenia (2026-11-21)

Sprawdzone na telefonie z ATAK 5.8.0.7 (SDK, developer build) i w symulatorze Android Auto, na
obiektach wstrzykniętych lokalnie do ATAK (kompilacja testowa wtyczki, bez serwera): połączenie
i ponowne połączenie, pełny obraz (207 obiektów w 0,3 s), ruch kontaktu, dane kontaktowe,
usuwanie, zaznaczanie w ATAK, 10 zestawów ikon (3677 ikon), 22 definicje map, „Mapa jak w ATAK”,
przełączanie trybów, stan bez ATAK i powiadomienie, szukanie kontaktu.

Niesprawdzone:

- własna pozycja odczytana z ATAK z prawdziwym GNSS (sprawdzona tylko strona aplikacji, na
  pozycji testowej podanej przez kompilację testową wtyczki),
- zawartość misji DataSync i kontakty z serwera — wymagają ATAK ze sklepu Play i wtyczki
  podpisanej w pipeline TAK,
- wersja wydaniowa wtyczki (debug ładuje się tylko w ATAK z SDK),
- prawdziwy samochód.
