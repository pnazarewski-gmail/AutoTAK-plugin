// Interfejs wystawiany przez aplikację AutoTAK; woła go wtyczka AutoTAK-plugin z procesu ATAK.
package dev.autotak.companion;

import dev.autotak.companion.ICompanionPlugin;

interface ICompanionHost {
    /**
     * Początek połączenia. Zwraca wersję protokołu, w której AutoTAK będzie rozmawiać,
     * albo 0, gdy połączenie jest odrzucone (np. tryb powiązany wyłączony).
     */
    int hello(int protocol, String pluginVersion, String atakVersion, ICompanionPlugin plugin);

    /** Paczka obiektów jako CoT XML. flags: bit 1 — początek pełnego obrazu, bit 2 — jego koniec. */
    void events(in List<String> cot, int flags);

    /** Obiekty usunięte z mapy ATAK. */
    void removed(in List<String> uids);

    /** Stan w JSON: własna pozycja i dane, stan połączenia ATAK z serwerem, wybrana mapa. */
    void state(String json);

    /**
     * Konfiguracja w JSON do uzgodnienia między modułami: wykaz zestawów ikon ATAK
     * (identyfikator, nazwa, wersja, liczba ikon) i definicje map online (pliki XML).
     */
    void config(String json);

    /**
     * Od protokołu 2: dane aplikacji w JSON — `app` (wersja), `build` (skrót commita), `protocol`.
     * Wtyczka woła to tylko wtedy, gdy `hello` zwróciło co najmniej 2; starsza aplikacja tego nie ma.
     */
    String hostInfo();

    /**
     * Od protokołu 3: obiekt wybrany w ATAK (dotknięcie na mapie ATAK); null — wybór zdjęty.
     * Co z tym zrobi aplikacja, zależy od jej ustawienia „Po wybraniu obiektu w ATAK”.
     */
    void selected(String uid);
}
